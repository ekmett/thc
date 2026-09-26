// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine

internal class FfiConfigurationException(message: String) : IllegalArgumentException(message)

/** Launcher policy, independent of Core compilation and of native allocation storage. */
internal enum class FfiMode {
    NATIVE, MANAGED;

    companion object {
        fun parse(value: String, source: String): FfiMode = when (value) {
            "native" -> NATIVE
            "managed" -> MANAGED
            else -> throw FfiConfigurationException("$source must be native or managed; got '$value'")
        }

        fun configured(property: String? = System.getProperty("thc.ffiMode"),
                       environment: String? = System.getenv("THC_FFI_MODE")): FfiMode =
            property?.let { parse(it, "thc.ffiMode") }
            ?: environment?.let { parse(it, "THC_FFI_MODE") }
            ?: NATIVE
    }
}

// Retain only option metadata, never an Engine or any context-owned native state.
// A separate option probe ignores application properties so a requested managed
// option cannot fail inside Graal before we can explain the missing capability.
private val llvmModeOptions by lazy {
    Engine.newBuilder().useSystemProperties(false).build().use { engine ->
        engine.languages["llvm"]?.let { language ->
            language.version to (language.options.get("llvm.managed") != null)
        }
    }
}

internal fun Context.Builder.withFfiMode(mode: FfiMode): Context.Builder {
    val llvm = llvmModeOptions
    if (mode == FfiMode.MANAGED) {
        if (llvm == null)
            throw FfiConfigurationException("--ffi managed is unavailable: the LLVM language is not installed")
        if (!llvm.second)
            throw FfiConfigurationException("--ffi managed is unavailable: the installed LLVM ${llvm.first} " +
                "does not expose llvm.managed; THC's llvm-community distribution provides native mode only")
        throw FfiConfigurationException("--ffi managed is unavailable: LLVM exposes llvm.managed, but THC's " +
            "native file/GMP providers and packaged bitcode have not been ported and verified for managed execution")
    }
    // An explicit THC native policy must not inherit managed=true on an engine
    // that supports both modes. CE rejects this option even when its value is false.
    if (llvm?.second == true) option("llvm.managed", "false")
    return this
}
