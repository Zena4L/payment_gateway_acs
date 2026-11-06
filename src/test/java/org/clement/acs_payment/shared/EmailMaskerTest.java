package org.clement.acs_payment.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailMaskerTest {

    @Test
    void keepsFirstCharacterAndDomain() {
        assertThat(EmailMasker.mask("jane.doe@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    void hidesValueWithoutLocalPart() {
        assertThat(EmailMasker.mask("@example.com")).isEqualTo("***");
    }

    @Test
    void hidesValueThatIsNotAnEmail() {
        assertThat(EmailMasker.mask("not-an-email")).isEqualTo("***");
    }
}
