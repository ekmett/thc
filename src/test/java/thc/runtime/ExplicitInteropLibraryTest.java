// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.profiles.ValueProfile;
import java.util.concurrent.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Isolates compiled explicit-dispatcher transport from the genuine public ABI controls. */
class ExplicitInteropLibraryTest {
    @Test void productionAcquisitionAndMessageUseKeepTheActualBoundedDispatcher() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, false, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var acquisition = new RootNode(language) {
                    @Child private InteropLibraryAcquisition access = new InteropLibraryAcquisition();
                    @Override public Object execute(VirtualFrame frame) { return access.execute(frame.getArguments()[0]); }
                }.getCallTarget();
                var receiver = new Receiver();
                var library = (InteropLibrary) acquisition.call(receiver);
                var consumer = new RootNode(language) {
                    @Child private InteropAccess access = new InteropAccess();
                    @Override public Object execute(VirtualFrame frame) {
                        return access.execute(PolyglotOp.READ_ARRAY_ELEMENT, frame.getArguments());
                    }
                }.getCallTarget();
                // One actual interpreter message establishes the use-site profile.
                assertEquals(3L, consumer.call(receiver, library, 0L, Unit.INSTANCE));
                compile(consumer);
                long sum = 0;
                for (long i = 0; i < 13; i++) sum += (Long) consumer.call(receiver, library, i, Unit.INSTANCE);
                assertEquals(117L, sum); assertEquals(14, receiver.reads); assertEquals(13, receiver.compiledReads); valid(consumer);
                assertSame(acquisition.getRootNode(), library.getRootNode());
                var fallback = (InteropLibrary) acquisition.call(42L);
                assertNotSame(library, fallback); assertSame(acquisition.getRootNode(), fallback.getRootNode());
                for (Object value : new Object[]{42L, "text", true, 3.5}) {
                    assertSame(fallback, acquisition.call(value)); assertTrue(fallback.accepts(value));
                }
                assertSame(library, acquisition.call(receiver));
                var failure = assertThrows(InteropFailure.class, () -> consumer.call(receiver, library, -1L, Unit.INSTANCE));
                assertInstanceOf(InvalidArrayIndexException.class, failure.getOriginal());
                var failureAccess = InteropLibrary.getUncached();
                assertTrue(failureAccess.isException(failure));
                Object meta = failureAccess.getMetaObject(failure);
                var metaAccess = InteropLibrary.getFactory().create(meta);
                assertEquals("InvalidArrayIndexException", metaAccess.getMetaSimpleName(meta));
                assertEquals("com.oracle.truffle.api.interop.InvalidArrayIndexException", metaAccess.getMetaQualifiedName(meta));
                assertTrue(metaAccess.isMetaInstance(meta, failure));
                assertFalse(metaAccess.isMetaInstance(meta, InteropFailure.create(UnsupportedMessageException.create())));
                assertSame(failure, assertThrows(InteropFailure.class, () -> failureAccess.throwException(failure)));
                assertEquals(-1L, ((InvalidArrayIndexException) failure.getOriginal()).getInvalidIndex());
                for (String hint : new String[]{null, "protocol detail"}) {
                    var original = UnsupportedTypeException.create(new Object[]{42L}, hint);
                    var wrapped = InteropFailure.create(original);
                    var messageAccess = InteropLibrary.getFactory().create(wrapped);
                    assertTrue(messageAccess.hasExceptionMessage(wrapped));
                    assertEquals(hint == null ? "UnsupportedTypeException" : hint, messageAccess.getExceptionMessage(wrapped));
                    assertSame(original, wrapped.getOriginal());
                }
                assertThrows(InteropFailure.class, () -> acquisition.call(new Object()));
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class Receiver implements TruffleObject {
        int reads, compiledReads, compiledConsumers, constantConsumers, compiledAcquisitions, constantAcquisitions;
        InteropLibrary lastLibrary;
        @ExportMessage boolean hasArrayElements() { return true; }
        // A separate cached export makes this a genuinely adoptable library. The read tested
        // below itself is stateless, so its first use does not need an unrelated DSL warmup.
        @ExportMessage long getArraySize(
                @Cached(value = "create()", uncached = "create()", neverDefault = true) Read read) { return read.size(); }
        @ExportMessage boolean isArrayElementReadable(long index) { return index >= 0 && index < 64; }
        @ExportMessage Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
            reads++;
            if (CompilerDirectives.inCompiledCode()) compiledReads++;
            return index + 3;
        }
    }
    static final class Read extends Node {
        static Read create() { return new Read(); }
        long size() { return 64; }
    }

    static long consume(InteropLibrary library, Object receiver, long count) {
        if (receiver instanceof Receiver observed) observed.lastLibrary = library;
        if (receiver instanceof Receiver observed && CompilerDirectives.inCompiledCode()) {
            observed.compiledConsumers++;
            if (CompilerDirectives.isPartialEvaluationConstant(library)) observed.constantConsumers++;
        }
        if (!library.accepts(receiver)) throw new IllegalArgumentException("Mismatched dispatcher");
        long sum = 0;
        try {
            if (!library.hasArrayElements(receiver)) throw new AssertionError("Not an array");
            for (long index = 0; index < count; index++) sum += (Long) library.readArrayElement(receiver, index);
        } catch (InteropException failure) { throw new AssertionError(failure); }
        return sum;
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void polymorphicUseKeepsTheSuppliedDispatcherAndOriginalAdoption(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var receiver = new Receiver();
                var firstGetter = getter(language, backend); var secondGetter = getter(language, backend);
                var first = (InteropLibrary) firstGetter.call(receiver);
                var second = (InteropLibrary) secondGetter.call(receiver);
                assertNotSame(first, second);
                RootCallTarget consumer;
                if (backend.equals("bytecode")) consumer = ExplicitInteropProbeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.beginReturn(); b.beginConsume();
                    b.emitLoadArgument(0); b.emitLoadArgument(1); b.emitLoadArgument(2);
                    b.endConsume(); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                else consumer = new RootNode(language) {
                    private final ValueProfile identity = ValueProfile.createIdentityProfile();
                    @Override public Object execute(VirtualFrame frame) {
                        return consume(identity.profile((InteropLibrary) frame.getArguments()[0]), frame.getArguments()[1], (Long) frame.getArguments()[2]);
                    }
                }.getCallTarget();
                assertEquals(0L, consumer.call((Object) first, receiver, 0L)); assertEquals(0, receiver.reads);
                compile(consumer);
                assertEquals(117L, consumer.call((Object) first, receiver, 13L));
                assertEquals(13, receiver.compiledReads); valid(consumer);
                assertSame(first, receiver.lastLibrary);
                // A second identity makes ValueProfile generic. It must neither substitute
                // the first dispatcher nor adopt either dispatcher into the consumer.
                assertEquals(117L, consumer.call((Object) second, receiver, 13L));
                assertSame(second, receiver.lastLibrary); assertEquals(26, receiver.reads);
                assertEquals(117L, consumer.call((Object) first, receiver, 13L));
                assertSame(first, receiver.lastLibrary); assertEquals(39, receiver.reads);
                compile(consumer);
                assertEquals(117L, consumer.call((Object) second, receiver, 13L));
                assertSame(second, receiver.lastLibrary);
                assertEquals(117L, consumer.call((Object) first, receiver, 13L));
                assertSame(first, receiver.lastLibrary); assertEquals(65, receiver.reads); valid(consumer);
                assertSame(firstGetter.getRootNode(), first.getRootNode());
                assertSame(secondGetter.getRootNode(), second.getRootNode());
                observe("polymorphic-" + backend, consumer, receiver);
            } finally { context.leave(); }
        }
    }

    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("compiler.TraceInlining", "true").option("engine.TraceTransferToInterpreter", "true")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
    }
    private static void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private static void observe(String name, RootCallTarget target, Receiver receiver) throws Exception {
        System.out.printf("%s valid=%s compiledConsumers=%d constantConsumers=%d reads=%d compiledReads=%d compiledAcquisitions=%d constantAcquisitions=%d%n", name,
            target.getClass().getMethod("isValidLastTier").invoke(target), receiver.compiledConsumers,
            receiver.constantConsumers, receiver.reads, receiver.compiledReads, receiver.compiledAcquisitions, receiver.constantAcquisitions);
    }
    private static RootCallTarget getter(Language language, String backend) {
        if (backend.equals("bytecode")) return ExplicitInteropProbeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot(); b.beginReturn(); b.beginAcquire(); b.emitLoadArgument(0); b.endAcquire(); b.endReturn(); b.endRoot();
        }).getNode(0).getCallTarget();
        return new RootNode(language) {
            @Child private ExplicitInteropProbeRoot.Acquisition acquisition = ExplicitInteropProbeRoot.Acquisition.create();
            @Override public Object execute(VirtualFrame frame) { return acquisition.execute(frame.getArguments()[0]); }
        }.getCallTarget();
    }

    @ParameterizedTest @CsvSource({"ast,false", "ast,true", "bytecode,false", "bytecode,true"})
    void constantDispatcherTransportIsolatedFromAcquisition(String backend, boolean local) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var receiver = new Receiver();
                var library = (InteropLibrary) getter(language, "ast").call(receiver);
                RootCallTarget target;
                if (backend.equals("bytecode")) target = ExplicitInteropProbeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var slot = local ? b.createLocal() : null;
                    if (local) { b.beginStoreLocal(slot); b.emitLoadArgument(2); b.endStoreLocal(); }
                    b.beginReturn(); b.beginConsume();
                    if (local) b.emitLoadLocal(slot); else b.emitLoadArgument(2);
                    b.emitLoadArgument(0); b.emitLoadArgument(1); b.endConsume(); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                else {
                    var descriptor = FrameDescriptor.newBuilder(); descriptor.addSlot(FrameSlotKind.Object, "library", null);
                    target = new RootNode(language, descriptor.build()) {
                        private final ValueProfile identity = ValueProfile.createIdentityProfile();
                        @Override public Object execute(VirtualFrame frame) {
                            InteropLibrary supplied = library;
                            if (local) { frame.setObject(0, library); supplied = (InteropLibrary) frame.getValue(0); }
                            return consume(identity.profile(supplied), frame.getArguments()[0], (Long) frame.getArguments()[1]);
                        }
                    }.getCallTarget();
                }
                assertEquals(0L, target.call(receiver, 0L, library)); assertEquals(0, receiver.reads);
                compile(target);
                assertEquals(117L, target.call(receiver, 13L, library));
                observe("constant-" + backend + "-local=" + local, target, receiver);
                assertEquals(13, receiver.compiledReads); valid(target);
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void getterResultIsVisibleInsideCompiledLoop(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var receiver = new Receiver(); var getter = getter(language, backend);
                var library = (InteropLibrary) getter.call(receiver);
                var caller = new RootNode(language) {
                    @Child private DirectCallNode get = DirectCallNode.create(getter);
                    private final ValueProfile identity = ValueProfile.createIdentityProfile();
                    @Override public Object execute(VirtualFrame frame) {
                        Object value = frame.getArguments()[0];
                        var supplied = (InteropLibrary) get.call(value);
                        return consume(identity.profile(supplied), value, (Long) frame.getArguments()[1]);
                    }
                }.getCallTarget();
                assertEquals(0L, caller.call(receiver, 0L)); assertEquals(0, receiver.reads);
                compile(caller);
                assertEquals(117L, caller.call(receiver, 13L));
                observe("getter-to-loop-" + backend, caller, receiver);
                assertEquals(13, receiver.reads); assertEquals(13, receiver.compiledReads);
                valid(caller); assertSame(library, getter.call(receiver));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void acquisitionAndConsumerAtOneRootShareActualDispatcher(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var receiver = new Receiver();
                RootCallTarget target;
                if (backend.equals("bytecode")) target = ExplicitInteropProbeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.beginReturn(); b.beginConsume();
                    b.beginAcquire(); b.emitLoadArgument(0); b.endAcquire(); b.emitLoadArgument(0); b.emitLoadArgument(1);
                    b.endConsume(); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                else target = new RootNode(language) {
                    @Child private ExplicitInteropProbeRoot.Acquisition acquisition = ExplicitInteropProbeRoot.Acquisition.create();
                    @Override public Object execute(VirtualFrame frame) {
                        Object receiver = frame.getArguments()[0];
                        return consume(acquisition.execute(receiver), receiver, (Long) frame.getArguments()[1]);
                    }
                }.getCallTarget();
                assertEquals(0L, target.call(receiver, 0L)); // Initialize acquisition/operation nodes, no reads.
                assertEquals(0, receiver.reads);
                compile(target);
                assertEquals(117L, target.call(receiver, 13L));
                observe("same-root-" + backend, target, receiver);
                assertEquals(13, receiver.reads); assertEquals(13, receiver.compiledReads);
                valid(target);
            } finally { context.leave(); }
        }
    }

    @Test void diagnoseIndependentConsumerCompilation() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var target = new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        return consume((InteropLibrary) frame.getArguments()[0], frame.getArguments()[1], (Long) frame.getArguments()[2]);
                    }
                }.getCallTarget();
                compile(target);
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void acquiredDispatcherFlowsAcrossRootsIntoCompiledMessageLoop(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var receiver = new Receiver(); var getter = getter(language, backend);
                var library = (InteropLibrary) getter.call(receiver); // Acquire only; no message warmup.
                assertTrue(library.isAdoptable()); assertNotNull(library.getParent());
                assertSame(getter.getRootNode(), library.getRootNode());
                assertTrue(library.accepts(receiver)); assertFalse(library.accepts("different receiver"));
                assertSame(library, getter.call(receiver)); assertEquals(0, receiver.reads);
                var consumer = new RootNode(language) {
                    @Override public String getName() { return "explicit-dispatch-consumer"; }
                    private final ValueProfile identity = ValueProfile.createIdentityProfile();
                    @Override public Object execute(VirtualFrame frame) {
                        return consume(identity.profile((InteropLibrary) frame.getArguments()[0]), frame.getArguments()[1], (Long) frame.getArguments()[2]);
                    }
                }.getCallTarget();
                var caller = new RootNode(language) {
                    @Override public String getName() { return "explicit-dispatch-caller-" + backend; }
                    @Child private DirectCallNode get = DirectCallNode.create(getter);
                    @Child private DirectCallNode use = DirectCallNode.create(consumer);
                    @Override public Object execute(VirtualFrame frame) {
                        Object value = frame.getArguments()[0];
                        return use.call(get.call(value), value, frame.getArguments()[1]);
                    }
                }.getCallTarget();
                assertEquals(0L, caller.call(receiver, 0L)); assertEquals(0, receiver.reads);
                compile(caller);
                assertEquals(117L, caller.call(receiver, 13L));
                observe("get-root-use-root-" + backend, caller, receiver);
                assertEquals(13, receiver.reads); assertEquals(13, receiver.compiledReads);
                valid(caller); assertSame(library, getter.call(receiver));
            } finally { context.leave(); }
        }
    }

    @Test void acquiredDispatcherSurvivesBytecodeYieldAndSequentialThreadTransfer() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            ContinuationResult continuation; Receiver receiver = new Receiver();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var root = ExplicitInteropProbeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var library = b.createLocal();
                    b.beginStoreLocal(library); b.beginAcquire(); b.emitLoadArgument(0); b.endAcquire(); b.endStoreLocal();
                    b.beginYield(); b.emitLoadLocal(library); b.endYield();
                    b.beginReturn(); b.beginConsume(); b.emitLoadLocal(library); b.emitLoadArgument(0); b.emitLoadConstant(13L);
                    b.endConsume(); b.endReturn(); b.endRoot();
                }).getNode(0);
                continuation = (ContinuationResult) root.getCallTarget().call(receiver);
                var library = (InteropLibrary) continuation.getResult();
                assertTrue(library.accepts(receiver)); assertSame(root, library.getRootNode());
                assertEquals(0, receiver.reads);
            } finally { context.leave(); }
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertEquals(117L, executor.submit(() -> {
                    context.enter();
                    try { return continuation.continueWith(null); }
                    finally { context.leave(); }
                }).get(10, TimeUnit.SECONDS));
            }
            assertEquals(13, receiver.reads);
        }
    }
}
