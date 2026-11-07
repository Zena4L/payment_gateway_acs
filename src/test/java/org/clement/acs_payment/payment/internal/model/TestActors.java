package org.clement.acs_payment.payment.internal.model;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TestActors {

    public static final String KEY_ID = "a1b2c3d4e5f6";

    public static Actor of(String ownerId) {
        return new Actor(ownerId, KEY_ID);
    }

    public static Actor initiatorOf(Payment payment) {
        return of(payment.getOwnerId());
    }
}
