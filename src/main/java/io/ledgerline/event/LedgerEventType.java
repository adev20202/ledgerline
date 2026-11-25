package io.ledgerline.event;

public enum LedgerEventType {
    ACCOUNT_OPENED,
    ACCOUNT_CLOSED,
    OVERDRAFT_LIMIT_SET,
    ENTRY_POSTED,
    ENTRY_REVERSED,
    HOLD_PLACED,
    HOLD_CAPTURED,
    HOLD_RELEASED,
    HOLD_EXPIRED
}
