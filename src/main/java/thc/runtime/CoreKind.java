// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Exact runtime kind is independent of evidence that a lifted value is evaluated. */
public enum CoreKind { LONG, FLOAT, DOUBLE, ADDRESS, VOID, DATA, CLOSURE, OBJECT, VECTOR, UNKNOWN }
