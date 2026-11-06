package org.clement.acs_payment.security.internal.service;

import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.clement.acs_payment.security.internal.exception.ApiKeyNotFoundException;
import org.clement.acs_payment.security.internal.model.ApiClientKey;
import org.clement.acs_payment.security.internal.model.ApiKey;
import org.clement.acs_payment.security.internal.repository.ApiClientKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String UNUSED_HASH = "0".repeat(64);

    private final ApiClientKeyRepository keys;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<ApiClient> authenticate(String rawKey) {
        var parsed = ApiKey.parse(rawKey);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        var key = parsed.get();
        var stored = keys.findByKeyId(key.keyId());
        if (stored.isEmpty()) {
            compareAgainstUnusedHashToKeepTimingConstant(key);
            return Optional.empty();
        }
        var clientKey = stored.get();
        if (!clientKey.matches(key) || !clientKey.isActive()) {
            return Optional.empty();
        }
        return Optional.of(clientKey.toClient());
    }

    @Transactional
    public IssuedApiKey issue(String ownerId, Role role) {
        var key = ApiKey.generate();
        keys.save(ApiClientKey.issue(key, ownerId, role, clock));
        return new IssuedApiKey(key, ownerId, role);
    }

    @Transactional
    public void revoke(String keyId) {
        var clientKey = keys.findByKeyId(keyId).orElseThrow(ApiKeyNotFoundException::new);
        clientKey.revoke(clock);
    }

    private static void compareAgainstUnusedHashToKeepTimingConstant(ApiKey key) {
        ApiClientKey.hashesEqual(UNUSED_HASH, key.hash());
    }

    public record IssuedApiKey(ApiKey key, String ownerId, Role role) {
    }
}
