// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import java.util.Map;

/** Program linkage and context-bound host entrypoints shared across Core backends. */
public interface ExecutableProgram {
    boolean getAsynchronousExceptions();
    /** Ordinary programs always retain continuations, including speculative single-thread execution. */
    default boolean getCapturesContinuations() { return getAsynchronousExceptions(); }
    default boolean getHasBytecode() { return false; }
    default String bytecodeDump() { throw new UnsupportedOperationException("Program has no bytecode backend"); }
    RootCallTarget hostEntryTarget(int arity);
    default RootCallTarget hostEntryTarget() { return hostEntryTarget(0); }
    Object entryValue(String name);
    /** Lower all admitted local bindings without guest or native initialization. */
    default void prepareCode() {}
    /** Link this demanded program after inert lowering, on its exact context owner. */
    default void initializeNative(thc.Language.State owner) {}
    /** Complete staged ordinary global initialization after native constructors. */
    default void initializeGlobals() {}
    RootCallTarget entryTarget(String name);
    DataLayout constructorLayout(String id);
    /** Per-program counts, without copying context-wide metric snapshots. */
    default Map<String, Object> rootCounts() { return Map.of(); }
    Map<String, Object> diagnostics();
}
