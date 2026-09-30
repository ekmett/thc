// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Tag;
import thc.CoreModules;
import thc.EntryValue;
import thc.ForeignExceptionFixtureSupport;
import thc.Language;
import thc.HostReference;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Both actual backend operations, with foreign-protocol models independent of guest primops. */
class PolyglotStorageTest {
    @ExportLibrary(value = InteropLibrary.class, delegateTo = "bytes")
    static final class ForeignBytes implements TruffleObject {
        final CbitsBuffer bytes;
        int bulkReads;
        Runnable afterRead;
        ForeignBytes(byte[] bytes, boolean writable) { this.bytes = new CbitsBuffer(bytes, writable); }
        @ExportMessage void readBuffer(long offset, byte[] destination, int at, int length) throws InvalidBufferOffsetException {
            bulkReads++; bytes.readBuffer(offset, destination, at, length);
            if (afterRead != null) afterRead.run();
        }
    }
    /** A real shared thunk with a saved child cut before its foreign handle exists. */
    private static final class LazyHandle extends GuestRoot {
        private final ForeignValue value;
        int prefix, suffix;
        AsyncRequest request;
        LazyHandle(Language language, ForeignValue value) {
            super(language, new FrameLayout().build()); this.value = value;
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public boolean getAsynchronousExceptions() { return true; }
        @Override public Object execute(VirtualFrame frame) {
            prefix++;
            var threads = Language.currentState(this).getThreads();
            request = threads.send(threads.currentIdentity(), "lazy handle");
            assertSame(request, threads.poll(this));
            return new AstCapture(request, SynchronousMasking.current(this)).append((saved, input) -> {
                assertSame(Unit.INSTANCE, input); suffix++; return value;
            }).freeze(this, frame.materialize());
        }
    }
    private static Object finishChild(Language language, SavedGuestContinuation saved, TupleShape shape) {
        return new RootNode(language) {
            @Child private Force force = new Force(new Metrics(false), true);
            @Override public Object execute(VirtualFrame frame) { return force.drainStack(saved, shape); }
        }.getCallTarget().call();
    }
    @ExportLibrary(InteropLibrary.class)
    static final class ForeignEffect implements TruffleObject {
        int effects;
        Object value;
        @ExportMessage boolean isExecutable() { return true; }
        @ExportMessage Object execute(Object[] arguments) {
            assertEquals(1, arguments.length); effects++; value = arguments[0]; return value;
        }
        @ExportMessage boolean hasArrayElements() { return true; }
        @ExportMessage long getArraySize() { return 1; }
        @ExportMessage boolean isArrayElementReadable(long index) { return index == 0; }
        @ExportMessage boolean isArrayElementModifiable(long index) { return index == 0; }
        @ExportMessage boolean isArrayElementInsertable(long index) { return false; }
        @ExportMessage Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (index != 0) throw InvalidArrayIndexException.create(index); return value;
        }
        @ExportMessage void writeArrayElement(long index, Object next) throws InvalidArrayIndexException {
            if (index != 0) throw InvalidArrayIndexException.create(index); effects++; value = next;
        }
    }
    @Tag("foreign-exceptions-full-core")
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void bothHandleDemandsResumeWithoutReplayingEarlierOperands(String backend) throws Exception {
        for (var operation : List.of(PolyglotOp.EXECUTE_VALUE, PolyglotOp.ARRAY_WRITE)) try (var context = coldContext()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var models = new PolyglotFFITest();
                var module = ForeignExceptionFixtureSupport.link(models.foreignModule(models.call(operation)), "call");
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                var target = program.entryTarget("call");
                var shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                var foreign = new ForeignEffect();
                var receiver = new LazyHandle(language, new ForeignValue(owner, foreign));
                var argument = new LazyHandle(language, new ForeignValue(owner, 42L));
                var receiverThunk = new Thunk(receiver.getCallTarget(), null);
                var argumentThunk = new Thunk(argument.getCallTarget(), null);
                assertTrue(context.asValue(new EntryValue(program, "call", operation.getArguments().size())).invokeMember("compile").asBoolean());
                Object answer = Calls.target(target, operation == PolyglotOp.EXECUTE_VALUE
                    ? new Object[]{0L, receiverThunk, argumentThunk, Unit.INSTANCE}
                    : new Object[]{0L, receiverThunk, 0L, argumentThunk, Unit.INSTANCE});
                var first = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(answer));
                assertSame(receiver.request, first.asyncRequest()); assertEquals(0, argument.prefix); assertEquals(0, foreign.effects);
                receiver.request.acknowledge();
                var second = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(finishChild(language, first, shape)));
                assertSame(argument.request, second.asyncRequest());
                assertEquals(1, receiver.prefix); assertEquals(1, receiver.suffix); assertEquals(2, receiverThunk.getState());
                assertEquals(1, argument.prefix); assertEquals(0, argument.suffix); assertEquals(0, foreign.effects);
                argument.request.acknowledge();
                answer = finishChild(language, second, shape);
                var result = TupleResults.ownedTupleResult(answer, shape);
                if (operation == PolyglotOp.EXECUTE_VALUE)
                    assertEquals(42L, ((ForeignValue) shape.getLayout().getObject(result, 0)).getReceiver());
                else assertEquals(0L, shape.getLayout().getLong(result, 0));
                assertEquals(42L, foreign.value); assertEquals(1, foreign.effects);
                assertEquals(1, receiver.prefix); assertEquals(1, receiver.suffix);
                assertEquals(1, argument.prefix); assertEquals(1, argument.suffix); assertEquals(2, argumentThunk.getState());
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @Tag("foreign-exceptions-full-core")
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalGhcCopyResumesLazyHandleBeforePerformingForeignRead(String backend) throws Exception {
        try (var context = coldContext()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState();
            owner.getThreads().enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                String entry = "main:PolyglotStorage.copySlice#";
                var module = new LinkedHashMap<>(CoreModules.reachable(ForeignExceptionFixtureSupport.source("post"), entry, true));
                module.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                var target = program.entryTarget(entry);
                var shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                var bytes = new ForeignBytes(new byte[]{1,2,3,4,5,6}, false);
                var child = new LazyHandle(language, new ForeignValue(owner, bytes));
                var thunk = new Thunk(child.getCallTarget(), null);
                assertTrue(context.asValue(new EntryValue(program, entry, 4)).invokeMember("compile").asBoolean());
                assertEquals(0, child.prefix); assertEquals(0, bytes.bulkReads);
                Object answer = Calls.target(target, new Object[]{0L, thunk, 1L, 3L, Unit.INSTANCE});
                var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(answer));
                assertSame(target.getRootNode(), saved.getSourceRoot(), "The saved cut must retain the pending foreign copy");
                assertSame(child.request, saved.asyncRequest());
                assertEquals(1L, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(1, child.prefix); assertEquals(0, child.suffix);
                assertEquals(0, bytes.bulkReads, "No foreign effect may run before handle demand completes");
                child.request.acknowledge();
                answer = finishChild(language, saved, shape);
                assertArrayEquals(new byte[]{2,3,4}, (byte[]) shape.getLayout().getObject(TupleResults.ownedTupleResult(answer, shape), 0));
                assertEquals(1, child.prefix); assertEquals(1, child.suffix);
                assertEquals(2, thunk.getState()); assertEquals(1, bytes.bulkReads);
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @Tag("foreign-exceptions-full-core")
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalGhcViewsKeepTheirTypedArrayDeclarationsOnFirstCompiledCall(String backend) throws Exception {
        for (boolean writable : new boolean[]{false, true}) try (var context = coldContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                String entry = "main:PolyglotStorage." + (writable ? "mutableView#" : "view#");
                var module = new LinkedHashMap<>(CoreModules.reachable(ForeignExceptionFixtureSupport.source("post"), entry, true));
                module.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, false) : new BytecodeProgram(language, module, false);
                var target = program.entryTarget(entry);
                var shape = ((GuestRoot) target.getRootNode()).getTupleResult();
                var bytes = new byte[8];
                assertTrue(context.asValue(new EntryValue(program, entry, 2)).invokeMember("compile").asBoolean());
                Object count = target.getClass().getMethod("getCallCount").invoke(target);
                Object answer = Calls.target(target, new Object[]{0L, bytes, Unit.INSTANCE});
                assertEquals(1L, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(count, target.getClass().getMethod("getCallCount").invoke(target));
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var view = (ForeignValue) shape.getLayout().getObject(TupleResults.ownedTupleResult(answer, shape), 0);
                assertEquals(writable, InteropLibrary.getUncached().isBufferWritable(view.getReceiver()));
                if (writable) InteropLibrary.getUncached().writeBufferByte(view.getReceiver(), 1, (byte) 42);
                else {
                    assertThrows(UnsupportedMessageException.class,
                        () -> InteropLibrary.getUncached().writeBufferByte(view.getReceiver(), 1, (byte) 42));
                    bytes[1] = 42;
                }
                assertEquals(42, bytes[1]);
                assertEquals(42, InteropLibrary.getUncached().readBufferByte(view.getReceiver(), 1));
            } finally { context.leave(); }
        }
    }
    @Tag("foreign-exceptions-full-core")
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalGhcCopyRetainsOrdinaryColdCodeAndCommitsBeforeAsyncDelivery(String backend) throws Exception {
        for (boolean into : new boolean[]{false, true}) for (boolean deliver : new boolean[]{false, true}) try (var context = coldContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var owner = Language.currentState();
                String entry = "main:PolyglotStorage." + (into ? "copyInto#" : "copySlice#");
                var module = new LinkedHashMap<>(CoreModules.reachable(ForeignExceptionFixtureSupport.source("post"), entry, true));
                module.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, deliver) : new BytecodeProgram(language, module, deliver);
                var target = program.entryTarget(entry);
                var shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                var bytes = new ForeignBytes(new byte[]{1,2,3,4,5,6,7,8}, false);
                var value = new ForeignValue(owner, bytes);
                var destination = new byte[8];
                var pending = new java.util.concurrent.atomic.AtomicReference<AsyncRequest>();
                if (deliver) bytes.afterRead = () -> pending.set(owner.getThreads().send(owner.getThreads().currentIdentity(), "copied"));
                owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var published = context.asValue(new EntryValue(program, entry, into ? 6 : 4));
                    assertTrue(published.invokeMember("compile").asBoolean());
                    assertEquals(0, bytes.bulkReads, "compilation must not execute a foreign read");
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    Object count = target.getClass().getMethod("getCallCount").invoke(target);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    Object answer = Calls.target(target, into
                        ? new Object[]{0L, value, 2L, destination, 1L, 4L, Unit.INSTANCE}
                        : new Object[]{0L, value, 2L, 4L, Unit.INSTANCE});
                    assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    assertEquals(count, target.getClass().getMethod("getCallCount").invoke(target));
                    // Both backends must retain their first installed target, including
                    // the original cold async branch, without fabricated observations.
                    boolean retained = true;
                    assertEquals(retained, target.getClass().getMethod("isValidLastTier").invoke(target), entry + " async=" + deliver);
                    if (deliver) {
                        var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(answer));
                        assertSame(pending.get(), saved.asyncRequest()); assertEquals(retained, pending.get().compiledCapture);
                        pending.get().acknowledge(); answer = saved.continueWith(Unit.INSTANCE);
                    }
                    if (into) {
                        assertEquals(0L, shape.getLayout().getLong(TupleResults.ownedTupleResult(answer, shape), 0));
                        assertArrayEquals(new byte[]{0,3,4,5,6,0,0,0}, destination);
                    } else assertArrayEquals(new byte[]{3,4,5,6}, (byte[]) shape.getLayout().getObject(TupleResults.ownedTupleResult(answer, shape), 0));
                    assertEquals(1, bytes.bulkReads, "completed-result resume must not repeat the bulk read");
                } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
    private static Context coldContext() {
        return Context.newBuilder("thc", "js").allowExperimentalOptions(true).allowNativeAccess(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false").build();
    }
    private static Context context() {
        return Context.newBuilder("thc", "js").allowExperimentalOptions(true)
            .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.ALL).build();
    }
    private static RootCallTarget target(String backend, PolyglotOp op) {
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        int count = op.getArguments().size();
        if (backend.equals("bytecode")) return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot(); var result = b.createLocal("foreign result", "value");
            b.beginPolyglotStorage(result, op);
            for (int i = 0; i < count; i++) b.emitLoadArgument(i);
            b.endPolyglotStorage();
            b.beginReturn(); b.emitLoadLocal(result); b.endReturn(); b.endRoot();
        }).getNode(0).getCallTarget();
        var descriptor = FrameDescriptor.newBuilder(); descriptor.addSlot(FrameSlotKind.Illegal, "result", null);
        Expr[] arguments = new Expr[count];
        for (int i = 0; i < count; i++) {
            final int index = i;
            arguments[i] = new Expr() { @Override public Object execute(VirtualFrame frame) { return frame.getArguments()[index]; } };
        }
        return new RootNode(language, descriptor.build()) {
            @Child PolyglotExpression expression = new PolyglotExpression(op, arguments);
            @Override public Object execute(VirtualFrame frame) { expression.executeTuple(frame, new int[]{0}, 0); return frame.getValue(0); }
        }.getCallTarget();
    }
    private static Object call(String backend, PolyglotOp op, Object... arguments) {
        Object[] supplied = Arrays.copyOf(arguments, arguments.length + 1);
        supplied[arguments.length] = Unit.INSTANCE;
        return Calls.target(target(backend, op), supplied);
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void bulkCopiesUseForeignBuffersWithoutNativeAddresses(String backend) throws Exception {
        for (String policy : List.of("heap", "native")) try (var context = Context.newBuilder("thc", "js")
                .allowExperimentalOptions(true).allowNativeAccess(true).option("thc.ByteArrayStorage", policy).build()) {
            context.initialize("thc"); context.enter();
            try {
                var bytes = new byte[]{1,2,3,4,5,6,7,8};
                var foreign = new ForeignBytes(bytes, false);
                var value = new ForeignValue(Language.currentState(), foreign);
                assertFalse(InteropLibrary.getUncached().isPointer(foreign));
                assertEquals(8L, call(backend, PolyglotOp.BUFFER_SIZE, value));
                assertEquals(0x0807060504030201L, call(backend, PolyglotOp.BUFFER_READ_LONG, value, 0L, 0L));
                assertEquals(0x0102030405060708L, call(backend, PolyglotOp.BUFFER_READ_LONG, value, 1L, 0L));
                Object copy = call(backend, PolyglotOp.BUFFER_COPY, value, 2L, 4L);
                assertEquals(4, ManagedByteArray.sizeGuest(copy)); assertEquals(1, foreign.bulkReads);
                for (int i = 0; i < 4; i++) assertEquals(i + 3, ManagedByteArray.readGuest(copy, i, true));
                if (policy.equals("native")) {
                    var allocation = assertInstanceOf(ManagedAllocation.class, copy);
                    assertTrue(allocation.hasNativeStorage()); assertFalse(allocation.isPinned());
                    assertEquals(allocation.nativeSegment().address(), ManagedAddress.fromGuestByteArray(copy).toNativeBits());
                } else assertInstanceOf(byte[].class, copy);
                assertThrows(RuntimeFault.class, ManagedAddress.fromGuestByteArray(bytes)::toNativeBits);
                bytes[2] = 99; assertEquals(3, ManagedByteArray.readGuest(copy, 0, true));
                assertSame(copy, ManagedByteArray.freezeGuest(copy));
                ManagedByteArray.writeGuest(ManagedByteArray.freezeGuest(copy), 0, 91);
                assertEquals(91, ManagedByteArray.readGuest(copy, 0, true)); assertEquals(99, bytes[2]);
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_COPY, value, Long.MAX_VALUE, 1L));
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_COPY, value, 0L, -1L));
                assertEquals(0, ManagedByteArray.sizeGuest(call(backend, PolyglotOp.BUFFER_COPY, value, 8L, 0L)));
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_WRITE_BYTE, value, 0L, 1L));
                assertEquals(1, bytes[0]);
                var destination = ManagedAllocation.mutable(8, 8, true);
                call(backend, PolyglotOp.BUFFER_COPY_INTO, value, 0L, destination, 2L, 4L);
                assertEquals(3, foreign.bulkReads); assertEquals(99, destination.readByteInt(4));
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_COPY_INTO, value, 0L, destination, 7L, 2L));
                assertEquals(3, foreign.bulkReads, "invalid destination is rejected before foreign effects");
                var copied = ManagedAllocation.mutable(16, 8);
                call(backend, PolyglotOp.BUFFER_COPY_INTO, value, 0L, copied, 0L, 4L);
                var pointer = ManagedAddress.fromByteArray(new byte[1]);
                copied.writeAddressByteOffset(8, pointer);
                assertSame(pointer, copied.readAddressByteOffset(8), "copy validation must not expose the allocation");
                int reads = foreign.bulkReads;
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_COPY_INTO, value, 0L, copied, 8L, 4L));
                assertEquals(reads, foreign.bulkReads, "pointer-cell overlap is rejected before foreign reads");
                byte[] untouched = {9,9,9,9};
                var failing = new ForeignBytes(new byte[]{1,2,3,4}, false);
                failing.afterRead = () -> { throw new IllegalStateException("foreign bulk read failed"); };
                assertThrows(IllegalStateException.class, () -> call(backend, PolyglotOp.BUFFER_COPY_INTO,
                    new ForeignValue(Language.currentState(), failing), 0L, untouched, 0L, 4L));
                assertArrayEquals(new byte[]{9,9,9,9}, untouched, "failed foreign reads do not partially mutate the destination");
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void explicitMutableViewsAliasWhileCopiesAndReadOnlyViewsDoNotGrantWrites(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = ManagedAllocation.mutable(16, 8);
                var mutable = (ForeignValue) call(backend, PolyglotOp.BUFFER_MUTABLE_VIEW, owner);
                var readonly = (ForeignValue) call(backend, PolyglotOp.BUFFER_VIEW, owner);
                call(backend, PolyglotOp.BUFFER_WRITE_LONG, mutable, 1L, 1L, 0x0102030405060708L);
                assertEquals(1, owner.readByteInt(1)); assertEquals(8, owner.readByteInt(8));
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_WRITE_BYTE, readonly, 0L, 1L));
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_WRITE_BYTE, mutable, 0L, 256L));
                call(backend, PolyglotOp.BUFFER_COPY_INTO, mutable, 1L, owner, 2L, 8L);
                for (int i = 0; i < 8; i++) assertEquals(i + 1, owner.readByteInt(i + 2));
                owner.shrink(4); assertEquals(4L, call(backend, PolyglotOp.BUFFER_SIZE, mutable));
                var nativeView = context.asValue(mutable.getReceiver());
                assertFalse(nativeView.isNativePointer()); assertTrue(nativeView.isBufferWritable());
                assertThrows(RuntimeFault.class, () -> owner.writeAddressByteOffset(0, ManagedAddress.nullAddress()));
                var pointerCells = ManagedAllocation.mutable(16, 8, true);
                pointerCells.writeAddressByteOffset(0, ManagedAddress.nullAddress());
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.BUFFER_VIEW, pointerCells));
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void javascriptArrayBuffersCopyWithoutNativePointerConversion(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState();
                Object receiver = owner.getEnv().asGuestValue(context.eval("js",
                    "globalThis.thcBytes = new Uint8Array([11,22,33,44]); thcBytes.buffer"));
                var value = new ForeignValue(owner, receiver);
                assertTrue(InteropLibrary.getUncached().hasBufferElements(receiver));
                assertFalse(InteropLibrary.getUncached().isPointer(receiver));
                assertArrayEquals(new byte[]{22,33}, (byte[]) call(backend, PolyglotOp.BUFFER_COPY, value, 1L, 2L));
                call(backend, PolyglotOp.BUFFER_WRITE_BYTE, value, 1L, 77L);
                assertEquals(77, context.eval("js", "thcBytes[1]").asInt());
                var destination = new byte[4];
                call(backend, PolyglotOp.BUFFER_COPY_INTO, value, 0L, destination, 0L, 4L);
                assertArrayEquals(new byte[]{11,77,33,44}, destination);
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void realForeignArraysAreCopiedAsOwnedOpaqueValues(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState();
                Object receiver = owner.getEnv().asGuestValue(context.eval("js", "[17, { answer: 42 }]"));
                var foreign = new ForeignValue(owner, receiver);
                assertEquals(2L, call(backend, PolyglotOp.ARRAY_SIZE, foreign));
                var copied = (Object[]) call(backend, PolyglotOp.ARRAY_COPY, foreign);
                assertTrue(ManagedArray.isFrozen(copied));
                assertEquals(17, InteropLibrary.getUncached().asInt(((ForeignValue) copied[0]).getReceiver()));
                var replacement = new ForeignValue(owner, 23);
                call(backend, PolyglotOp.ARRAY_WRITE, foreign, 0L, replacement);
                assertEquals(17, InteropLibrary.getUncached().asInt(((ForeignValue) copied[0]).getReceiver()));
                assertEquals(23, InteropLibrary.getUncached().asInt(((ForeignValue) call(backend, PolyglotOp.ARRAY_READ, foreign, 0L)).getReceiver()));
                var function = new ForeignValue(owner, owner.getEnv().asGuestValue(context.eval("js", "(array => array[0])")));
                assertEquals(23, InteropLibrary.getUncached().asInt(((ForeignValue) call(backend, PolyglotOp.EXECUTE_VALUE, function, foreign)).getReceiver()));
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void fixedGuestArraysRejectInsertionForeignObjectsAndCrossContextUse(String backend) throws Exception {
        ForeignValue escaped;
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState();
                var array = ManagedSmallArray.allocate(2, new Object());
                escaped = (ForeignValue) call(backend, PolyglotOp.ARRAY_MUTABLE_VIEW, array);
                var element = call(backend, PolyglotOp.ARRAY_READ, escaped, 0L);
                call(backend, PolyglotOp.ARRAY_WRITE, escaped, 1L, element);
                assertSame(ManagedSmallArray.read(array, 0), ManagedSmallArray.read(array, 1));
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.ARRAY_WRITE, escaped, 0L, new ForeignValue(owner, 42)));
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.ARRAY_WRITE, escaped, 2L, element));
                array.shrink(1); assertEquals(1L, call(backend, PolyglotOp.ARRAY_SIZE, escaped));
                ManagedSmallArray.freeze(array);
                assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.ARRAY_WRITE, escaped, 0L, element));
                try (var second = context()) {
                    second.initialize("thc"); second.enter();
                    try { assertThrows(RuntimeFault.class, () -> call(backend, PolyglotOp.ARRAY_SIZE, escaped)); }
                    finally { second.leave(); }
                }
            } finally { context.leave(); }
        }
    }
}
