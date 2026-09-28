// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

/** Launcher policy, independent of Core compilation and native allocation storage. */
public enum FfiMode {
    NATIVE, MANAGED;

    public static FfiMode parse(String value, String source) {
        return switch (value) {
            case "native" -> NATIVE;
            case "managed" -> MANAGED;
            default -> throw new FfiConfigurationException(source + " must be native or managed; got '" + value + "'");
        };
    }
    public static FfiMode configured() { return configured(System.getProperty("thc.ffiMode"), System.getenv("THC_FFI_MODE")); }
    public static FfiMode configured(String property) { return configured(property, System.getenv("THC_FFI_MODE")); }
    public static FfiMode configured(String property, String environment) {
        return property != null ? parse(property, "thc.ffiMode") : environment != null ? parse(environment, "THC_FFI_MODE") : NATIVE;
    }

    private record Options(String version, boolean managed) {}
    private static Options llvmOptions;
    private static boolean probed;
    // Retain metadata only, never an Engine or context-owned native state.
    // A failed probe remains retryable, matching the original synchronized lazy.
    private static synchronized Options llvmOptions() {
        if (!probed) {
            Options result;
            try (var engine = Engine.newBuilder().useSystemProperties(false).build()) {
                var language = engine.getLanguages().get("llvm");
                result = language == null ? null : new Options(language.getVersion(), language.getOptions().get("llvm.managed") != null);
            }
            llvmOptions = result; probed = true;
        }
        return llvmOptions;
    }
    public Context.Builder configure(Context.Builder builder) {
        var llvm = llvmOptions();
        if (this == MANAGED) {
            if (llvm == null) throw new FfiConfigurationException("--ffi managed is unavailable: the LLVM language is not installed");
            if (!llvm.managed()) throw new FfiConfigurationException("--ffi managed is unavailable: the installed LLVM " + llvm.version() + " does not expose llvm.managed; THC's llvm-community distribution provides native mode only");
            throw new FfiConfigurationException("--ffi managed is unavailable: LLVM exposes llvm.managed, but THC's native file/GMP providers and packaged bitcode have not been ported and verified for managed execution");
        }
        // An explicit native policy must not inherit managed=true. Community
        // LLVM rejects this option even when its value is false.
        if (llvm != null && llvm.managed()) builder.option("llvm.managed", "false");
        return builder;
    }
}
