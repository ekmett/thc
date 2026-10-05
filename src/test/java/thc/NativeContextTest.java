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

    @Test void launcherCollectsMetricsOnlyWhenDiagnosticsAreRequested() {
        var previous = System.getProperty("thc.diagnostics");
        try {
            System.clearProperty("thc.diagnostics");
            assertFalse(Main.launcherDiagnostics());
            System.setProperty("thc.diagnostics", "true");
            assertTrue(Main.launcherDiagnostics());
        } finally {
            if (previous == null) System.clearProperty("thc.diagnostics");
            else System.setProperty("thc.diagnostics", previous);
        }
    }

    @Test void explicitPolyglotPropertiesOverrideLauncherCompilerDefaults() {
        for (var option : new String[] {"engine.SingleTierCompilationThreshold",
                "compiler.CompilationTimeout", "compiler.MaximumGraalGraphSize"}) {
            var key = "polyglot." + option;
            var previous = System.getProperty(key);
            try {
                System.setProperty(key, "not-a-number");
                var failure = assertThrows(IllegalArgumentException.class, () -> {
                    try (var context = Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.LAUNCHER).build()) {}
                }, option + " must reach the real launcher builder");
                assertTrue(failure.getMessage().contains("not-a-number"), failure.getMessage());
            } finally {
                if (previous == null) System.clearProperty(key);
                else System.setProperty(key, previous);
            }
        }
    }
}
