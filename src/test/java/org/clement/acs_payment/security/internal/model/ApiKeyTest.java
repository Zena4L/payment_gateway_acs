package org.clement.acs_payment.security.internal.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyTest {

    @Test
    void generatesKeysInTheDocumentedFormat() {
        var key = ApiKey.generate();

        assertThat(key.value()).matches(ApiKey.FORMAT);
    }

    @Test
    void generatesDistinctKeys() {
        assertThat(ApiKey.generate().value()).isNotEqualTo(ApiKey.generate().value());
    }

    @Test
    void parsesWhatItGenerates() {
        var key = ApiKey.generate();

        assertThat(ApiKey.parse(key.value())).contains(key);
    }

    @Test
    void hashesToSha256HexThatDiffersFromTheKey() {
        var key = ApiKey.generate();

        assertThat(key.hash()).matches("[0-9a-f]{64}").isNotEqualTo(key.value()).isEqualTo(key.hash());
    }

    @Test
    void neverPrintsTheSecret() {
        var key = ApiKey.generate();

        assertThat(key.toString()).contains(key.keyId()).doesNotContain(key.secret());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-a-key",
            "acs_554558ae6ffc",
            "acs_554558AE6FFC.2xU4YpXa_1vS_V_r8VpI593HlrfP5d_gdRpxjlf3lj8",
            "xyz_554558ae6ffc.2xU4YpXa_1vS_V_r8VpI593HlrfP5d_gdRpxjlf3lj8",
            "acs_554558ae6ffc.short",
            "acs_554558ae6ffc.2xU4YpXa_1vS_V_r8VpI593HlrfP5d_gdRpxjlf3lj8x"
    })
    void rejectsMalformedKeys(String raw) {
        assertThat(ApiKey.parse(raw)).isEmpty();
    }
}
