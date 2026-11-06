package org.clement.acs_payment.paystack;

public sealed interface InitializationResult {

    record Initialized(String authorizationUrl, String accessCode) implements InitializationResult {

        @Override
        public String toString() {
            return "Initialized[authorizationUrl=" + authorizationUrl + "]";
        }
    }

    record Rejected(RejectionReason reason, String message) implements InitializationResult {
    }

    record Unavailable() implements InitializationResult {
    }

    record OutcomeUnknown() implements InitializationResult {
    }
}
