// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.graalvm.polyglot.Context;
import thc.runtime.NativeFileProvider;
import thc.runtime.WindowsDirectoryStreams;

/** Explicit host-filesystem authority for an opt-in context. This factory owns
 * the final filesystem configuration and returns a built Context, never a
 * mutable Builder. It cannot authenticate arbitrary filesystem wrappers.
 * POSIX files require selected-ABI resources; Windows supports the original directory API. */
public final class NativeIO {
    private NativeIO() {}
    public enum StandardEndpoint { INPUT, OUTPUT, ERROR }

    public static Context createContext() { return createContext(Set.of()); }
    public static Context createContext(Set<StandardEndpoint> standardEndpoints) {
        return createContext(standardEndpoints, false);
    }
    public static Context createContext(boolean allowProcesses) { return createContext(Set.of(), allowProcesses); }
    public static Context createContext(Set<StandardEndpoint> standardEndpoints, boolean allowProcesses) {
        if (WindowsDirectoryStreams.supportedHost()) {
            if (allowProcesses) throw new IllegalArgumentException("Native subprocesses currently require Linux x86_64");
            return WindowsDirectoryStreams.createContext(ContextProfile.NATIVE, Set.copyOf(standardEndpoints));
        }
        return NativeFileProvider.createContext(new LinkedHashSet<>(standardEndpoints), ContextProfile.NATIVE, allowProcesses);
    }
    public static boolean supportedHost() { return NativeFileProvider.supportedHost() || WindowsDirectoryStreams.supportedHost(); }
    public static boolean supportedPosixHost() {
        if (!"Linux".equals(System.getProperty("os.name"))) return false;
        String architecture = System.getProperty("os.arch");
        return "amd64".equals(architecture) || "x86_64".equals(architecture);
    }
    public static Context commandLineContext() { return commandLineContext(false); }
    static Context commandLineContext(boolean interfaceHelper) {
        if (WindowsDirectoryStreams.supportedHost()) return WindowsDirectoryStreams.createContext(ContextProfile.LAUNCHER,
            new LinkedHashSet<>(Arrays.asList(StandardEndpoint.values())));
        return NativeFileProvider.createContext(new LinkedHashSet<>(Arrays.asList(StandardEndpoint.values())), ContextProfile.LAUNCHER, supportedPosixHost(), interfaceHelper);
    }
}
