package io.ledgerline.account;

import io.ledgerline.common.Direction;

public enum AccountType {
    ASSET(Direction.DEBIT),
    EXPENSE(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    INCOME(Direction.CREDIT);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    public Direction normalSide() {
        return normalSide;
    }

    /** Balance in minor units, positive when the account sits on its normal side. */
    public long signedBalance(long debitTotal, long creditTotal) {
        return normalSide == Direction.DEBIT
                ? Math.subtractExact(debitTotal, creditTotal)
                : Math.subtractExact(creditTotal, debitTotal);
    }
}
