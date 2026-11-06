package org.clement.acs_payment.security.internal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.security.internal.dto.IssueApiKeyRequest;
import org.clement.acs_payment.security.internal.dto.IssuedApiKeyResponse;
import org.clement.acs_payment.security.internal.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/api-keys")
@RequiredArgsConstructor
class ApiKeyAdminController {

    private final ApiKeyService apiKeys;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IssuedApiKeyResponse issue(@Valid @RequestBody IssueApiKeyRequest request) {
        return IssuedApiKeyResponse.from(apiKeys.issue(request.ownerId(), request.role()));
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable String keyId) {
        apiKeys.revoke(keyId);
    }
}
