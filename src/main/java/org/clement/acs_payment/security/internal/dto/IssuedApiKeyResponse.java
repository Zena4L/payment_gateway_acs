package org.clement.acs_payment.security.internal.dto;

import lombok.Builder;
import org.clement.acs_payment.security.Role;
import org.clement.acs_payment.security.internal.service.ApiKeyService;

@Builder
public record IssuedApiKeyResponse(String keyId, String apiKey, String ownerId, Role role) {

    public static IssuedApiKeyResponse from(ApiKeyService.IssuedApiKey issued) {
        return IssuedApiKeyResponse.builder()
                .keyId(issued.key().keyId())
                .apiKey(issued.key().value())
                .ownerId(issued.ownerId())
                .role(issued.role())
                .build();
    }
}
