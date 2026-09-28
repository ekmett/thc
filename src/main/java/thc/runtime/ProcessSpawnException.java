// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;

public final class ProcessSpawnException extends IOException {
    private final int errno;
    private final ProcessFailureStage stage;
    public ProcessSpawnException(int errno, ProcessFailureStage stage) {
        super(stage.getOperation() + " failed with errno " + errno);
        this.errno = errno;
        this.stage = stage;
    }
    public int getErrno() { return errno; }
    public ProcessFailureStage getStage() { return stage; }
}
