package org.clement.acs_payment.security.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import org.clement.acs_payment.security.Role;
import org.clement.acs_payment.security.internal.config.SecurityProperties;
import org.clement.acs_payment.security.internal.model.ApiClientKey;
import org.clement.acs_payment.security.internal.model.ApiKey;
import org.clement.acs_payment.security.internal.repository.ApiClientKeyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

class BootstrapAdminKeyTest {

    private final ApiClientKeyRepository keys = mock(ApiClientKeyRepository.class);
    private final ApiKey configured = ApiKey.generate();

    @Test
    void provisionsAdminKeyWhenAbsent() {
        when(keys.findByKeyId(configured.keyId())).thenReturn(Optional.empty());

        runner(configured.value()).run(new DefaultApplicationArguments());

        var saved = ArgumentCaptor.forClass(ApiClientKey.class);
        verify(keys).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getOwnerId()).isEqualTo(BootstrapAdminKey.OWNER_ID);
        assertThat(saved.getValue().matches(configured)).isTrue();
    }

    @Test
    void leavesExistingKeyWithDifferentSecretUntouched() {
        var other = ApiClientKey.issue(ApiKey.generate(), "someone", Role.CLIENT, Clock.systemUTC());
        when(keys.findByKeyId(configured.keyId())).thenReturn(Optional.of(other));

        runner(configured.value()).run(new DefaultApplicationArguments());

        verify(keys, never()).save(any());
    }

    @Test
    void toleratesAnotherInstanceProvisioningFirst() {
        when(keys.findByKeyId(configured.keyId())).thenReturn(Optional.empty());
        when(keys.save(any())).thenThrow(new DataIntegrityViolationException("uk_api_client_key_key_id"));

        runner(configured.value()).run(new DefaultApplicationArguments());

        verify(keys).save(any());
    }

    @Test
    void doesNothingWhenNotConfigured() {
        runner("").run(new DefaultApplicationArguments());

        verify(keys, never()).findByKeyId(any());
    }

    private BootstrapAdminKey runner(String key) {
        return new BootstrapAdminKey(new SecurityProperties(key), keys, Clock.systemUTC());
    }
}
