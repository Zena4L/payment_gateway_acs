package org.clement.acs_payment.payment.internal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.dto.InitiatePaymentRequest;
import org.clement.acs_payment.payment.internal.dto.PaymentResponse;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.service.PaymentInitiation;
import org.clement.acs_payment.payment.internal.service.PaymentInitiationService;
import org.clement.acs_payment.payment.internal.service.PaymentVerificationService;
import org.clement.acs_payment.security.ApiClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
class PaymentController {

    private final PaymentInitiationService initiation;
    private final PaymentVerificationService verification;

    @PostMapping("/initiate")
    ResponseEntity<PaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request,
                                             @AuthenticationPrincipal ApiClient client) {
        var result = initiation.initiate(request.toCommand(client.ownerId()));
        return ResponseEntity.status(statusFor(result)).body(PaymentResponse.from(result.payment()));
    }

    @GetMapping("/verify/{reference}")
    PaymentResponse verify(@PathVariable String reference, @AuthenticationPrincipal ApiClient client) {
        return PaymentResponse.from(verification.verify(reference, client));
    }

    private HttpStatus statusFor(PaymentInitiation result) {
        if (result.replayed()) {
            return HttpStatus.OK;
        }
        return result.payment().getStatus() == PaymentStatus.UNKNOWN ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
    }
}
