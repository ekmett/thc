// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FfiModeTest {
    @Test void configurationUsesPropertyThenEnvironmentThenNative() {
        assertEquals(FfiMode.NATIVE, FfiMode.configured(null, null));
        assertEquals(FfiMode.MANAGED, FfiMode.configured(null, "managed"));
        assertEquals(FfiMode.NATIVE, FfiMode.configured("native", "managed"));
        assertEquals(FfiMode.MANAGED, FfiMode.configured("managed", "invalid"));
        for (String invalid : List.of("", "MANAGED", "hybrid", "native,managed")) {
            assertTrue(assertThrows(FfiConfigurationException.class,
                () -> FfiMode.configured(invalid, "native")).getMessage().contains("thc.ffiMode"));
            assertTrue(assertThrows(FfiConfigurationException.class,
                () -> FfiMode.configured(null, invalid)).getMessage().contains("THC_FFI_MODE"));
        }
    }
    private String unavailable() {
        try (var engine = Engine.newBuilder().useSystemProperties(false).build()) {
            var llvm = engine.getLanguages().get("llvm");
            return llvm == null || llvm.getOptions().get("llvm.managed") == null
                ? "does not expose llvm.managed" : "native file/GMP providers and packaged bitcode";
        }
    }
    @Test void managedSelectionFailsBeforeReadingTheEntryOrInitializingNativeProviders() {
        var reason = unavailable();
        for (var option : List.of(List.of("--ffi", "managed"), List.of("--ffi=managed"))) {
            for (var entry : List.of(List.of("--run-io", "missing.json", "main"),
                List.of("--run-executable", "missing.json", "main", "shutdown"), List.of("missing.json", "entry", "0"))) {
                var arguments = new ArrayList<>(option);
                arguments.addAll(entry);
                var failure = assertThrows(FfiConfigurationException.class, () -> Main.launch(arguments.toArray(String[]::new)));
                assertTrue(failure.getMessage().contains("--ffi managed is unavailable"));
                assertTrue(failure.getMessage().contains(reason), failure.getMessage());
            }
        }
    }
    @Test void embeddedLauncherFactoryHonorsManagedDefaultsBeforeBothContextPaths() {
        var old = System.getProperty("thc.ffiMode");
        try {
            System.setProperty("thc.ffiMode", "managed");
            for (boolean io : new boolean[]{false, true}) {
                assertTrue(assertThrows(FfiConfigurationException.class,
                    () -> Main.executionContext(io).close()).getMessage().contains(unavailable()));
            }
        } finally {
            if (old == null) System.clearProperty("thc.ffiMode"); else System.setProperty("thc.ffiMode", old);
        }
    }
    @Test void malformedLauncherModeNeverFallsThroughToAFileName() {
        for (var option : List.of(new String[]{"--ffi"}, new String[]{"--ffi="},
            new String[]{"--ffi", "hybrid"}, new String[]{"--ffi=MANAGED"})) {
            assertTrue(assertThrows(FfiConfigurationException.class, () -> Main.launch(option)).getMessage().startsWith("--ffi"));
        }
    }
}
