// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
/** GHC has already unboxed the public Int/Double arguments at this boundary. */
public final class JavaScriptImport {
    private final String source;
    @CompilationFinal(dimensions = 1) private final CoreKind[] arguments;
    private final CoreKind result;
    private final ForeignSafety safety;
    public JavaScriptImport(String source, CoreKind[] arguments, CoreKind result, ForeignSafety safety) {
        this.source = source; this.arguments = arguments; this.result = result; this.safety = safety;
    }
    public String getSource() { return source; }
    public CoreKind[] getArguments() { return arguments; }
    public CoreKind getResult() { return result; }
    public ForeignSafety getSafety() { return safety; }
}
