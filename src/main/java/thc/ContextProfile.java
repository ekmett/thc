// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

/** Fixed settings only; IO authority is chosen by the context factory. */
public enum ContextProfile { NATIVE, SYNCHRONOUS_TEST, LAUNCHER }
