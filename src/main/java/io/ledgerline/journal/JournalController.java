package io.ledgerline.journal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.ledgerline.common.Direction;
import io.ledgerline.idempotency.IdempotencyService;
import io.ledgerline.journal.JournalQueryService.EntryView;
import io.ledgerline.web.TenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/entries")
@Validated
public class JournalController {

    private final PostingService postingService;
    private final JournalQueryService queryService;
    private final IdempotencyService idempotency;

    public JournalController(PostingService postingService, JournalQueryService queryService,
            IdempotencyService idempotency) {
        this.postingService = postingService;
        this.queryService = queryService;
        this.idempotency = idempotency;
    }

    public record PostEntryRequest(
            @Size(max = 100) String reference,
            @NotBlank @Size(max = 500) String description,
            LocalDate effectiveDate,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull @Size(min = 2, max = 200) List<@Valid LineRequest> lines) {
    }

    public record LineRequest(
            @NotNull UUID accountId,
            @NotNull Direction direction,
            @Positive long amountMinor,
            @Size(max = 200) String memo) {
    }

    public record ReverseRequest(@NotBlank @Size(max = 400) String reason) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Object> post(TenantId tenant, @Valid @RequestBody PostEntryRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        List<PostingService.Line> lines = request.lines().stream()
                .map(l -> new PostingService.Line(l.accountId(), l.direction(), l.amountMinor(), l.memo()))
                .toList();
        return idempotency.execute(tenant.value(), idempotencyKey, "POST /api/entries", request, HttpStatus.CREATED,
                () -> JournalQueryService.view(postingService.post(tenant.value(),
                        new PostingService.PostEntryCommand(request.reference(), request.description(),
                                request.effectiveDate(), request.currency(), lines))));
    }

    @GetMapping("/{id}")
    public EntryView get(TenantId tenant, @PathVariable UUID id) {
        return queryService.get(tenant.value(), id);
    }

    @GetMapping
    public List<EntryView> list(TenantId tenant,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return queryService.list(tenant.value(), from, to, page, size);
    }

    @PostMapping("/{id}/reversal")
    @Transactional
    public ResponseEntity<Object> reverse(TenantId tenant, @PathVariable UUID id,
            @Valid @RequestBody ReverseRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return idempotency.execute(tenant.value(), idempotencyKey, "POST /api/entries/" + id + "/reversal", request,
                HttpStatus.CREATED,
                () -> JournalQueryService.view(postingService.reverse(tenant.value(), id, request.reason())));
    }
}
