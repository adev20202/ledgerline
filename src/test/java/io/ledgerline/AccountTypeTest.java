package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.ledgerline.account.AccountType;
import io.ledgerline.tenant.ApiKeys;

class AccountTypeTest {

    @Test
    void debitNormalAccountsGrowWithDebits() {
        assertThat(AccountType.ASSET.signedBalance(100, 30)).isEqualTo(70);
        assertThat(AccountType.EXPENSE.signedBalance(0, 30)).isEqualTo(-30);
    }

    @Test
    void creditNormalAccountsGrowWithCredits() {
        assertThat(AccountType.LIABILITY.signedBalance(30, 100)).isEqualTo(70);
        assertThat(AccountType.INCOME.signedBalance(5, 0)).isEqualTo(-5);
    }

    @Test
    void overflowIsNotSilent() {
        assertThatThrownBy(() -> AccountType.ASSET.signedBalance(Long.MIN_VALUE, 1))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void apiKeysAreRandomAndHashStable() {
        String a = ApiKeys.generate();
        assertThat(a).startsWith("lk_").isNotEqualTo(ApiKeys.generate());
        assertThat(ApiKeys.hash(a)).hasSize(64).isEqualTo(ApiKeys.hash(a));
    }
}
