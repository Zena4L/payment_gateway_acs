package org.clement.acs_payment.webhook.internal.controller;

import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.webhook.internal.service.WebhookIngestionService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class PaystackWebhookController {

    static final String SIGNATURE_HEADER = "x-paystack-signature";

    private final WebhookIngestionService ingestion;

    @PostMapping("/api/v1/webhooks/paystack")
    ResponseEntity<Void> receive(@RequestBody byte[] body,
                                 @RequestHeader(name = SIGNATURE_HEADER, required = false) @Nullable String signature) {
        ingestion.receive(body, signature);
        return ResponseEntity.ok().build();
    }
}
