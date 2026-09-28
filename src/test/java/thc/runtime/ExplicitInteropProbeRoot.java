// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.ValueProfile;
import thc.Language;

/** Private feasibility probe: no production lowering or foreign ABI declaration. */
@GenerateBytecode(languageClass = Language.class, enableYield = true)
public abstract class ExplicitInteropProbeRoot extends RootNode implements BytecodeRootNode {
    protected ExplicitInteropProbeRoot(Language language, FrameDescriptor descriptor) { super(language, descriptor); }

    public static final class Acquisition extends Node {
        @Child private InteropLibrary library;
        public static Acquisition create() { return new Acquisition(); }
        public InteropLibrary execute(Object receiver) {
            if (library == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                library = insert(InteropLibrary.getFactory().create(receiver));
            }
            if (!library.accepts(receiver)) throw new IllegalArgumentException("Receiver not accepted by acquired dispatcher");
            if (receiver instanceof ExplicitInteropLibraryTest.Receiver observed && CompilerDirectives.inCompiledCode()) {
                observed.compiledAcquisitions++;
                if (CompilerDirectives.isPartialEvaluationConstant(library)) observed.constantAcquisitions++;
            }
            return library;
        }
    }

    @Operation
    public static final class Acquire {
        @Specialization public static InteropLibrary run(Object receiver, @Cached(neverDefault = true) Acquisition acquisition) {
            return acquisition.execute(receiver);
        }
    }

    @Operation
    public static final class Consume {
        @Specialization public static long run(InteropLibrary library, Object receiver, long count,
                @Cached(value = "createIdentityProfile()", inline = false) ValueProfile identity) {
            return ExplicitInteropLibraryTest.consume(identity.profile(library), receiver, count);
        }
    }
}
