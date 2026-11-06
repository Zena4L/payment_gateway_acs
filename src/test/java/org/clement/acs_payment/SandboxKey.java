package org.clement.acs_payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SandboxKey {

    public static final String PAYSTACK_BASE_URL = "https://api.paystack.co";

    private static final String VARIABLE = "ACS_PAYSTACK_SECRET_KEY";
    private static final String TEST_KEY_PREFIX = "sk_test_";

    public static String secretKey() {
        var key = System.getenv(VARIABLE);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Sandbox tests need " + VARIABLE + " set to a Paystack test key (sk_test_...). "
                    + "Run: set -a; source .env; set +a; ./mvnw -Psandbox verify");
        }
        if (!key.startsWith(TEST_KEY_PREFIX)) {
            throw new IllegalStateException(VARIABLE + " must be a Paystack test key (sk_test_...). Live keys are refused.");
        }
        return key;
    }

    public static String sign(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA512 is not available", ex);
        }
    }
}
