// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Environment ABI controls, not a shared-lowerer or persisted THC AOT claim. */
class CapturedProgramInstanceTest {
    private ExecutableProgram program(Language language, boolean bytecode, long marker) {
        var wide = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
        var binding = map("id", "marker", "name", "marker", "lifted", false, "coercion", false,
            "rep", wide, "expr", list("lit", "int", Long.toString(marker)));
        var module = map("bindings", list(binding), "constructors", list(), "instrument", true);
        return bytecode ? new BytecodeProgram(language, module) : new Program(language, module);
    }

    private static final class Apply extends RootNode {
        @Child private Dispatch dispatch = Dispatch.create(1, false, new Metrics(true));
        Apply() { super(null); }
        @Override public Object execute(VirtualFrame frame) {
            return dispatch.execute(frame, (Closure) frame.getArguments()[0], new Object[]{frame.getArguments()[1]});
        }
        Object call(Closure function, long value) { return Calls.target(getCallTarget(), new Object[]{function, value}); }
    }

    @Test void oneTargetAlternatesSameContextInstancesThroughRealPapDispatch() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var target = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) {
                        var environment = (CapturedFrame) frame.getArguments()[1];
                        return (Long) environment.getProgram().entryValue("marker") + environment.getLong(0) +
                            (Long) frame.getArguments()[2] + (Long) frame.getArguments()[3];
                    }
                }.getCallTarget();
                var apply = new Apply();
                for (boolean bytecode : new boolean[]{false, true}) {
                    var first = program(language, bytecode, 11);
                    var second = program(language, bytecode, 31);
                    var layout = new CaptureLayout(language, new boolean[]{true}, new boolean[]{true});
                    var a = new Closure(layout.captureValues(new Object[]{-3L}, first), 2, target).pap(new Object[]{7L});
                    var b = new Closure(layout.captureValues(new Object[]{-3L}, second), 2, target).pap(new Object[]{7L});
                    assertSame(a.target, b.target);
                    assertEquals(20L, apply.call(a, 5));
                    assertEquals(40L, apply.call(b, 5));
                    assertEquals(21L, apply.call(a, 6));
                    assertSame(first, a.environment.getProgram());
                    assertSame(second, b.environment.getProgram());
                }
            } finally { context.leave(); }
        }
    }

    @Test void thunkTransportRetainsFreshInstanceAcrossSeparateContexts() {
        for (boolean bytecode : new boolean[]{false, true}) {
            for (long expected : new long[]{11, 31}) {
                try (var context = Main.executionContext(false)) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        // Production remains EXCLUSIVE: each context still owns its roots.
                        RootCallTarget target = new RootNode(null) {
                            @Override public Object execute(VirtualFrame frame) {
                                return ((CapturedFrame) frame.getArguments()[1]).getProgram().entryValue("marker");
                            }
                        }.getCallTarget();
                        var owner = program(language, bytecode, expected);
                        var layout = new CaptureLayout(language, new boolean[0]);
                        var thunk = new Thunk(target, layout.captureValues(new Object[0], owner));
                        var cache = new ThunkTargetCache(new Metrics(true));
                        assertEquals(expected, cache.call(thunk.getTarget(), thunk.getEnvironment()));
                        assertSame(owner, thunk.getEnvironment().getProgram());
                        assertEquals(0, thunk.getState(), "Transport alone must not update or force a thunk");
                    } finally { context.leave(); }
                }
            }
        }
    }

    @Test void instanceCapturePreservesPrimitiveRawCellAndAllocationAuthentication() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var owner = program(language, false, 11);
                var captures = new CaptureLayout(language, new boolean[]{true, false}, new boolean[]{true, false});
                var slots = new FrameLayout();
                int number = slots.bind("number"), reference = slots.bind("cell");
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], slots.build());
                var cell = new RecCell();
                FrameAccess.writeLong(frame, number, Long.MIN_VALUE);
                FrameAccess.write(frame, reference, cell);
                for (var environment : list(captures.capture(frame, new int[]{number, reference}, owner),
                        captures.captureValues(new Object[]{Long.MIN_VALUE, cell}, owner))) {
                    assertSame(owner, environment.getProgram());
                    assertEquals(Long.MIN_VALUE, environment.getLong(0));
                    assertSame(cell, environment.getObject(1));
                    assertFalse(cell.getInitialized());
                }
                assertNull(captures.captureValues(new Object[]{3L, cell}).getProgram(), "Legacy captures remain unbound");
                assertThrows(RuntimeFault.class, () -> new CapturedFrame(captures, new Object(), owner));
                assertThrows(RuntimeFault.class, () -> captures.captureValues(new Object[]{3, cell}, owner));
            } finally { context.leave(); }
        }
    }
}
