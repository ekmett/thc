// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Zero errno preserves the sticky guest error. Null exitCode leaves the output cell untouched. */
public record ProcessResult(int status, Integer exitCode, int errno) {
    public int getStatus() { return status; }
    public Integer getExitCode() { return exitCode; }
    public int getErrno() { return errno; }
}
