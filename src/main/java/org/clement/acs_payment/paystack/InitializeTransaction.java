package org.clement.acs_payment.paystack;

import lombok.Builder;

@Builder
public record InitializeTransaction(String reference, String email, long amountMinor, String currency) {

    @Override
    public String toString() {
        return "InitializeTransaction[reference=" + reference + ", amountMinor=" + amountMinor + ", currency=" + currency + "]";
    }
}
