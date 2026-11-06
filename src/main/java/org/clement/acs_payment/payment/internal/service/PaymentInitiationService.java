package org.clement.acs_payment.payment.internal.service;

import java.util.Currency;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.config.PaymentProperties;
import org.clement.acs_payment.payment.internal.exception.PaymentConflictException;
import org.clement.acs_payment.payment.internal.exception.ProviderRejectedException;
import org.clement.acs_payment.payment.internal.exception.ProviderUnavailableException;
import org.clement.acs_payment.payment.internal.exception.UnsupportedCurrencyException;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.InitializeTransaction;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentInitiationService {

    private final PaymentRepository payments;
    private final PaymentWriter writer;
    private final PaystackGateway paystack;
    private final PaymentProperties properties;

    public PaymentInitiation initiate(InitiatePaymentCommand command) {
        var money = toMoney(command);
        var existing = payments.findByReference(command.reference());
        if (existing.isPresent()) {
            return replay(existing.get(), command, money);
        }
        Payment created;
        try {
            created = writer.create(command, money);
        } catch (DataIntegrityViolationException ex) {
            var winner = payments.findByReference(command.reference()).orElseThrow(() -> ex);
            return replay(winner, command, money);
        }
        var result = paystack.initialize(InitializeTransaction.builder()
                .reference(created.getReference())
                .email(created.getEmail())
                .amountMinor(created.getAmountMinor())
                .currency(created.getCurrency().getCurrencyCode())
                .build());
        var updated = writer.applyInitialization(created.getId(), result);
        return switch (result) {
            case InitializationResult.Rejected rejected when !rejected.alreadyKnownToProvider() ->
                    throw new ProviderRejectedException();
            case InitializationResult.Unavailable() -> throw new ProviderUnavailableException();
            default -> PaymentInitiation.created(updated);
        };
    }

    private Money toMoney(InitiatePaymentCommand command) {
        if (!properties.supportedCurrencies().contains(command.currency())) {
            throw new UnsupportedCurrencyException(command.currency());
        }
        return new Money(command.amount(), Currency.getInstance(command.currency()));
    }

    private PaymentInitiation replay(Payment payment, InitiatePaymentCommand command, Money money) {
        if (!payment.matches(command.ownerId(), command.email(), money)) {
            throw new PaymentConflictException();
        }
        return PaymentInitiation.replayed(payment);
    }
}
