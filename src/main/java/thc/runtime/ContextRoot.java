// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import thc.Language;

/** Compilation-event ownership, not an execution guard. The token retains no context resources. */
public abstract class ContextRoot extends RootNode {
    private Object compilationOwner;

    protected ContextRoot(TruffleLanguage<?> language, FrameDescriptor descriptor) {
        super(language, descriptor);
        compilationOwner = language instanceof Language ? Language.currentState().getCompilationOwner() : null;
    }

    final Object compilationOwner() { return compilationOwner; }

    /** Reusable code has no single context owner. Configure only before target publication. */
    final void shareCompilationOwnership() { compilationOwner = null; }
}
