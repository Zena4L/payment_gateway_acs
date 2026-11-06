package org.clement.acs_payment.webhook.internal.model;

import org.clement.acs_payment.webhook.events.ProcessingOutcome;

public enum ProcessingState {
    RECEIVED, PROCESSED, DUPLICATE, IGNORED, REJECTED, FAILED;

    public static ProcessingState from(ProcessingOutcome outcome) {
        return switch (outcome) {
            case PROCESSED -> PROCESSED;
            case IGNORED -> IGNORED;
            case REJECTED -> REJECTED;
            case FAILED -> FAILED;
        };
    }
}
