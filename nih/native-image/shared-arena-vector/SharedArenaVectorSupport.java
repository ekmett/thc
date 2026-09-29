/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */
package com.oracle.svm.core.foreign;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.oracle.svm.core.jdk.VectorAPIEnabled;

/** Hosted capability check tied to the actual loaded substitution's source bytes. */
public final class SharedArenaVectorSupport {
    public static boolean verifyInstalled() {
        return matches("com.oracle.svm.core.foreign.Target_jdk_internal_misc_ScopedMemoryAccess",
                "THC_SHARED_ARENA_VECTOR_DIGEST") &&
                matches("com.oracle.svm.hosted.foreign.ForeignFunctionsFeature$SharedArenaSupportImpl",
                "THC_SHARED_ARENA_PHASE_DIGEST");
    }

    public static boolean unsupportedCombination() {
        return VectorAPIEnabled.getValue() && !verifyInstalled();
    }

    private static boolean matches(String className, String expected) {
        try {
            Class<?> target = Class.forName(className, false,
                    SharedArenaVectorSupport.class.getClassLoader());
            if (target.getModule() != SharedArenaVectorSupport.class.getModule()) return false;
            URL origin = target.getProtectionDomain().getCodeSource().getLocation();
            // Native Image's guest module-resource reader returns the original
            // JAR, ignoring --patch-module. Verify the loaded class's code origin,
            // not that unrelated resource view.
            String name = className.replace('.', '/') + ".class";
            URL bytes = origin.getPath().endsWith(".jar")
                    ? URI.create("jar:" + origin.toExternalForm() + "!/" + name).toURL()
                    : new URL(origin, name);
            try (var input = bytes.openStream()) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                boolean installed = actual.equals(expected);
                if (!installed) System.err.println("Shared vector provider class digest mismatch: " + className + " " + actual);
                return installed;
            }
        } catch (IOException | NoSuchAlgorithmException | ClassNotFoundException | SecurityException failure) {
            System.err.println("Shared vector provider verification failed: " + failure);
            return false;
        }
    }
}
