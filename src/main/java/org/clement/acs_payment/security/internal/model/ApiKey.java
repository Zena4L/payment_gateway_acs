package org.clement.acs_payment.security.internal.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

public record ApiKey(String keyId, String secret) {

    public static final String FORMAT = "acs_[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}";

    private static final Pattern PARSER = Pattern.compile("acs_([0-9a-f]{12})\\.([A-Za-z0-9_-]{43})");
    private static final SecureRandom RANDOM = new SecureRandom();

    public static ApiKey generate() {
        var keyId = new byte[6];
        var secret = new byte[32];
        RANDOM.nextBytes(keyId);
        RANDOM.nextBytes(secret);
        return new ApiKey(HexFormat.of().formatHex(keyId), Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    public static Optional<ApiKey> parse(String raw) {
        var matcher = PARSER.matcher(raw);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ApiKey(matcher.group(1), matcher.group(2)));
    }

    public String value() {
        return "acs_" + keyId + "." + secret;
    }

    public String hash() {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    @Override
    public String toString() {
        return "ApiKey[keyId=" + keyId + "]";
    }
}
