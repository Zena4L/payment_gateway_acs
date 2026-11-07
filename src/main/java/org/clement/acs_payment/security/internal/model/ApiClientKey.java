package org.clement.acs_payment.security.internal.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.clement.acs_payment.shared.UuidV7;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "api_client_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class ApiClientKey {

    @Id
    private UUID id;

    private String keyId;

    private String keyHash;

    private String ownerId;

    @Enumerated(EnumType.STRING)
    private Role role;

    private Instant createdAt;

    private @Nullable Instant revokedAt;

    public static ApiClientKey issue(ApiKey key, String ownerId, Role role, Clock clock) {
        return ApiClientKey.builder()
                .id(UuidV7.next())
                .keyId(key.keyId())
                .keyHash(key.hash())
                .ownerId(ownerId)
                .role(role)
                .createdAt(clock.instant())
                .build();
    }

    public boolean matches(ApiKey key) {
        return hashesEqual(keyHash, key.hash());
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke(Clock clock) {
        if (revokedAt == null) {
            revokedAt = clock.instant();
        }
    }

    public ApiClient toClient() {
        return new ApiClient(ownerId, keyId, role);
    }

    public static boolean hashesEqual(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
