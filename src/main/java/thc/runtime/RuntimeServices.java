// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Small target services behind a versioned private scalar ABI. Stable Haskell
 * modules expose typed records; hazardous controls stay in Unsafe internals. */
public final class RuntimeServices {
    private RuntimeServices() {}

    @TruffleBoundary
    public static long query(Node node, int backend, int selector, long index, long detail) {
        var state = Language.currentState(node);
        if (selector >= 0 && selector <= 7) {
            if (index != 0L || (selector < 5 && detail != 0L)) throw fault("Invalid runtime information index");
            return switch (selector) {
                case 0 -> 1L; // THC, rather than the native-GHC compatibility implementation.
                case 1 -> backend; // Actual lowering backend, not a context default.
                case 2 -> state.getEnv().isNativeAccessAllowed() ? 1L : 0L;
                case 3 -> state.getEnv().isCreateThreadAllowed() ? 1L : 0L;
                case 4 -> state.getThreads().getCpuAffinity().getCount();
                case 5 -> {
                    var root = node.getRootNode();
                    var info = root == null ? null : root.getLanguageInfo();
                    String version = info == null ? null : info.getVersion();
                    yield version == null ? RuntimeServiceStatus.UNAVAILABLE : RuntimeServiceStatus.text(version, detail);
                }
                default -> {
                    try {
                        String property = System.getProperty(selector == 6 ? "java.version" : "java.vm.name");
                        yield property == null ? RuntimeServiceStatus.UNAVAILABLE : RuntimeServiceStatus.text(property, detail);
                    } catch (SecurityException ignored) { yield RuntimeServiceStatus.DENIED; }
                }
            };
        }
        if (selector >= 100 && selector <= 199) return RuntimeThreadServices.query(state, selector, index, detail);
        if (selector >= 200 && selector <= 299) return RuntimeMemoryServices.query(state, selector, index, detail);
        if (selector >= 300 && selector <= 399) return RuntimeGCServices.query(state, selector, index, detail);
        if (selector >= 400 && selector <= 499) return state.getRuntimeJit().query(selector, index, detail);
        if (selector >= 500 && selector <= 599) return state.getRuntimeTrace().query(selector, index, detail);
        throw fault("Unknown runtime service query " + selector);
    }

    @TruffleBoundary
    public static long control(Node node, int selector, long setting) {
        var state = Language.currentState(node);
        return switch (selector) {
            case 400 -> state.getRuntimeJit().control(selector, setting);
            case 500 -> state.getRuntimeTrace().control(selector, setting);
            default -> throw fault("Unknown runtime service control " + selector);
        };
    }

    @TruffleBoundary
    public static long trace(Node node, int operation, long token, ManagedAddress bytes, long length) {
        var state = Language.currentState(node);
        var threads = state.getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { return state.getRuntimeTrace().emit(operation, token, bytes, length); }
        finally { threads.leaveForeign(previous); }
    }
}
