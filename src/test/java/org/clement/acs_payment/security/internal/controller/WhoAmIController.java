package org.clement.acs_payment.security.internal.controller;

import org.clement.acs_payment.security.ApiClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WhoAmIController {

    @GetMapping("/test/whoami")
    ApiClient whoami(@AuthenticationPrincipal ApiClient client) {
        return client;
    }
}
