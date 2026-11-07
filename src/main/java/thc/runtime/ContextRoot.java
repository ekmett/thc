// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.Node;
import java.util.concurrent.atomic.AtomicReference;
import thc.Language;

/** Compilation-event ownership, not an execution guard. The token retains no context resources. */
public abstract class ContextRoot extends RootNode {
    private Object compilationOwner;
    AtomicReference<GraphRecovery.Failure> graphFailure = new AtomicReference<>();
    @CompilationFinal Assumption noGraphFailure = Assumption.create("no source failure receipt");
    volatile boolean compilationFailureObserved;

    protected ContextRoot(TruffleLanguage<?> language, FrameDescriptor descriptor) {
        super(language, descriptor);
        compilationOwner = language instanceof Language ? Language.currentState().getCompilationOwner() : null;
    }

    /** Compiler-created code inherits ownership without consulting an entered context. */
    protected ContextRoot(ContextRoot source, FrameDescriptor descriptor) {
        super(source.getLanguageInfo() == null ? null : source.getLanguage(Language.class), descriptor);
        compilationOwner = source.compilationOwner;
    }

    final Object compilationOwner() { return compilationOwner; }

    /** Reusable code has no single context owner. Configure only before target publication. */
    final void shareCompilationOwnership() { compilationOwner = null; }

    @Override public Node copy() {
        ContextRoot copy = (ContextRoot) super.copy();
        copy.graphFailure = new AtomicReference<>();
        copy.noGraphFailure = Assumption.create("no source failure receipt");
        copy.compilationFailureObserved = false;
        return copy;
    }
}
