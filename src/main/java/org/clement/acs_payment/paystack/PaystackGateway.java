package org.clement.acs_payment.paystack;

public interface PaystackGateway {

    InitializationResult initialize(InitializeTransaction request);
}
