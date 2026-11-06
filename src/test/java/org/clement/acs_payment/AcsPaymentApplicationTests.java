package org.clement.acs_payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "acs.paystack.secret-key=sk_test_dummy")
class AcsPaymentApplicationTests {

    @Test
    void contextLoads() {
    }

}
