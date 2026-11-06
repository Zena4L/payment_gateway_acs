package org.clement.acs_payment.payment.internal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.clement.acs_payment.payment.internal.model.PaymentStatus.EXPIRED;
import static org.clement.acs_payment.payment.internal.model.PaymentStatus.FAILED;
import static org.clement.acs_payment.payment.internal.model.PaymentStatus.INITIALIZING;
import static org.clement.acs_payment.payment.internal.model.PaymentStatus.PENDING;
import static org.clement.acs_payment.payment.internal.model.PaymentStatus.REQUIRES_REVIEW;
import static org.clement.acs_payment.payment.internal.model.PaymentStatus.SUCCESS;
import static org.clement.acs_payment.payment.internal.model.PaymentStatus.UNKNOWN;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            INITIALIZING, EnumSet.of(PENDING, UNKNOWN, FAILED),
            PENDING, EnumSet.of(SUCCESS, FAILED, EXPIRED, REQUIRES_REVIEW),
            UNKNOWN, EnumSet.of(PENDING, SUCCESS, FAILED, EXPIRED, REQUIRES_REVIEW),
            REQUIRES_REVIEW, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            EXPIRED, EnumSet.noneOf(PaymentStatus.class));

    static Stream<Arguments> everyPair() {
        return Stream.of(PaymentStatus.values())
                .flatMap(from -> Stream.of(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void followsTheDocumentedTransitionTable(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canMoveTo(to)).isEqualTo(EXPECTED.get(from).contains(to));
    }

    @Test
    void treatsOnlySuccessFailedAndExpiredAsFinal() {
        assertThat(Stream.of(PaymentStatus.values()).filter(PaymentStatus::isFinal))
                .containsExactlyInAnyOrder(SUCCESS, FAILED, EXPIRED);
    }
}
