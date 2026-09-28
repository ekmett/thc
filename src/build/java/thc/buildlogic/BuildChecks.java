// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.util.function.Supplier;

/** Build guards retain their original exception categories and lazy messages. */
public final class BuildChecks {
    private BuildChecks() {}
    public static void checkBuild(boolean condition) {
        checkBuild(condition, () -> "Check failed.");
    }
    public static void checkBuild(boolean condition, Supplier<?> message) {
        if (!condition) throw new IllegalStateException(String.valueOf(message.get()));
    }
    public static void requireBuild(boolean condition, Supplier<?> message) {
        if (!condition) throw new IllegalArgumentException(String.valueOf(message.get()));
    }
    public static <T> T checkNotNull(T value, Supplier<?> message) {
        if (value == null) throw new IllegalStateException(String.valueOf(message.get()));
        return value;
    }
}
