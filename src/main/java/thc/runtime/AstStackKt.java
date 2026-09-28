// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;
import thc.Language;

public final class AstStackKt {
    private AstStackKt() {}
    public static AstStackScope astStackScope(Node node) {
        return Language.currentState(node).getThreadPollState().get().getAstStack$org_intelligence_thc();
    }
    public static boolean stackSpill(SavedGuestContinuation saved) { return saved.stackSpill(); }
}
