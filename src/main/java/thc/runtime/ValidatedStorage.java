// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** The superclass argument validates allocation before Object initialization. */
public abstract class ValidatedStorage {
    protected ValidatedStorage(Object checked) {}
}
