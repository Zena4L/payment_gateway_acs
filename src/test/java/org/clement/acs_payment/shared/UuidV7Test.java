package org.clement.acs_payment.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void producesVersionSevenWithRfcVariant() {
        var id = UuidV7.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void ordersIdsByCreationTime() throws InterruptedException {
        var first = UuidV7.next();
        Thread.sleep(2);
        var second = UuidV7.next();

        assertThat(second.compareTo(first)).isPositive();
    }

    @Test
    void producesDistinctIds() {
        var ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.next());
        }

        assertThat(ids).hasSize(10_000);
    }
}
