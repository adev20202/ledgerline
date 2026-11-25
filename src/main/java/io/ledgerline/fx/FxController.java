package io.ledgerline.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.ledgerline.common.NotFoundException;
import io.ledgerline.fx.FxService.ClearingAccount;
import io.ledgerline.fx.FxService.Rate;
import io.ledgerline.idempotency.IdempotencyService;
import io.ledgerline.journal.JournalQueryService;
import io.ledgerline.journal.JournalQueryService.EntryView;
import io.ledgerline.web.TenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/fx")
public class FxController {

    private final FxService fxService;
    private final IdempotencyService idempotency;

    public FxController(FxService fxService, IdempotencyService idempotency) {
        this.fxService = fxService;
        this.idempotency = idempotency;
    }

    public record AddRateRequest(
            @NotBlank @Size(min = 3, max = 3) String base,
            @NotBlank @Size(min = 3, max = 3) String quote,
            @NotNull @Positive BigDecimal rate,
            @NotNull LocalDate effectiveDate) {
    }

    public record ClearingAccountRequest(@NotNull UUID accountId) {
    }

    public record ConversionRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @Positive long amountMinor,
            LocalDate rateDate,
            @Size(max = 100) String reference,
            @Size(max = 500) String description) {
    }

    public record ConversionView(EntryView entry, Rate rate, long sourceAmountMinor, long targetAmountMinor) {
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    public Rate addRate(TenantId tenant, @Valid @RequestBody AddRateRequest request) {
        return fxService.addRate(tenant.value(), request.base(), request.quote(), request.rate(),
                request.effectiveDate());
    }

    @GetMapping("/rates")
    public Rate rate(TenantId tenant, @RequestParam String base, @RequestParam String quote,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return fxService.findRate(tenant.value(), base, quote, on)
                .orElseThrow(() -> new NotFoundException("Rate", base + "/" + quote + " on " + on));
    }

    @PutMapping("/clearing-accounts/{currency}")
    public List<ClearingAccount> setClearingAccount(TenantId tenant, @PathVariable String currency,
            @Valid @RequestBody ClearingAccountRequest request) {
        fxService.setClearingAccount(tenant.value(), currency, request.accountId());
        return fxService.clearingAccounts(tenant.value());
    }

    @GetMapping("/clearing-accounts")
    public List<ClearingAccount> clearingAccounts(TenantId tenant) {
        return fxService.clearingAccounts(tenant.value());
    }

    @PostMapping("/conversions")
    @Transactional
    public ResponseEntity<Object> convert(TenantId tenant, @Valid @RequestBody ConversionRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return idempotency.execute(tenant.value(), idempotencyKey, "POST /api/fx/conversions", request,
                HttpStatus.CREATED, () -> {
                    FxService.Conversion c = fxService.convert(tenant.value(), new FxService.ConversionCommand(
                            request.fromAccountId(), request.toAccountId(), request.amountMinor(),
                            request.rateDate(), request.reference(), request.description()));
                    return new ConversionView(JournalQueryService.view(c.entry()), c.rate(), c.sourceAmountMinor(),
                            c.targetAmountMinor());
                });
    }
}
