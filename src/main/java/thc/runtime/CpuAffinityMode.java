// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Support is not a promise that the OS accepts a particular request. */
public enum CpuAffinityMode { UNAVAILABLE, ADVISORY, PINNED }
