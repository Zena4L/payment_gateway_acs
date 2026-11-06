package org.clement.acs_payment.payment.internal.service;

import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.webhook.events.PaystackChargeReceived;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@RequiredArgsConstructor
class WebhookChargeListener {

    private final WebhookChargeProcessor processor;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(PaystackChargeReceived event) {
        processor.process(event);
    }
}
