package io.ledgerline.projection;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountRepository;
import io.ledgerline.account.AccountType;
import io.ledgerline.common.Currencies;
import io.ledgerline.common.NotFoundException;
import io.ledgerline.hold.HoldRepository;

/**
 * Reads balances from the projection. Balances trail the journal by however
 * far the projector is behind. Held amounts are read live from holds.
 */
@Service
@Transactional(readOnly = true)
public class BalanceQueryService {

    private final AccountBalanceRepository balances;
    private final AccountRepository accounts;
    private final HoldRepository holds;
    private final Clock clock;

    public BalanceQueryService(AccountBalanceRepository balances, AccountRepository accounts, HoldRepository holds,
            Clock clock) {
        this.balances = balances;
        this.accounts = accounts;
        this.holds = holds;
        this.clock = clock;
    }

    /** availableMinor is balanceMinor less the amount held by live holds. */
    public record AccountBalanceView(UUID accountId, String currency, long debitTotalMinor, long creditTotalMinor,
            long balanceMinor, long heldMinor, long availableMinor, Instant updatedAt) {
    }

    public record TrialBalanceRow(UUID accountId, String code, String name, AccountType type, long debitMinor,
            long creditMinor) {
    }

    public record TrialBalanceView(String currency, List<TrialBalanceRow> rows, long totalDebitMinor,
            long totalCreditMinor, boolean balanced) {
    }

    public AccountBalanceView balanceOf(UUID tenantId, UUID accountId) {
        Account account = accounts.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new NotFoundException("Account", accountId));
        return balanceOf(account);
    }

    public AccountBalanceView balanceOf(Account account) {
        AccountBalance balance = balances.findById(account.getId()).orElse(null);
        long debits = balance == null ? 0 : balance.getDebitTotalMinor();
        long credits = balance == null ? 0 : balance.getCreditTotalMinor();
        long signed = account.getType().signedBalance(debits, credits);
        long held = holds.sumLive(account.getId(), clock.instant());
        return new AccountBalanceView(account.getId(), account.getCurrency(), debits, credits, signed, held,
                Math.subtractExact(signed, held), balance == null ? null : balance.getUpdatedAt());
    }

    /** Net debit or credit per account; the two totals are equal when the books balance. */
    public TrialBalanceView trialBalance(UUID tenantId, String currencyCode) {
        String currency = Currencies.require(currencyCode).getCurrencyCode();
        Map<UUID, AccountBalance> byAccount = balances.findAllByTenantIdAndCurrency(tenantId, currency).stream()
                .collect(Collectors.toMap(AccountBalance::getId, Function.identity()));

        List<TrialBalanceRow> rows = new ArrayList<>();
        long totalDebit = 0;
        long totalCredit = 0;
        for (Account account : accounts.findAllByTenantIdAndCurrencyOrderByCode(tenantId, currency)) {
            AccountBalance b = byAccount.get(account.getId());
            long net = b == null ? 0 : Math.subtractExact(b.getDebitTotalMinor(), b.getCreditTotalMinor());
            long debit = Math.max(net, 0);
            long credit = Math.max(-net, 0);
            totalDebit = Math.addExact(totalDebit, debit);
            totalCredit = Math.addExact(totalCredit, credit);
            rows.add(new TrialBalanceRow(account.getId(), account.getCode(), account.getName(), account.getType(),
                    debit, credit));
        }
        return new TrialBalanceView(currency, rows, totalDebit, totalCredit, totalDebit == totalCredit);
    }
}
