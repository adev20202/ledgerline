package io.ledgerline.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountRepository;
import io.ledgerline.common.ConflictException;
import io.ledgerline.common.Currencies;
import io.ledgerline.common.Direction;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.common.NotFoundException;
import io.ledgerline.journal.JournalEntry;
import io.ledgerline.journal.PostingService;

/**
 * FX rates per tenant, and conversions between two accounts in different
 * currencies.
 *
 * <p>A conversion posts one entry with four lines: value leaves the source
 * account and enters the target account, and each currency's clearing
 * account takes the other side, so the entry balances in both currencies.
 */
@Service
public class FxService {

    /** Rates are stored and inverted with this many decimal places. */
    static final int RATE_SCALE = 12;

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final PostingService postingService;
    private final Clock clock;

    public FxService(JdbcTemplate jdbc, AccountRepository accounts, PostingService postingService, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.postingService = postingService;
        this.clock = clock;
    }

    /** A rate as used for base -> quote; inverted when only quote -> base is stored. */
    public record Rate(String base, String quote, BigDecimal rate, LocalDate effectiveDate, boolean inverted) {
    }

    public record ConversionCommand(UUID fromAccountId, UUID toAccountId, long amountMinor, LocalDate rateDate,
            String reference, String description) {
    }

    public record Conversion(JournalEntry entry, Rate rate, long sourceAmountMinor, long targetAmountMinor) {
    }

