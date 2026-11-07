package org.clement.acs_payment.payment.internal.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.dto.InitiatePaymentRequest;
import org.clement.acs_payment.payment.internal.dto.PageResponse;
import org.clement.acs_payment.payment.internal.dto.PaymentResponse;
import org.clement.acs_payment.payment.internal.dto.TransactionResponse;
import org.clement.acs_payment.payment.internal.model.Actor;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.service.PaymentInitiation;
import org.clement.acs_payment.payment.internal.service.PaymentInitiationService;
import org.clement.acs_payment.payment.internal.service.PaymentQuery;
import org.clement.acs_payment.payment.internal.service.PaymentQueryService;
import org.clement.acs_payment.payment.internal.service.PaymentSortField;
import org.clement.acs_payment.payment.internal.service.SortDirection;
import org.clement.acs_payment.payment.internal.service.PaymentVerificationService;
import org.clement.acs_payment.security.ApiClient;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
class PaymentController {

    private final PaymentInitiationService initiation;
    private final PaymentVerificationService verification;
    private final PaymentQueryService queries;

    @PostMapping("/initiate")
    ResponseEntity<PaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request,
                                             @AuthenticationPrincipal ApiClient client) {
        var result = initiation.initiate(request.toCommand(new Actor(client.ownerId(), client.keyId())));
        return ResponseEntity.status(statusFor(result)).body(PaymentResponse.from(result.payment()));
    }

    @GetMapping("/verify/{reference}")
    PaymentResponse verify(@PathVariable String reference, @AuthenticationPrincipal ApiClient client) {
        return PaymentResponse.from(verification.verify(reference, client));
    }

    @GetMapping
    PageResponse<TransactionResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") PaymentSortField sort,
            @RequestParam(defaultValue = "desc") SortDirection direction,
            @RequestParam(required = false) @Nullable PaymentStatus status,
            @RequestParam(required = false) @Nullable String reference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant createdTo,
            @AuthenticationPrincipal ApiClient client) {
        var query = PaymentQuery.builder()
                .page(page)
                .size(size)
                .sort(sort)
                .direction(direction)
                .status(status)
                .reference(reference)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .build();
        return PageResponse.from(queries.list(query, client), TransactionResponse::from, sort.name(), direction.name());
    }

    @GetMapping("/{transactionId}")
    TransactionResponse get(@PathVariable UUID transactionId, @AuthenticationPrincipal ApiClient client) {
        return TransactionResponse.from(queries.get(transactionId, client));
    }

    private HttpStatus statusFor(PaymentInitiation result) {
        if (result.replayed()) {
            return HttpStatus.OK;
        }
        return result.payment().getStatus() == PaymentStatus.UNKNOWN ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
    }
}
