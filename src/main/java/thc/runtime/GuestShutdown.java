// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** First exit wins; host resources close in both modes, while fast mode skips guest hooks. */
public record GuestShutdown(int status, boolean fast) {
    public int getStatus() { return status; }
    public boolean getFast() { return fast; }
}