    @Transactional
    public Rate addRate(UUID tenantId, String base, String quote, BigDecimal rate, LocalDate effectiveDate) {
        String b = Currencies.require(base).getCurrencyCode();
        String q = Currencies.require(quote).getCurrencyCode();
        if (b.equals(q)) {
            throw new LedgerRuleException("same_currency", "Base and quote currency must differ");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new LedgerRuleException("invalid_rate", "Rate must be greater than zero");
        }
        if (rate.stripTrailingZeros().scale() > RATE_SCALE || rate.precision() - rate.scale() > 12) {
            throw new LedgerRuleException("invalid_rate", "Rate has too many digits");
        }
        try {
            jdbc.update("""
                    insert into fx_rates (tenant_id, base_currency, quote_currency, effective_date, rate, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, tenantId, b, q, Date.valueOf(effectiveDate), rate, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("fx_rate_exists",
                    "A " + b + "/" + q + " rate for " + effectiveDate + " already exists");
        }
        return new Rate(b, q, rate.stripTrailingZeros(), effectiveDate, false);
    }

    /**
     * The rate for base -> quote in effect on the date: the stored pair if
     * there is one, otherwise the inverse of quote -> base.
     */
    @Transactional(readOnly = true)
    public Optional<Rate> findRate(UUID tenantId, String base, String quote, LocalDate on) {
        String b = Currencies.require(base).getCurrencyCode();
        String q = Currencies.require(quote).getCurrencyCode();
        Optional<Rate> direct = stored(tenantId, b, q, on);
        if (direct.isPresent()) {
            return direct;
        }
        return stored(tenantId, q, b, on).map(r -> new Rate(b, q,
                BigDecimal.ONE.divide(r.rate(), RATE_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros(),
                r.effectiveDate(), true));
    }

    @Transactional
    public void setClearingAccount(UUID tenantId, String currency, UUID accountId) {
        String c = Currencies.require(currency).getCurrencyCode();
        Account account = accounts.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new NotFoundException("Account", accountId));
        if (!account.isOpen()) {
            throw new LedgerRuleException("account_closed", "Account " + account.getCode() + " is closed");
        }
        if (!account.getCurrency().equals(c)) {
            throw new LedgerRuleException("currency_mismatch",
                    "Account " + account.getCode() + " is in " + account.getCurrency() + ", not " + c);
        }
        jdbc.update("""
                insert into fx_clearing_accounts (tenant_id, currency, account_id) values (?, ?, ?)
                on conflict (tenant_id, currency) do update set account_id = excluded.account_id
                """, tenantId, c, accountId);
    }

    public record ClearingAccount(String currency, UUID accountId) {
    }

    @Transactional(readOnly = true)
    public List<ClearingAccount> clearingAccounts(UUID tenantId) {
        return jdbc.query("select currency, account_id from fx_clearing_accounts where tenant_id = ? order by 1",
                (rs, n) -> new ClearingAccount(rs.getString(1), rs.getObject(2, UUID.class)), tenantId);
    }

    @Transactional
    public Conversion convert(UUID tenantId, ConversionCommand command) {
        Account from = account(tenantId, command.fromAccountId());
        Account to = account(tenantId, command.toAccountId());
        String source = from.getCurrency();
        String target = to.getCurrency();
        if (source.equals(target)) {
            throw new LedgerRuleException("same_currency", "Both accounts are in " + source);
        }
        if (command.amountMinor() <= 0) {
            throw new LedgerRuleException("non_positive_amount", "Amount must be greater than zero");
        }

        LocalDate on = command.rateDate() != null ? command.rateDate() : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Rate rate = findRate(tenantId, source, target, on).orElseThrow(() -> new LedgerRuleException(
                "fx_rate_missing", "No " + source + "/" + target + " rate in effect on " + on));
        long converted = convertAmount(command.amountMinor(), source, target, rate.rate());
        if (converted <= 0) {
            throw new LedgerRuleException("conversion_too_small",
                    command.amountMinor() + " in " + source + " is less than one minor unit of " + target);
        }

        UUID sourceClearing = clearingAccount(tenantId, source);
        UUID targetClearing = clearingAccount(tenantId, target);
        Direction out = from.getType().normalSide().opposite();
        Direction in = to.getType().normalSide();
        String memo = "FX 1 " + source + " = " + rate.rate().toPlainString() + " " + target + " (" + rate.effectiveDate()
                + ")";
        String description = command.description() != null
                ? command.description()
                : "Convert " + source + " to " + target;

        JournalEntry entry = postingService.post(tenantId, new PostingService.PostEntryCommand(command.reference(),
                description, null, null, List.of(
                        new PostingService.Line(from.getId(), out, command.amountMinor(), memo),
                        new PostingService.Line(sourceClearing, out.opposite(), command.amountMinor(), memo),
                        new PostingService.Line(targetClearing, in.opposite(), converted, memo),
                        new PostingService.Line(to.getId(), in, converted, memo))));

        jdbc.update("""
                insert into fx_conversions (entry_id, tenant_id, source_currency, target_currency,
                    source_amount_minor, target_amount_minor, rate, rate_date, inverted)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.getId(), tenantId, source, target, command.amountMinor(), converted, rate.rate(),
                Date.valueOf(rate.effectiveDate()), rate.inverted());
        return new Conversion(entry, rate, command.amountMinor(), converted);
    }

    /** Converts minor units of one currency to the other, rounding half-even. */
    static long convertAmount(long amountMinor, String source, String target, BigDecimal rate) {
        int sourceDigits = digits(source);
        int targetDigits = digits(target);
        BigDecimal result = BigDecimal.valueOf(amountMinor)
                .movePointLeft(sourceDigits)
                .multiply(rate, MathContext.UNLIMITED)
                .movePointRight(targetDigits)
                .setScale(0, RoundingMode.HALF_EVEN);
        try {
            return result.longValueExact();
        } catch (ArithmeticException e) {
            throw new LedgerRuleException("amount_overflow", "Converted amount is too large");
        }
    }

    private static int digits(String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (digits < 0) {
            throw new LedgerRuleException("unsupported_currency", currency + " has no minor unit");
        }
        return digits;
    }

    private Optional<Rate> stored(UUID tenantId, String base, String quote, LocalDate on) {
        return jdbc.query("""
                select rate, effective_date from fx_rates
                where tenant_id = ? and base_currency = ? and quote_currency = ? and effective_date <= ?
                order by effective_date desc limit 1
                """, (rs, n) -> new Rate(base, quote, rs.getBigDecimal(1).stripTrailingZeros(),
                rs.getDate(2).toLocalDate(), false), tenantId, base, quote, Date.valueOf(on)).stream().findFirst();
    }

    private UUID clearingAccount(UUID tenantId, String currency) {
        return jdbc.query("select account_id from fx_clearing_accounts where tenant_id = ? and currency = ?",
                (rs, n) -> rs.getObject(1, UUID.class), tenantId, currency).stream().findFirst()
                .orElseThrow(() -> new LedgerRuleException("fx_clearing_account_missing",
                        "No FX clearing account set for " + currency));
    }

    private Account account(UUID tenantId, UUID accountId) {
        return accounts.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new LedgerRuleException("unknown_account", "Account " + accountId + " does not exist"));
    }
}
