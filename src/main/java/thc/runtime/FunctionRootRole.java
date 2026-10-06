// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
/** A side-exit body is a call boundary, not a new owner of an inherited tail cycle. */
public enum FunctionRootRole { FUNCTION, PASS_THROUGH, INITIALIZER }
