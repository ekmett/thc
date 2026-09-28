// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;
import com.oracle.truffle.api.nodes.Node;
import java.util.LinkedHashMap;
import java.nio.ByteOrder;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original GHC wrappers: raw references, narrow results and completed state effects. */
@Tag("foreign-exceptions-full-core")
class RawInteropTest {
    @ExportLibrary(InteropLibrary.class)
    static final class Bytes implements TruffleObject {
        final byte[] bytes = {3,4,5,6,7,8,9,10,11,12,13,14,15};
        final CbitsBuffer buffer = new CbitsBuffer(bytes, true);
        int reads, compiledReads, writes;
        Runnable afterRead, afterWrite;
        @ExportMessage boolean hasBufferElements() { return true; }
        @ExportMessage boolean isBufferWritable() { return true; }
        @ExportMessage long getBufferSize(@Cached(value = "create()", uncached = "create()", neverDefault = true) Size size) { return size.execute(bytes.length); }
        @ExportMessage byte readBufferByte(long offset) throws InvalidBufferOffsetException {
            if (offset < 0 || offset >= bytes.length) throw InvalidBufferOffsetException.create(offset, 1);
            reads++; if (CompilerDirectives.inCompiledCode()) compiledReads++;
            if (afterRead != null) afterRead.run();
            return bytes[(int) offset];
        }
        @ExportMessage void writeBufferByte(long offset, byte value) throws InvalidBufferOffsetException {
            if (offset < 0 || offset >= bytes.length) throw InvalidBufferOffsetException.create(offset, 1);
            writes++; bytes[(int) offset] = value; if (afterWrite != null) afterWrite.run();
        }
        @ExportMessage short readBufferShort(ByteOrder order, long offset) throws InvalidBufferOffsetException { return buffer.readBufferShort(order, offset); }
        @ExportMessage int readBufferInt(ByteOrder order, long offset) throws InvalidBufferOffsetException { return buffer.readBufferInt(order, offset); }
        @ExportMessage long readBufferLong(ByteOrder order, long offset) throws InvalidBufferOffsetException { return buffer.readBufferLong(order, offset); }
        @ExportMessage float readBufferFloat(ByteOrder order, long offset) throws InvalidBufferOffsetException { return buffer.readBufferFloat(order, offset); }
        @ExportMessage double readBufferDouble(ByteOrder order, long offset) throws InvalidBufferOffsetException { return buffer.readBufferDouble(order, offset); }
        @ExportMessage void readBuffer(long offset, byte[] destination, int start, int length) throws InvalidBufferOffsetException { buffer.readBuffer(offset, destination, start, length); }
        @ExportMessage void writeBufferShort(ByteOrder order, long offset, short value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer.writeBufferShort(order, offset, value); }
        @ExportMessage void writeBufferInt(ByteOrder order, long offset, int value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer.writeBufferInt(order, offset, value); }
        @ExportMessage void writeBufferLong(ByteOrder order, long offset, long value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer.writeBufferLong(order, offset, value); }
        @ExportMessage void writeBufferFloat(ByteOrder order, long offset, float value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer.writeBufferFloat(order, offset, value); }
        @ExportMessage void writeBufferDouble(ByteOrder order, long offset, double value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer.writeBufferDouble(order, offset, value); }
    }
    static final class Size extends Node {
        static Size create() { return new Size(); }
        long execute(int value) { return value; }
    }
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true).allowNativeAccess(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private static ExecutableProgram program(String backend, String name, boolean async) throws Exception {
        var source = CoreModules.reachable(ForeignExceptionFixtureSupport.source("post"), "main:InteropPrimitives." + name, true);
        var module = new LinkedHashMap<>(source); module.put("instrument", true);
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        return backend.equals("ast") ? new Program(language, module, async) : new BytecodeProgram(language, module, async);
    }
    private static RootCallTarget target(ExecutableProgram program, String name) {
        // GHC aliases readOne/writeOne/getLibrary to primitive wrapper closures.
        // Resolve the alias only; do not execute a foreign operation to find its target.
        Object value = Calls.target(program.hostEntryTarget(0),
            new Object[]{program.entryValue("main:InteropPrimitives." + name), new Object[0]});
        var closure = assertInstanceOf(Closure.class, value);
        assertEquals(0, closure.suppliedCount);
        return closure.target;
    }
    private static Object result(RootCallTarget target, Object answer) {
        var shape = ((GuestRoot) target.getRootNode()).getTupleResult();
        if (shape == null) return answer;
        var owned = TupleResults.ownedTupleResult(answer, shape);
        if (shape.getLayout().isInt(0)) return shape.getLayout().getInt(owned, 0);
        return shape.getLayout().getLong(owned, 0);
    }
    private static void compile(Context context, ExecutableProgram program, String name, int arity) {
        assertTrue(context.asValue(new EntryValue(program, "main:InteropPrimitives." + name, arity)).invokeMember("compile").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalRawLoopUsesTheAcquiredDispatcherForCompiledReads(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, false, null);
            try {
                var bytes = new Bytes(); var program = program(backend, "sumBytes", false); var target = target(program, "sumBytes");
                assertEquals(3L, result(target, Calls.target(target, new Object[]{0L, bytes, 1L, Unit.INSTANCE})));
                compile(context, program, "sumBytes", 3);
                assertEquals(117L, result(target, Calls.target(target, new Object[]{0L, bytes, 13L, Unit.INSTANCE})));
                assertEquals(14, bytes.reads); assertEquals(13, bytes.compiledReads);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalStateOnlyWriteCommitsOnceBeforeAsynchronousDelivery(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                var bytes = new Bytes();
                var getter = target(program(backend, "getLibrary", true), "getLibrary");
                var library = (InteropLibrary) Calls.target(getter, new Object[]{0L, bytes});
                var program = program(backend, "writeOne", true); var target = target(program, "writeOne");
                assertSame(Unit.INSTANCE, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, bytes, library, 0L, 99, Unit.INSTANCE}));
                compile(context, program, "writeOne", 5);
                var request = new AsyncRequest[1];
                bytes.afterWrite = () -> request[0] = owner.getThreads().send(owner.getThreads().currentIdentity(), "completed raw write");
                Object answer = ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, bytes, library, 0L, -7, Unit.INSTANCE});
                var saved = SavedGuestContinuations.savedGuestContinuation(answer);
                assertNotNull(saved); assertSame(request[0], saved.asyncRequest());
                assertEquals(2, bytes.writes); assertEquals((byte) -7, bytes.bytes[0]);
                request[0].acknowledge();
                assertSame(Unit.INSTANCE, saved.continueWith(Unit.INSTANCE));
                assertEquals(2, bytes.writes, "A completed foreign store must not replay");
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalNarrowReadCommitsItsResultBeforeAsynchronousDelivery(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                var bytes = new Bytes(); bytes.bytes[1] = -7;
                var getter = target(program(backend, "getLibrary", true), "getLibrary");
                var library = (InteropLibrary) Calls.target(getter, new Object[]{0L, bytes});
                var program = program(backend, "readOne", true); var target = target(program, "readOne");
                assertEquals(3, result(target, Calls.target(target, new Object[]{0L, bytes, library, 0L, Unit.INSTANCE})));
                compile(context, program, "readOne", 4);
                var request = new AsyncRequest[1];
                bytes.afterRead = () -> request[0] = owner.getThreads().send(owner.getThreads().currentIdentity(), "completed narrow read");
                Object answer = Calls.target(target, new Object[]{0L, bytes, library, 1L, Unit.INSTANCE});
                var saved = SavedGuestContinuations.savedGuestContinuation(answer);
                assertNotNull(saved); assertSame(request[0], saved.asyncRequest());
                assertEquals(2, bytes.reads);
                request[0].acknowledge();
                assertEquals(-7, result(target, saved.continueWith(Unit.INSTANCE)));
                assertEquals(2, bytes.reads, "A completed foreign read must not replay");
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
}
