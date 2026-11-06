package org.clement.acs_payment.payment.internal.service;

import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@UtilityClass
class OptimisticLocking {

    static <T> T retryOnce(Supplier<T> action) {
        try {
            return action.get();
        } catch (ObjectOptimisticLockingFailureException ex) {
            return action.get();
        }
    }

    static void retryOnce(Runnable action) {
        try {
            action.run();
        } catch (ObjectOptimisticLockingFailureException ex) {
            action.run();
        }
    }
}
