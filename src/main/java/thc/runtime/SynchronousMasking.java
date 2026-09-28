// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;
public final class SynchronousMasking {
    public static final SynchronousMasking INSTANCE = new SynchronousMasking();
    private SynchronousMasking() {}
    public static MaskingState current(Node node) { return Language.currentState(node).getThreadMaskingState$org_intelligence_thc().get().getValue(); }
    @TruffleBoundary public static void set(Node node, MaskingState state) { Language.currentState(node).getMaskingState$org_intelligence_thc().set(state); }
}
