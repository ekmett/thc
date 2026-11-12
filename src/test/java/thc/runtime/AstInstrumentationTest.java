// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.*;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class AstInstrumentationTest {
    @TruffleInstrument.Registration(id = "thc-activation-test", services = Attachment.class)
    public static final class Attachment extends TruffleInstrument {
        private Instrumenter instrumenter;
        @Override protected void onCreate(Env env) { instrumenter = env.getInstrumenter(); env.registerService(this); }
        EventBinding<ExecutionEventListener> attach(Source source, ExecutionEventListener listener) {
            return instrumenter.attachExecutionEventListener(SourceSectionFilter.newBuilder()
                .sourceIs(source).tagIs(StandardTags.StatementTag.class).indexIn(2, 1).build(), listener);
        }
        EventBinding<ExecutionEventListener> attachStatements(Source source, ExecutionEventListener listener) {
            return instrumenter.attachExecutionEventListener(SourceSectionFilter.newBuilder()
                .sourceIs(source).tagIs(StandardTags.StatementTag.class).build(), listener);
        }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(30, TimeUnit.SECONDS), "Activation barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    @Test void attachmentDuringActivationRetainsAncestryAndStackAccounting() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc");
            var attachment = context.getEngine().getInstruments().get("thc-activation-test").lookup(Attachment.class);
            var reached = new CountDownLatch(1); var proceed = new CountDownLatch(1);
            var callbacks = new AtomicInteger();
            final FunctionRoot outer; final RootCallTarget nested;
            var source = Source.newBuilder("thc", "x+y", "attachment.hs").build();
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Barrier extends Expr {
                    Barrier() { setRepresentation(new Literal(2L).getRepresentation()); }
                    @Override public boolean needsCallState() { return false; }
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) { reached.countDown(); await(proceed); return 2L; }
                }
                var value = new Literal(40L).located(new CoreSourceLocation(source.createSection(2, 1), List.of()));
                var body = new Primitive("+#", new Expr[]{new Barrier(), value});
                outer = new FunctionRoot(language, new FrameLayout().build(), "attaching activation", null,
                    new int[0], new int[0], new int[0], body, new Metrics(false), new CoreRepresentation[0],
                    body.getRepresentation(), value.getCoreSourceLocation(), new boolean[0], null, null, new int[0],
                    null, false, new int[0][], false, FunctionRootRole.FUNCTION, true);
                nested = new FunctionRoot(language, new FrameLayout().build(), "callback guest", null,
                    new int[0], new int[0], new int[0], new Literal(41L), new Metrics(false)).getCallTarget();
                outer.getCallTarget();
            } finally { context.leave(); }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var result = executor.submit(() -> {
                    context.enter();
                    try {
                        Object value = Calls.target(outer.getCallTarget(), new Object[]{16L});
                        var stack = AstStacks.astStackScope(outer);
                        assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                        return value;
                    } finally { context.leave(); }
                });
                try {
                    await(reached);
                    var binding = attachment.attach(source, new ExecutionEventListener() {
                        @Override public void onEnter(EventContext event, VirtualFrame frame) {
                            callbacks.incrementAndGet();
                            var stack = AstStacks.astStackScope(event.getInstrumentedNode());
                            assertEquals(1, stack.getDepth()); assertTrue(stack.getDriving());
                            assertTrue(frame.isLong(FrameLayout.BLOOM_FILTER));
                            assertEquals(outer.entryBloom(16L), frame.getLong(FrameLayout.BLOOM_FILTER));
                            assertEquals(41L, Calls.target(nested, new Object[]{frame.getLong(FrameLayout.BLOOM_FILTER)}));
                        }
                        @Override public void onReturnValue(EventContext event, VirtualFrame frame, Object value) {}
                        @Override public void onReturnExceptional(EventContext event, VirtualFrame frame, Throwable failure) {}
                    });
                    try { proceed.countDown(); assertEquals(42L, result.get(30, TimeUnit.SECONDS)); assertEquals(1, callbacks.get()); }
                    finally { binding.dispose(); }
                } finally { proceed.countDown(); }
            }
        }
    }
}
