package org.clement.acs_payment.paystack;

public sealed interface VerificationResult {

    record Found(ProviderTransaction transaction) implements VerificationResult {
    }

    record NotFound() implements VerificationResult {
    }

    record Rejected(String message) implements VerificationResult {
    }

    record Unavailable() implements VerificationResult {
    }
}
