package io.ledgerline.common;

/** A request that is well formed but breaks a ledger rule. Maps to 422. */
public class LedgerRuleException extends RuntimeException {

    private final String code;

    public LedgerRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
