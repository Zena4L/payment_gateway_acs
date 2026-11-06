package org.clement.acs_payment.shared;

import lombok.experimental.UtilityClass;

@UtilityClass
public class EmailMasker {

    private static final String HIDDEN = "***";

    public static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return HIDDEN;
        }
        return email.charAt(0) + HIDDEN + email.substring(at);
    }
}
