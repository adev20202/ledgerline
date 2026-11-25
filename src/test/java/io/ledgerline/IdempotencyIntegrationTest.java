package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountType;
import io.ledgerline.idempotency.IdempotencyService;

@AutoConfigureMockMvc
class IdempotencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    IdempotencyService idempotencyService;

    UUID tenant;
    String apiKey;
    Account cash;
    Account revenue;

    @BeforeEach
    void setUp() {
        var issued = tenantService.create("Acme", "acme");
        tenant = issued.tenant().getId();
        apiKey = issued.apiKey();
        cash = open(tenant, "1000", AccountType.ASSET, "USD");
        revenue = open(tenant, "4000", AccountType.INCOME, "USD");
    }

    @Test
    void sameKeyTenTimesCreatesOneEntry() throws Exception {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = postEntry("key-1", 500).andExpect(status().isCreated())
                    .andReturn().getResponse();
            ids.add(json.readTree(response.getContentAsString()).get("id").asText());
            assertThat(response.getHeader(IdempotencyService.REPLAYED_HEADER)).isEqualTo(i == 0 ? null : "true");
        }

        assertThat(ids).hasSize(1);
        assertThat(count("journal_entries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_events where event_type = 'ENTRY_POSTED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        postEntry("key-1", 500).andExpect(status().isCreated());

        postEntry("key-1", 501).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        assertThat(count("journal_entries")).isEqualTo(1);
    }

    @Test
    void sameKeyOnAnotherEndpointIsRejected() throws Exception {
        postEntry("key-1", 500).andExpect(status().isCreated());

        mvc.perform(post("/api/accounts").header("X-Api-Key", apiKey).header(IdempotencyService.HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"1100\",\"name\":\"Bank\",\"type\":\"ASSET\",\"currency\":\"USD\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void keysAreScopedToTheTenant() throws Exception {
        var other = tenantService.create("Globex", "globex");
        Account otherCash = open(other.tenant().getId(), "1000", AccountType.ASSET, "USD");
        Account otherRevenue = open(other.tenant().getId(), "4000", AccountType.INCOME, "USD");

        postEntry("key-1", 500).andExpect(status().isCreated());
        mvc.perform(entryRequest(other.apiKey(), "key-1", otherCash, otherRevenue, 500))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyService.REPLAYED_HEADER));

        assertThat(count("journal_entries")).isEqualTo(2);
    }

    @Test
    void failedRequestCanBeRetriedWithTheSameKey() throws Exception {
        mvc.perform(post("/api/entries").header("X-Api-Key", apiKey).header(IdempotencyService.HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(cash, revenue, 500).replace("\"CREDIT\",\"amountMinor\":500",
                                "\"CREDIT\",\"amountMinor\":499")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("idempotency_keys")).isZero();

        postEntry("key-1", 500).andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyService.REPLAYED_HEADER));
    }

    @Test
    void parallelRequestsWithTheSameKeyCreateOneEntry() throws Exception {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Throwable> failures = ConcurrentPostingIntegrationTest.runInParallel(8, i -> {
            MockHttpServletResponse response = postEntry("key-p", 900).andExpect(status().isCreated())
                    .andReturn().getResponse();
            ids.add(json.readTree(response.getContentAsString()).get("id").asText());
        });

        assertThat(failures).isEmpty();
        assertThat(ids).hasSize(1);
        assertThat(count("journal_entries")).isEqualTo(1);
    }

    @Test
    void expiredKeyStartsFresh() throws Exception {
        postEntry("key-1", 500).andExpect(status().isCreated());
        jdbc.update("update idempotency_keys set expires_at = now() - interval '1 minute'");

        postEntry("key-1", 777).andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyService.REPLAYED_HEADER));
        assertThat(count("journal_entries")).isEqualTo(2);
    }

    @Test
    void purgeRemovesOnlyExpiredKeys() throws Exception {
        postEntry("old", 500).andExpect(status().isCreated());
        postEntry("new", 600).andExpect(status().isCreated());
        jdbc.update("update idempotency_keys set expires_at = now() - interval '1 minute' where idempotency_key = 'old'");

        assertThat(idempotencyService.purgeExpired()).isEqualTo(1);
        assertThat(jdbc.queryForList("select idempotency_key from idempotency_keys", String.class))
                .containsExactly("new");
    }

    @Test
    void reversalWithKeyIsSafeToRetry() throws Exception {
        String entryId = json.readTree(postEntry(null, 500).andReturn().getResponse().getContentAsString())
                .get("id").asText();

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/entries/" + entryId + "/reversal").header("X-Api-Key", apiKey)
                            .header(IdempotencyService.HEADER, "rev-1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"duplicate\"}"))
                    .andExpect(status().isCreated());
        }
        assertThat(count("journal_entries")).isEqualTo(2);
    }

    @Test
    void withoutKeyEveryRequestRuns() throws Exception {
        postEntry(null, 500).andExpect(status().isCreated());
        postEntry(null, 500).andExpect(status().isCreated());

        assertThat(count("journal_entries")).isEqualTo(2);
        assertThat(count("idempotency_keys")).isZero();
    }

    @Test
    void overlongKeyIsRejected() throws Exception {
        postEntry("k".repeat(201), 500).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_idempotency_key"));
    }

    private org.springframework.test.web.servlet.ResultActions postEntry(String key, long amount) throws Exception {
        return mvc.perform(entryRequest(apiKey, key, cash, revenue, amount));
    }

    private org.springframework.test.web.servlet.RequestBuilder entryRequest(String apiKey, String key, Account debit,
            Account credit, long amount) {
        var request = post("/api/entries").header("X-Api-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                .content(entryBody(debit, credit, amount));
        return key == null ? request : request.header(IdempotencyService.HEADER, key);
    }

    private static String entryBody(Account debit, Account credit, long amount) {
        return """
                {"description":"sale","currency":"USD","lines":[
                {"accountId":"%s","direction":"DEBIT","amountMinor":%d},
                {"accountId":"%s","direction":"CREDIT","amountMinor":%d}]}"""
                .formatted(debit.getId(), amount, credit.getId(), amount).replace("\n", "");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
