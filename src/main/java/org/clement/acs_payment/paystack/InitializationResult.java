package org.clement.acs_payment.paystack;

public sealed interface InitializationResult {

    record Initialized(String authorizationUrl) implements InitializationResult {
    }

    record Rejected(RejectionReason reason, String message) implements InitializationResult {

        public boolean alreadyKnownToProvider() {
            return reason == RejectionReason.DUPLICATE_REFERENCE;
        }
    }

    record Unavailable() implements InitializationResult {
    }

    record OutcomeUnknown() implements InitializationResult {
    }
}
