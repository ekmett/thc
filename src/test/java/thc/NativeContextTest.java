// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NativeContextTest {
    @Test void nativeProfilesRejectInheritedManagedExecutionBeforeContextConstruction() {
        var previous = System.getProperty("polyglot.llvm.managed");
        try {
            for (var setting : new String[] {"true", "TRUE", "invalid", ""}) {
                System.setProperty("polyglot.llvm.managed", setting);
                for (var profile : ContextProfile.values())
                    assertThrows(IllegalArgumentException.class,
                        () -> Main.withContextProfile(Context.newBuilder("thc"), profile));
            }
            System.setProperty("polyglot.llvm.managed", "false");
            for (var profile : ContextProfile.values())
                assertDoesNotThrow(() -> Main.withContextProfile(Context.newBuilder("thc"), profile));
        } finally {
            if (previous == null) System.clearProperty("polyglot.llvm.managed");
            else System.setProperty("polyglot.llvm.managed", previous);
        }
    }

    @Test void explicitPolyglotPropertiesOverrideLauncherCompilerDefaults() {
        var key = "polyglot.compiler.MaximumGraalGraphSize";
        var previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertEquals("100000", Main.launcherCompilerOptions().get("compiler.MaximumGraalGraphSize"));
            System.setProperty(key, "400000");
            assertEquals("400000", Main.launcherCompilerOptions().get("compiler.MaximumGraalGraphSize"));
            assertEquals("30", Main.launcherCompilerOptions().get("compiler.CompilationTimeout"));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}
