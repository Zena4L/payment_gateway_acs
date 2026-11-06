package org.clement.acs_payment.security.internal.service;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.security.Role;
import org.clement.acs_payment.security.internal.config.SecurityProperties;
import org.clement.acs_payment.security.internal.model.ApiClientKey;
import org.clement.acs_payment.security.internal.model.ApiKey;
import org.clement.acs_payment.security.internal.repository.ApiClientKeyRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapAdminKey implements ApplicationRunner {

    public static final String OWNER_ID = "bootstrap-admin";

    private final SecurityProperties properties;
    private final ApiClientKeyRepository keys;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        var configured = properties.bootstrapAdminKey();
        if (configured == null || configured.isBlank()) {
            return;
        }
        var key = ApiKey.parse(configured).orElseThrow();
        keys.findByKeyId(key.keyId()).ifPresentOrElse(
                existing -> warnIfDifferent(existing, key),
                () -> provision(key));
    }

    private void warnIfDifferent(ApiClientKey existing, ApiKey key) {
        if (!existing.matches(key)) {
            log.warn("Bootstrap admin key id {} already exists with a different secret; leaving it unchanged", key.keyId());
        }
    }

    private void provision(ApiKey key) {
        try {
            keys.save(ApiClientKey.issue(key, OWNER_ID, Role.ADMIN, clock));
            log.info("Provisioned bootstrap admin key id {}", key.keyId());
        } catch (DataIntegrityViolationException ex) {
            log.info("Bootstrap admin key id {} was provisioned by another instance", key.keyId());
        }
    }
}
