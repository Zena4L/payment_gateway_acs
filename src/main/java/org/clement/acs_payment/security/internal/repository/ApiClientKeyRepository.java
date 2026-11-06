package org.clement.acs_payment.security.internal.repository;

import java.util.Optional;
import java.util.UUID;
import org.clement.acs_payment.security.internal.model.ApiClientKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientKeyRepository extends JpaRepository<ApiClientKey, UUID> {

    Optional<ApiClientKey> findByKeyId(String keyId);
}
