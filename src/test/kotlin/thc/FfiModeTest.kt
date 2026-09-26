// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FfiModeTest {
    @Test fun configurationUsesPropertyThenEnvironmentThenNative() {
        assertEquals(FfiMode.NATIVE, FfiMode.configured(null, null))
        assertEquals(FfiMode.MANAGED, FfiMode.configured(null, "managed"))
        assertEquals(FfiMode.NATIVE, FfiMode.configured("native", "managed"))
        assertEquals(FfiMode.MANAGED, FfiMode.configured("managed", "invalid"))
        for (invalid in listOf("", "MANAGED", "hybrid", "native,managed")) {
            assertTrue(assertThrows(FfiConfigurationException::class.java) {
                FfiMode.configured(invalid, "native")
            }.message!!.contains("thc.ffiMode"))
            assertTrue(assertThrows(FfiConfigurationException::class.java) {
                FfiMode.configured(null, invalid)
            }.message!!.contains("THC_FFI_MODE"))
        }
    }

    private fun unavailable(): String = Engine.newBuilder().useSystemProperties(false).build().use { engine ->
        if (engine.languages["llvm"]?.options?.get("llvm.managed") == null) "does not expose llvm.managed"
        else "native file/GMP providers and packaged bitcode"
    }

    @Test fun managedSelectionFailsBeforeReadingTheEntryOrInitializingNativeProviders() {
        val reason = unavailable()
        for (option in listOf(listOf("--ffi", "managed"), listOf("--ffi=managed"))) {
            for (entry in listOf(listOf("--run-io", "missing.json", "main"),
                listOf("--run-executable", "missing.json", "main", "shutdown"),
                listOf("missing.json", "entry", "0"))) {
                val failure = assertThrows(FfiConfigurationException::class.java) {
                    launch((option + entry).toTypedArray())
                }
                assertTrue(failure.message!!.contains("--ffi managed is unavailable"))
                assertTrue(failure.message!!.contains(reason), failure.message)
            }
        }
    }

    @Test fun embeddedLauncherFactoryHonorsManagedDefaultsBeforeBothContextPaths() {
        val old = System.getProperty("thc.ffiMode")
        try {
            System.setProperty("thc.ffiMode", "managed")
            for (io in listOf(false, true)) {
                assertTrue(assertThrows(FfiConfigurationException::class.java) {
                    executionContext(fileIO = io).close()
                }.message!!.contains(unavailable()))
            }
        } finally {
            if (old == null) System.clearProperty("thc.ffiMode") else System.setProperty("thc.ffiMode", old)
        }
    }

    @Test fun malformedLauncherModeNeverFallsThroughToAFileName() {
        for (option in listOf(arrayOf("--ffi"), arrayOf("--ffi="), arrayOf("--ffi", "hybrid"),
            arrayOf("--ffi=MANAGED"))) {
            assertTrue(assertThrows(FfiConfigurationException::class.java) { launch(option) }
                .message!!.startsWith("--ffi"))
        }
    }
}
