// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;

/** Clones preserve body identity and cached positive/negative target answers. */
public final class AstSelfTarget extends Node {
    private static final class CachedTarget {
        final RootCallTarget target;
        final boolean matches;
        CachedTarget(RootCallTarget target, boolean matches) { this.target = target; this.matches = matches; }
    }
    @CompilationFinal(dimensions = 1) private volatile CachedTarget[] cached = new CachedTarget[0];
    @ExplodeLoop public boolean matches(RootCallTarget target) {
        for (CachedTarget entry : cached) if (entry.target == target) return entry.matches;
        boolean result = ((GuestRoot) getRootNode()).isSelf(target);
        if (cached.length < 3) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            atomic(() -> {
                for (CachedTarget entry : cached) if (entry.target == target) return;
                if (cached.length < 3) {
                    CachedTarget[] next = Arrays.copyOf(cached, cached.length + 1);
                    next[cached.length] = new CachedTarget(target, result);
                    cached = next; // Clones may share the old immutable array.
                }
            });
        }
        return result;
    }
}
