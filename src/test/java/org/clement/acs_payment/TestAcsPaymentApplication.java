package org.clement.acs_payment;

import org.springframework.boot.SpringApplication;

public class TestAcsPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.from(AcsPaymentApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
