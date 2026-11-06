package org.clement.acs_payment.paystack.internal.dto;

import org.clement.acs_payment.paystack.InitializeTransaction;

public record InitializeRequestBody(String email, long amount, String currency, String reference) {

    public static InitializeRequestBody from(InitializeTransaction transaction) {
        return new InitializeRequestBody(transaction.email(), transaction.amountMinor(), transaction.currency(), transaction.reference());
    }
}
