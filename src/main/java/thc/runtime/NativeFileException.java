// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
import java.util.Objects;

/** Captured host errno, not a guessed managed error category. */
public final class NativeFileException extends IOException {
    private final int errno;
    public NativeFileException(String operation, int errno) {
        super("Native file " + Objects.requireNonNull(operation) + " failed (errno " + errno + ")");
        if (errno <= 0) throw new IllegalArgumentException("Invalid native errno");
        this.errno = errno;
    }
    public int getErrno() { return errno; }
}
