package org.clement.acs_payment.shared.internal.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/test/errors")
public class ErrorScenariosController {

    @GetMapping("/api-exception")
    void apiException() {
        throw new ApiException(HttpStatus.CONFLICT, "payment-conflict", "Reference already used with different details");
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("boom: jdbc:postgresql://secret-host/db");
    }

    @GetMapping("/logged")
    String logged() {
        log.info("handling logged request");
        return "ok";
    }

    @PostMapping("/validated")
    void validated(@Valid @RequestBody SampleRequest request) {
    }

    record SampleRequest(@NotBlank @Email String email, @Positive int amount) {
    }
}
