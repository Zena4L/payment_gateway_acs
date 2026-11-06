package org.clement.acs_payment.payment.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.clement.acs_payment.payment.internal.service.ResolutionOutcome;

public record ResolveReviewRequest(
        @NotNull ResolutionOutcome outcome,
        @NotBlank @Size(max = 255) String reason) {
}
