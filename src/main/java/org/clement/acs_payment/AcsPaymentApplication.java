package org.clement.acs_payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AcsPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcsPaymentApplication.class, args);
    }

}
