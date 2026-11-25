package io.ledgerline.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.ledgerline.common.ConflictException;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.config.LedgerProperties;

/**
 * Runs a write at most once per tenant and Idempotency-Key.
 *
 * <p>The key row is inserted in the caller's transaction before the write. A
 * second request with the same key waits on the primary key until the first
 * transaction ends: if it committed, the stored response is replayed; if it
 * rolled back, the second request runs. Responses are kept for
 * {@code ledger.idempotency.ttl}.
 */
@Component
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LedgerProperties properties;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper, LedgerProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    private record Stored(String requestHash, int status, String body) {
    }

    /**
     * @param operation names the endpoint, for example "POST /api/entries"
     * @param request   the request body; a key reused with a different body
     *                  or operation is rejected
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> ResponseEntity<Object> execute(UUID tenantId, String key, String operation, Object request,
            HttpStatus status, Supplier<T> action) {
        if (key == null) {
            return ResponseEntity.status(status).body(action.get());
        }
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new LedgerRuleException("invalid_idempotency_key",
                    "Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }

        String hash = fingerprint(operation, request);
        Instant now = clock.instant();
        jdbc.update("delete from idempotency_keys where tenant_id = ? and idempotency_key = ? and expires_at <= ?",
                tenantId, key, Timestamp.from(now));
        int inserted = jdbc.update("""
                insert into idempotency_keys (tenant_id, idempotency_key, request_hash, created_at, expires_at)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """, tenantId, key, hash, Timestamp.from(now),
                Timestamp.from(now.plus(properties.idempotency().ttl())));

        if (inserted == 0) {
            return replay(tenantId, key, hash);
        }

        T result = action.get();
        jdbc.update("""
                update idempotency_keys set response_status = ?, response_body = ?::jsonb
                where tenant_id = ? and idempotency_key = ?
                """, status.value(), write(result), tenantId, key);
        return ResponseEntity.status(status).body(result);
    }

    /** Removes expired keys. Returns how many were removed. */
    @Transactional
    public int purgeExpired() {
        return jdbc.update("delete from idempotency_keys where expires_at <= ?", Timestamp.from(clock.instant()));
    }

    private ResponseEntity<Object> replay(UUID tenantId, String key, String hash) {
        List<Stored> rows = jdbc.query("""
                select request_hash, response_status, response_body::text from idempotency_keys
                where tenant_id = ? and idempotency_key = ?
                """, (rs, n) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)), tenantId, key);
        if (rows.isEmpty()) {
            // the other request's key expired and was purged between our insert and select
            throw new ConflictException("idempotency_key_in_flux", "Retry the request");
        }
        Stored stored = rows.get(0);
        if (!stored.requestHash().equals(hash)) {
            throw new ConflictException("idempotency_key_reused",
                    "Idempotency-Key " + key + " was already used for a different request");
        }
        return ResponseEntity.status(stored.status()).header(REPLAYED_HEADER, "true").body(read(stored.body()));
    }

    private String fingerprint(String operation, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store response", e);
        }
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response is not JSON", e);
        }
    }
}
