package org.clement.acs_payment.payment.internal.service;

import org.clement.acs_payment.payment.internal.model.Payment;

public record PaymentInitiation(Payment payment, boolean replayed) {

    static PaymentInitiation created(Payment payment) {
        return new PaymentInitiation(payment, false);
    }

    static PaymentInitiation replayed(Payment payment) {
        return new PaymentInitiation(payment, true);
    }
}
