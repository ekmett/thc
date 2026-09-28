// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Native pointers cannot outlive a synchronous call's allocation borrows. */
public final class PackagePointerLease { public volatile boolean open = true; }
