package io.ledgerline.account;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.ledgerline.journal.JournalQueryService;
import io.ledgerline.journal.JournalQueryService.StatementLineView;
import io.ledgerline.projection.BalanceQueryService;
import io.ledgerline.projection.BalanceQueryService.AccountBalanceView;
import io.ledgerline.web.TenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/accounts")
@Validated
public class AccountController {

    private final AccountService accountService;
    private final BalanceQueryService balanceQueryService;
    private final JournalQueryService journalQueryService;

    public AccountController(AccountService accountService, BalanceQueryService balanceQueryService,
            JournalQueryService journalQueryService) {
        this.accountService = accountService;
        this.balanceQueryService = balanceQueryService;
        this.journalQueryService = journalQueryService;
    }

    public record OpenAccountRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,31}") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull AccountType type,
            @NotBlank @Size(min = 3, max = 3) String currency) {
    }

    public record AccountView(UUID id, String code, String name, AccountType type, String currency,
            AccountStatus status, Instant createdAt, Instant closedAt) {
        static AccountView from(Account a) {
            return new AccountView(a.getId(), a.getCode(), a.getName(), a.getType(), a.getCurrency(), a.getStatus(),
                    a.getCreatedAt(), a.getClosedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView open(TenantId tenant, @Valid @RequestBody OpenAccountRequest request) {
        return AccountView.from(accountService.open(tenant.value(),
                new AccountService.OpenAccountCommand(request.code(), request.name(), request.type(),
                        request.currency())));
    }

    @GetMapping
    public List<AccountView> list(TenantId tenant, @RequestParam(required = false) AccountType type) {
        return accountService.list(tenant.value(), type).stream().map(AccountView::from).toList();
    }

    @GetMapping("/{id}")
    public AccountView get(TenantId tenant, @PathVariable UUID id) {
        return AccountView.from(accountService.get(tenant.value(), id));
    }

    @PostMapping("/{id}/close")
    public AccountView close(TenantId tenant, @PathVariable UUID id) {
        return AccountView.from(accountService.close(tenant.value(), id));
    }

    @GetMapping("/{id}/balance")
    public AccountBalanceView balance(TenantId tenant, @PathVariable UUID id) {
        return balanceQueryService.balanceOf(tenant.value(), id);
    }

    @GetMapping("/{id}/statement")
    public List<StatementLineView> statement(TenantId tenant, @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int size) {
        accountService.get(tenant.value(), id);
        return journalQueryService.statement(tenant.value(), id, from, to, page, size);
    }
}
