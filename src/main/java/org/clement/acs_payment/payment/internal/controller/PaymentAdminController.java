package org.clement.acs_payment.payment.internal.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.dto.ResolveReviewRequest;
import org.clement.acs_payment.payment.internal.dto.TransactionResponse;
import org.clement.acs_payment.payment.internal.dto.TransitionResponse;
import org.clement.acs_payment.payment.internal.service.PaymentReviewService;
import org.clement.acs_payment.security.ApiClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
class PaymentAdminController {

    private final PaymentReviewService reviews;

    @PostMapping("/{transactionId}/resolution")
    TransactionResponse resolve(@PathVariable UUID transactionId,
                                @Valid @RequestBody ResolveReviewRequest request,
                                @AuthenticationPrincipal ApiClient admin) {
        return TransactionResponse.from(reviews.resolve(transactionId, request.outcome(), request.reason(), admin));
    }

    @GetMapping("/{transactionId}/transitions")
    List<TransitionResponse> transitions(@PathVariable UUID transactionId) {
        return reviews.transitions(transactionId).stream().map(TransitionResponse::from).toList();
    }
}
