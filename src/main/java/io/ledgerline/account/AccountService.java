package io.ledgerline.account;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.common.ConflictException;
import io.ledgerline.common.Currencies;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.common.NotFoundException;
import io.ledgerline.event.EventStore;
import io.ledgerline.event.LedgerEventType;
import io.ledgerline.event.Payloads;
import io.ledgerline.projection.BalanceQueryService;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final EventStore eventStore;
    private final BalanceQueryService balances;
    private final Clock clock;

    public AccountService(AccountRepository accounts, EventStore eventStore, BalanceQueryService balances,
            Clock clock) {
        this.accounts = accounts;
        this.eventStore = eventStore;
        this.balances = balances;
        this.clock = clock;
    }

    public record OpenAccountCommand(String code, String name, AccountType type, String currency) {
    }

    @Transactional
    public Account open(UUID tenantId, OpenAccountCommand command) {
        String currency = Currencies.require(command.currency()).getCurrencyCode();
        if (accounts.existsByTenantIdAndCode(tenantId, command.code())) {
            throw new ConflictException("account_code_taken", "Account code " + command.code() + " is already in use");
        }
        Account account = accounts.save(new Account(UUID.randomUUID(), tenantId, command.code(), command.name(),
                command.type(), currency, clock.instant()));
        eventStore.append(tenantId, LedgerEventType.ACCOUNT_OPENED, new Payloads.AccountOpened(account.getId(),
                account.getCode(), account.getType().name(), account.getCurrency()));
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID tenantId, UUID accountId) {
        return accounts.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new NotFoundException("Account", accountId));
    }

    @Transactional(readOnly = true)
    public List<Account> list(UUID tenantId, AccountType type) {
        return type == null
                ? accounts.findAllByTenantIdOrderByCode(tenantId)
                : accounts.findAllByTenantIdAndTypeOrderByCode(tenantId, type);
    }

    /** Closes an account. Only accounts with a zero balance can be closed. */
    @Transactional
    public Account close(UUID tenantId, UUID accountId) {
        Account account = get(tenantId, accountId);
        if (!account.isOpen()) {
            throw new LedgerRuleException("account_closed", "Account " + account.getCode() + " is already closed");
        }
        long balance = balances.balanceOf(account).balanceMinor();
        if (balance != 0) {
            throw new LedgerRuleException("account_not_zero",
                    "Account " + account.getCode() + " has balance " + balance + " and cannot be closed");
        }
        account.close(clock.instant());
        eventStore.append(tenantId, LedgerEventType.ACCOUNT_CLOSED, new Payloads.AccountClosed(account.getId()));
        return account;
    }
}
