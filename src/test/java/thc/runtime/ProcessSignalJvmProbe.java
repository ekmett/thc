// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.*;
import java.util.Arrays;

/** Separate process: the Gradle test worker must never replace its host handlers. */
public final class ProcessSignalJvmProbe {
    private ProcessSignalJvmProbe() {}
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException("Check failed."); }
    public static void main(String[] args) throws Throwable {
        if (Arrays.equals(args, new String[] {"reduced-signals-disabled"})) {
            check(!ManagedSignals.hasReducedVmSignals()); System.out.println("Later VM option disables reduced signal usage"); return;
        }
        check(ManagedSignals.hasReducedVmSignals());
        if (Arrays.equals(args, new String[] {"unrelocated"})) {
            check(!NativeSignalTransport.userSignalAvailable());
            // Changing the environment after startup cannot release HotSpot's USR2 disposition.
            // This child owns only its environment mutation; no signal handler is changed.
            try (var arena = Arena.ofConfined()) {
                var linker = Linker.nativeLinker(); var setenv = linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                check((int) setenv.invokeExact(arena.allocateFrom("_JAVA_SR_SIGNUM"), arena.allocateFrom("64"), 1) == 0);
            }
            check(!NativeSignalTransport.userSignalAvailable());
            try (var transport = new NativeSignalTransport()) {
                Throwable failure = null; try { transport.install(12, -4); } catch (Throwable observed) { failure = observed; }
                check(failure instanceof RuntimeFault && failure.getMessage().contains("_JAVA_SR_SIGNUM=64"));
            }
            System.out.println("Unrelocated JVM retains SIGUSR2"); return;
        }
        check(NativeSignalTransport.userSignalAvailable());
        var raise = Linker.nativeLinker().downcallHandle(Linker.nativeLinker().defaultLookup().find("raise").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        try (var transport = new NativeSignalTransport()) {
            for (int signal : new int[] {1, 2, 3, 10, 12, 15, 24, 25}) {
                check(transport.install(signal, -4).action() == -1); check((int) raise.invokeExact(signal) == 0);
                var event = java.util.Objects.requireNonNull(transport.take()); check(event.signal() == signal && event.info().length > 0);
            }
        }
        check(NativeSignalTransport.userSignalAvailable()); // USR2 restored; HotSpot's reserved handler survives.
        System.out.println("JVM received signals 1,2,3,10,12,15,24,25");
    }
}
