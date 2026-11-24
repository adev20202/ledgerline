package io.ledgerline.common;

import java.util.Currency;
import java.util.Locale;

public final class Currencies {

    private Currencies() {
    }

    /** Returns the ISO 4217 currency for the code, or throws a rule violation. */
    public static Currency require(String code) {
        if (code == null || code.length() != 3) {
            throw new LedgerRuleException("unknown_currency", "Currency must be a 3-letter ISO 4217 code");
        }
        try {
            return Currency.getInstance(code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LedgerRuleException("unknown_currency", "Unknown currency " + code);
        }
    }
}
