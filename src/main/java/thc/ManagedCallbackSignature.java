// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

/** The generated helper association is distinct from an ordinary native function address. */
public record ManagedCallbackSignature(ManagedExportSignature signature, String typeString) {}
