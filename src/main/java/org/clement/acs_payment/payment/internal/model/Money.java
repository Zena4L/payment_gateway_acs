package org.clement.acs_payment.payment.internal.model;

import java.math.BigDecimal;
import java.util.Currency;
import org.clement.acs_payment.payment.internal.exception.InvalidMoneyException;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        if (amount.signum() <= 0) {
            throw new InvalidMoneyException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new InvalidMoneyException("Amount has more decimal places than " + currency.getCurrencyCode() + " allows");
        }
        amount = amount.setScale(currency.getDefaultFractionDigits());
    }

    public long minorUnits() {
        return amount.movePointRight(currency.getDefaultFractionDigits()).longValueExact();
    }
}
