package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountType;
import io.ledgerline.common.ConflictException;
import io.ledgerline.common.Direction;
import io.ledgerline.fx.FxService;
import io.ledgerline.fx.FxService.Conversion;
import io.ledgerline.fx.FxService.ConversionCommand;
import io.ledgerline.idempotency.IdempotencyService;
import io.ledgerline.journal.JournalEntry;
import io.ledgerline.journal.PostingService.Line;
import io.ledgerline.journal.PostingService.PostEntryCommand;
import io.ledgerline.projection.BalanceQueryService;

@AutoConfigureMockMvc
class FxIntegrationTest extends IntegrationTestBase {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate NOV_10 = LocalDate.of(2026, 11, 10);
    static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);

    @Autowired
    FxService fx;
    @Autowired
    BalanceQueryService balances;
    @Autowired
    MockMvc mvc;

    UUID tenant;
    String apiKey;
    Account usdCash;
    Account eurCash;
    Account usdClearing;
    Account eurClearing;
    Account equity;

    @BeforeEach
    void setUp() {
        var issued = tenantService.create("Acme", "acme");
        tenant = issued.tenant().getId();
        apiKey = issued.apiKey();
        usdCash = open(tenant, "1000", AccountType.ASSET, "USD");
        eurCash = open(tenant, "1001", AccountType.ASSET, "EUR");
        usdClearing = open(tenant, "3900-USD", AccountType.EQUITY, "USD");
        eurClearing = open(tenant, "3900-EUR", AccountType.EQUITY, "EUR");
        equity = open(tenant, "3000", AccountType.EQUITY, "USD");
        fx.setClearingAccount(tenant, "USD", usdClearing.getId());
        fx.setClearingAccount(tenant, "EUR", eurClearing.getId());
        transfer(tenant, usdCash, equity, 1_000_00);
    }

    @Test
    void manualEntryBalancedPerCurrencyIsAccepted() {
        JournalEntry entry = postingService.post(tenant, new PostEntryCommand(null, "swap", null, null, List.of(
                new Line(usdCash.getId(), Direction.CREDIT, 100_00, null),
                new Line(usdClearing.getId(), Direction.DEBIT, 100_00, null),
                new Line(eurClearing.getId(), Direction.CREDIT, 90_00, null),
                new Line(eurCash.getId(), Direction.DEBIT, 90_00, null))));

        assertThat(entry.getCurrency()).isNull();
        assertThat(entry.getLines()).extracting(l -> l.getCurrency()).containsExactly("USD", "USD", "EUR", "EUR");
    }

    @Test
    void entryMustBalanceInEachCurrency() {
        // equal totals overall, but USD and EUR each unbalanced
        assertThatThrownBy(() -> postingService.post(tenant, new PostEntryCommand(null, "bad", null, null, List.of(
                new Line(usdCash.getId(), Direction.CREDIT, 100_00, null),
                new Line(eurCash.getId(), Direction.DEBIT, 100_00, null)))))
                .extracting("code").isEqualTo("unbalanced");
    }

    @Test
    void conversionPostsFourLinesAndBalancesBothCurrencies() {
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.92"), NOV_1);

        Conversion c = fx.convert(tenant, new ConversionCommand(usdCash.getId(), eurCash.getId(), 100_00, NOV_10,
                "FX-1", null));
        projectAll();

        assertThat(c.targetAmountMinor()).isEqualTo(92_00);
        assertThat(c.entry().getLines()).hasSize(4);
        assertThat(balances.balanceOf(tenant, usdCash.getId()).balanceMinor()).isEqualTo(900_00);
        assertThat(balances.balanceOf(tenant, eurCash.getId()).balanceMinor()).isEqualTo(92_00);
        assertThat(balances.trialBalance(tenant, "USD").balanced()).isTrue();
        assertThat(balances.trialBalance(tenant, "EUR").balanced()).isTrue();
        assertThat(jdbc.queryForObject("select rate from fx_conversions where entry_id = ?", BigDecimal.class,
                c.entry().getId())).isEqualByComparingTo("0.92");
    }

    @Test
    void roundsHalfEvenToTheTargetMinorUnit() {
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.5"), NOV_1);

        // 3 cents * 0.5 = 1.5 -> 2, 5 cents * 0.5 = 2.5 -> 2
        assertThat(convert(3).targetAmountMinor()).isEqualTo(2);
        assertThat(convert(5).targetAmountMinor()).isEqualTo(2);
        assertThat(convert(7).targetAmountMinor()).isEqualTo(4);
    }

    @Test
    void usesTheTargetCurrencyDigits() {
        Account jpy = open(tenant, "1002", AccountType.ASSET, "JPY");
        Account jpyClearing = open(tenant, "3900-JPY", AccountType.EQUITY, "JPY");
        fx.setClearingAccount(tenant, "JPY", jpyClearing.getId());
        fx.addRate(tenant, "USD", "JPY", new BigDecimal("150.123"), NOV_1);

        Conversion c = fx.convert(tenant, new ConversionCommand(usdCash.getId(), jpy.getId(), 100_00, NOV_10, null,
                null));

        assertThat(c.targetAmountMinor()).isEqualTo(15_012);
    }

    @Test
    void usesTheInverseWhenOnlyTheOtherPairIsStored() {
        fx.addRate(tenant, "EUR", "USD", new BigDecimal("1.25"), NOV_1);

        Conversion c = convert(100_00);

        assertThat(c.rate().inverted()).isTrue();
        assertThat(c.rate().rate()).isEqualByComparingTo("0.8");
        assertThat(c.targetAmountMinor()).isEqualTo(80_00);
    }

    @Test
    void picksTheRateInEffectOnTheDate() {
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.90"), NOV_1);
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.95"), NOV_20);

        assertThat(fx.findRate(tenant, "USD", "EUR", NOV_10).orElseThrow().rate()).isEqualByComparingTo("0.90");
        assertThat(fx.findRate(tenant, "USD", "EUR", NOV_20).orElseThrow().rate()).isEqualByComparingTo("0.95");
        assertThatThrownBy(() -> fx.convert(tenant, new ConversionCommand(usdCash.getId(), eurCash.getId(), 100,
                LocalDate.of(2026, 10, 31), null, null))).extracting("code").isEqualTo("fx_rate_missing");
    }

    @Test
    void ratesAreScopedToTheTenant() {
        UUID other = newTenant("other");
        fx.addRate(other, "USD", "EUR", new BigDecimal("0.9"), NOV_1);

        assertThat(fx.findRate(tenant, "USD", "EUR", NOV_10)).isEmpty();
    }

    @Test
    void storedRateCannotBeReplaced() {
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.9"), NOV_1);

        assertThatThrownBy(() -> fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.91"), NOV_1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void conversionNeedsClearingAccounts() {
        Account gbp = open(tenant, "1003", AccountType.ASSET, "GBP");
        fx.addRate(tenant, "USD", "GBP", new BigDecimal("0.8"), NOV_1);

        assertThatThrownBy(() -> fx.convert(tenant, new ConversionCommand(usdCash.getId(), gbp.getId(), 100, NOV_10,
                null, null))).extracting("code").isEqualTo("fx_clearing_account_missing");
    }

    @Test
    void clearingAccountMustBeInItsCurrency() {
        assertThatThrownBy(() -> fx.setClearingAccount(tenant, "EUR", usdClearing.getId()))
                .extracting("code").isEqualTo("currency_mismatch");
    }

    @Test
    void rejectsSameCurrencyAndTinyAmounts() {
        Account usdBank = open(tenant, "1100", AccountType.ASSET, "USD");
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.004"), NOV_1);

        assertThatThrownBy(() -> fx.convert(tenant, new ConversionCommand(usdCash.getId(), usdBank.getId(), 100,
                NOV_10, null, null))).extracting("code").isEqualTo("same_currency");
        assertThatThrownBy(() -> convert(1)).extracting("code").isEqualTo("conversion_too_small");
    }

    @Test
    void reversingAConversionClearsBothCurrencies() {
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.92"), NOV_1);
        Conversion c = convert(100_00);

        postingService.reverse(tenant, c.entry().getId(), "wrong account");
        projectAll();

        assertThat(balances.balanceOf(tenant, usdCash.getId()).balanceMinor()).isEqualTo(1_000_00);
        assertThat(balances.balanceOf(tenant, eurCash.getId()).balanceMinor()).isZero();
        assertThat(balances.balanceOf(tenant, eurClearing.getId()).balanceMinor()).isZero();
    }

    @Test
    void olderEventsWithoutLineCurrencyStillProject() {
        long seq = jdbc.queryForObject("update tenant_event_sequences set last_sequence = last_sequence + 1 "
                + "where tenant_id = ? returning last_sequence", Long.class, tenant);
        jdbc.update("""
                insert into ledger_events (tenant_id, tenant_sequence, event_type, payload, occurred_at)
                values (?, ?, 'ENTRY_POSTED', ?::jsonb, now())
                """, tenant, seq, """
                {"entryId":"%s","currency":"USD","effectiveDate":"2026-01-01","lines":[
                 {"accountId":"%s","direction":"DEBIT","amountMinor":40},
                 {"accountId":"%s","direction":"CREDIT","amountMinor":40}]}
                """.formatted(UUID.randomUUID(), usdCash.getId(), equity.getId()));

        projectAll();

        assertThat(balances.balanceOf(tenant, usdCash.getId()).balanceMinor()).isEqualTo(1_000_40);
    }

    @Test
    void conversionEndpointIsIdempotent() throws Exception {
        fx.addRate(tenant, "USD", "EUR", new BigDecimal("0.92"), NOV_1);
        String body = """
                {"fromAccountId":"%s","toAccountId":"%s","amountMinor":5000,"rateDate":"2026-11-10"}
                """.formatted(usdCash.getId(), eurCash.getId());

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/fx/conversions").header("X-Api-Key", apiKey)
                            .header(IdempotencyService.HEADER, "conv-1").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.targetAmountMinor").value(4600))
                    .andExpect(jsonPath("$.entry.lines.length()").value(4));
        }
        assertThat(jdbc.queryForObject("select count(*) from fx_conversions", Integer.class)).isEqualTo(1);
    }

    private Conversion convert(long amountMinor) {
        return fx.convert(tenant, new ConversionCommand(usdCash.getId(), eurCash.getId(), amountMinor, NOV_10, null,
                null));
    }
}
