package org.clement.acs_payment.payment.internal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.clement.acs_payment.payment.internal.exception.InvalidMoneyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency GHS = Currency.getInstance("GHS");

    @Test
    void convertsCedisToPesewas() {
        assertThat(new Money(new BigDecimal("150.00"), GHS).minorUnits()).isEqualTo(15000L);
    }

    @Test
    void convertsWholeAmountToPesewas() {
        assertThat(new Money(new BigDecimal("150"), GHS).minorUnits()).isEqualTo(15000L);
    }

    @Test
    void convertsSmallestUnit() {
        assertThat(new Money(new BigDecimal("0.01"), GHS).minorUnits()).isEqualTo(1L);
    }

    @Test
    void normalisesAmountToCurrencyScale() {
        assertThat(new Money(new BigDecimal("150.5"), GHS).amount()).isEqualByComparingTo("150.50").hasToString("150.50");
    }

    @Test
    void acceptsTrailingZerosBeyondCurrencyScale() {
        assertThat(new Money(new BigDecimal("150.500"), GHS).minorUnits()).isEqualTo(15050L);
    }

    @Test
    void rejectsMoreDecimalPlacesThanCurrencyAllows() {
        assertThatThrownBy(() -> new Money(new BigDecimal("150.005"), GHS))
                .isInstanceOf(InvalidMoneyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-150.00"})
    void rejectsZeroAndNegativeAmounts(String amount) {
        assertThatThrownBy(() -> new Money(new BigDecimal(amount), GHS))
                .isInstanceOf(InvalidMoneyException.class);
    }
}
