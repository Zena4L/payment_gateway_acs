package org.clement.acs_payment.security.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.clement.acs_payment.security.Role;

public record IssueApiKeyRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String ownerId,
        @NotNull Role role) {
}
