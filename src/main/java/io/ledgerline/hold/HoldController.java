package io.ledgerline.hold;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.ledgerline.idempotency.IdempotencyService;
import io.ledgerline.journal.JournalQueryService;
import io.ledgerline.journal.JournalQueryService.EntryView;
import io.ledgerline.web.TenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/holds")
public class HoldController {

    private final HoldService holdService;
    private final IdempotencyService idempotency;

    public HoldController(HoldService holdService, IdempotencyService idempotency) {
        this.holdService = holdService;
        this.idempotency = idempotency;
    }

    public record PlaceHoldRequest(
            @NotNull UUID accountId,
            @Positive long amountMinor,
            @Min(1) @Max(2_592_000) long expiresInSeconds,
            @Size(max = 100) String reference) {
    }

    public record CaptureRequest(@NotNull UUID counterAccountId, @Positive Long amountMinor,
            @Size(max = 500) String description) {
    }

    public record HoldView(UUID id, UUID accountId, long amountMinor, String currency, HoldStatus status,
            String reference, Instant expiresAt, Instant createdAt, Instant resolvedAt, UUID capturedEntryId,
            Long capturedAmountMinor) {
        static HoldView from(Hold h) {
            return new HoldView(h.getId(), h.getAccountId(), h.getAmountMinor(), h.getCurrency(), h.getStatus(),
                    h.getReference(), h.getExpiresAt(), h.getCreatedAt(), h.getResolvedAt(), h.getCapturedEntryId(),
                    h.getCapturedAmountMinor());
        }
    }

    public record CaptureView(HoldView hold, EntryView entry) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Object> place(TenantId tenant, @Valid @RequestBody PlaceHoldRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return idempotency.execute(tenant.value(), idempotencyKey, "POST /api/holds", request, HttpStatus.CREATED,
                () -> HoldView.from(holdService.place(tenant.value(), new HoldService.PlaceHoldCommand(
                        request.accountId(), request.amountMinor(), Duration.ofSeconds(request.expiresInSeconds()),
                        request.reference()))));
    }

    @GetMapping("/{id}")
    public HoldView get(TenantId tenant, @PathVariable UUID id) {
        return HoldView.from(holdService.get(tenant.value(), id));
    }

    @GetMapping
    public List<HoldView> list(TenantId tenant, @RequestParam UUID accountId,
            @RequestParam(required = false) HoldStatus status) {
        return holdService.list(tenant.value(), accountId, status).stream().map(HoldView::from).toList();
    }

    @PostMapping("/{id}/capture")
    @Transactional
    public ResponseEntity<Object> capture(TenantId tenant, @PathVariable UUID id,
            @Valid @RequestBody CaptureRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return idempotency.execute(tenant.value(), idempotencyKey, "POST /api/holds/" + id + "/capture", request,
                HttpStatus.CREATED, () -> {
                    HoldService.Capture c = holdService.capture(tenant.value(), id, new HoldService.CaptureCommand(
                            request.counterAccountId(), request.amountMinor(), request.description()));
                    return new CaptureView(HoldView.from(c.hold()), JournalQueryService.view(c.entry()));
                });
    }

    @PostMapping("/{id}/release")
    @Transactional
    public HoldView release(TenantId tenant, @PathVariable UUID id) {
        return HoldView.from(holdService.release(tenant.value(), id));
    }
}
