package org.clement.acs_payment.paystack;

import org.jspecify.annotations.Nullable;

public interface PaystackWebhooks {

    boolean hasValidSignature(byte[] body, @Nullable String signature);

    WebhookPayload parse(byte[] body);
}
