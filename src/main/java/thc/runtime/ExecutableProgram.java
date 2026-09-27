// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import java.util.Map;

/** Program linkage and context-bound host entrypoints shared across Core backends. */
public interface ExecutableProgram {
    boolean getAsynchronousExceptions();
    default boolean getHasBytecode() { return false; }
    default String bytecodeDump() { throw new UnsupportedOperationException("Program has no bytecode backend"); }
    RootCallTarget hostEntryTarget(int arity);
    default RootCallTarget hostEntryTarget() { return hostEntryTarget(0); }
    Object entryValue(String name);
    RootCallTarget entryTarget(String name);
    DataLayout constructorLayout(String id);
    Map<String, Object> diagnostics();
}
