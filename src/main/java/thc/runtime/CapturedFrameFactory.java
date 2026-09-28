// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Matches the generated capture storage superclass constructor exactly. */
public interface CapturedFrameFactory {
    CapturedFrame create(CaptureLayout layout, Object allocationKey);
}
