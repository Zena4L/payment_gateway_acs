package org.clement.acs_payment.shared;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;
import lombok.experimental.UtilityClass;

@UtilityClass
public class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static UUID next() {
        var random = new byte[10];
        RANDOM.nextBytes(random);
        long mostSignificant = (System.currentTimeMillis() << 16)
                | 0x7000L
                | ((random[0] & 0x0FL) << 8)
                | (random[1] & 0xFFL);
        long leastSignificant = 0x8000000000000000L
                | (ByteBuffer.wrap(random, 2, 8).getLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(mostSignificant, leastSignificant);
    }
}
