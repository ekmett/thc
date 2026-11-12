// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import thc.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OperandRetentionTest {
    @Test void primitiveLoweringKeepsTypedOperandsOnFirstCompiledEntryAtDefaultBudget() throws Exception {
        var scalar = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        for (String name : List.of("eqChar#", "+#", "negateInt64#")) {
            var operandRep = Map.of("kind", "long", "primReps", List.of(name.equals("eqChar#") ? "WordRep" : name.equals("negateInt64#") ? "Int64Rep" : "IntRep"), "evaluated", true);
            var resultRep = name.equals("negateInt64#") ? operandRep : scalar;
            var literal = List.of("lit", name.equals("eqChar#") ? "char" : name.equals("negateInt64#") ? "int64" : "int", "21", Map.of("rep", operandRep));
            var operands = name.equals("negateInt64#") ? List.of(literal) : List.of(literal, literal);
            var call = List.of("app", List.of("prim", name), operands,
                name.equals("negateInt64#") ? List.of(false) : List.of(false, false), false, false, Map.of("rep", resultRep));
            var module = Map.<String, Object>of("instrument", true, "constructors", List.of(), "bindings", List.of(
                Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true,
                    "expr", List.of("lam", List.of(Map.of("id", "unused", "lifted", false, "rep", scalar)),
                        call, Map.of("resultRep", resultRep)))));
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("compiler.MaximumGraalGraphSize", "1000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = new Program(language, module, true, false);
                    var target = program.entryTarget("entry");
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), name);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(name.equals("eqChar#") ? 1L : name.equals("+#") ? 42L : -21L,
                        assertInstanceOf(Long.class, Calls.target(target, new Object[]{0L, 0L})));
                    assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                } finally { context.leave(); }
            }
        }
    }
    private static final class PrimitiveRoot extends GuestRoot {
        @Child private Primitive body;
        final boolean generic;
        final SourceSection source;
        PrimitiveRoot(Language language, String name, boolean generic, Expr... operands) {
            super(language, new FrameLayout().build());
            body = new Primitive(name, operands); this.generic = generic; source = operands[0].getSourceSection();
        }
        @Override public SourceSection getSourceSection() { return source; }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            try { return generic ? body.execute(frame) : body.executeLong(frame); }
            catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
        }
    }
    @Test void primitiveOperandsResumeSuccessiveCutsWithoutReplayingSourceEvents() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var attachment = context.getEngine().getInstruments().get("thc-activation-test").lookup(AstInstrumentationTest.Attachment.class);
                for (boolean generic : List.of(false, true)) {
                    var order = new ArrayList<String>(); var events = new ArrayList<String>();
                    var source = Source.newBuilder("thc", "x+y", "primitive-operands.hs").build();
                    var first = new IndexOperand(19L, order, "first", true, false);
                    var second = new IndexOperand(23L, order, "second", true, false);
                    first.setCoreSourceLocation(new CoreSourceLocation(source.createSection(0, 1), List.of()));
                    second.setCoreSourceLocation(new CoreSourceLocation(source.createSection(2, 1), List.of()));
                    var root = new PrimitiveRoot(language, "+#", generic, first, second);
                    var binding = attachment.attachStatements(source, new ExecutionEventListener() {
                        @Override public void onEnter(EventContext event, VirtualFrame frame) { events.add(event.getInstrumentedSourceSection().getCharacters().toString()); }
                        @Override public void onReturnValue(EventContext event, VirtualFrame frame, Object value) {}
                        @Override public void onReturnExceptional(EventContext event, VirtualFrame frame, Throwable failure) {}
                    });
                    try {
                        var saved = assertInstanceOf(AstContinuation.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                        assertEquals(List.of("first"), order);
                        var remaining = assertInstanceOf(AstContinuation.class, saved.continueWith(19L));
                        assertEquals(List.of("first", "second"), order);
                        assertEquals(42L, assertInstanceOf(Long.class, remaining.continueWith(23L)));
                        assertEquals(List.of("first", "second"), order);
                        assertEquals(List.of("x", "y"), events);
                        assertThrows(RuntimeFault.class, () -> remaining.continueWith(23L));
                    } finally { binding.dispose(); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void primitiveDelimitedRecipesKeepSavedPrefixAndReadRemainingHeapOnEachInvocation() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var order = new ArrayList<String>();
                var heap = ManagedAddress.fromByteArray(new byte[]{21});
                Expr remaining = new Expr() { @Override public Object execute(VirtualFrame frame) { order.add("heap"); return heap.readWord8(0); } };
                var firstRoot = new PrimitiveRoot(language, "+#", false,
                    new IndexOperand(21L, order, "first", false, true), remaining);
                var firstCut = assertThrows(DelimitedCut.class, () -> Calls.target(firstRoot.getCallTarget(), new Object[]{0L}));
                assertEquals(1, firstCut.getFrames().size());
                var firstRecipe = firstCut.getFrames().getFirst().getStep();
                for (int value : new int[]{21, 22}) {
                    heap.writeWord8(0, value);
                    var copy = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, firstRoot.getFrameDescriptor());
                    assertEquals(21L + value, firstRecipe.resume(copy, new DelimitedResume(21L), MaskingState.UNMASKED, null));
                }
                assertEquals(List.of("first", "heap", "heap"), order);
                order.clear();
                var prefix = new IndexOperand(19L, order, "first", false, false);
                var secondRoot = new PrimitiveRoot(language, "+#", true, prefix, new IndexOperand(23L, order, "second", false, true));
                var secondCut = assertThrows(DelimitedCut.class, () -> Calls.target(secondRoot.getCallTarget(), new Object[]{0L}));
                assertEquals(1, secondCut.getFrames().size());
                var secondRecipe = secondCut.getFrames().getFirst().getStep();
                prefix.value = 100L;
                for (long value : new long[]{23, 24}) {
                    var copy = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, secondRoot.getFrameDescriptor());
                    assertEquals(19L + value, secondRecipe.resume(copy, new DelimitedResume(value), MaskingState.UNMASKED, null));
                }
                assertEquals(List.of("first", "second"), order);
                var wrong = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, secondRoot.getFrameDescriptor());
                assertThrows(RuntimeFault.class, () -> secondRecipe.resume(wrong, new DelimitedResume(23), MaskingState.UNMASKED, null));
            } finally { context.leave(); }
        }
    }
    @Test void primitiveUnaryAndExceptionalSuffixKeepRequiredLongCarrier() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var order = new ArrayList<String>();
                var unary = new PrimitiveRoot(language, "negateInt64#", true, new IndexOperand(21L, order, "unary", true, false));
                var saved = assertInstanceOf(AstContinuation.class, Calls.target(unary.getCallTarget(), new Object[]{0L}));
                assertEquals(-21L, saved.continueWith(21L));
                var wrong = assertInstanceOf(AstContinuation.class, Calls.target(unary.getCallTarget(), new Object[]{0L}));
                assertThrows(RuntimeFault.class, () -> wrong.continueWith(21));
                var divide = new PrimitiveRoot(language, "quotInt#", false,
                    new IndexOperand(42L, order, "dividend", false, false), new IndexOperand(0L, order, "divisor", true, false));
                var dividing = assertInstanceOf(AstContinuation.class, Calls.target(divide.getCallTarget(), new Object[]{0L}));
                assertThrows(ArithmeticException.class, () -> dividing.continueWith(0L));
                assertEquals(List.of("unary", "unary", "dividend", "divisor"), order);
            } finally { context.leave(); }
        }
    }
    @Test void scalarIndexLoweringKeepsStaticAddressOnFirstCompiledEntryAtDefaultBudget() throws Exception {
        var address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
        var index = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        for (String name : List.of("indexCharOffAddr#", "indexWord16OffAddr#", "indexInt16OffAddr#")) {
            String carrier = name.equals("indexCharOffAddr#") ? "WordRep" : name.equals("indexWord16OffAddr#") ? "Word16Rep" : "Int16Rep";
            var result = Map.of("kind", "long", "primReps", List.of(carrier), "evaluated", true);
            var read = List.of("app", List.of("prim", name),
                List.of(List.of("lit", "string-bytes", "ffff", Map.of("rep", address)),
                    List.of("lit", "int", "0", Map.of("rep", index))),
                List.of(false, false), false, false, Map.of("rep", result));
            var module = Map.<String, Object>of("instrument", true, "constructors", List.of(), "bindings", List.of(
                Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true,
                    "expr", List.of("lam", List.of(Map.of("id", "unused", "lifted", false, "rep", index)),
                        read, Map.of("resultRep", result)))));
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("compiler.MaximumGraalGraphSize", "1000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = new Program(language, module, true, false);
                    var target = program.entryTarget("entry");
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), name);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    Object answer = Calls.target(target, new Object[]{0L, 0L});
                    if (name.equals("indexCharOffAddr#")) assertEquals(255L, assertInstanceOf(Long.class, answer));
                    else assertEquals(name.equals("indexInt16OffAddr#") ? -1 : 65535, assertInstanceOf(Integer.class, answer));
                    assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                } finally { context.leave(); }
            }
        }
    }
    private static final class IndexOperand extends Expr {
        Object value;
        final List<String> order;
        final String name;
        final boolean cut, delimited;
        IndexOperand(Object value, List<String> order, String name, boolean cut, boolean delimited) {
            this.value = value; this.order = order; this.name = name; this.cut = cut; this.delimited = delimited;
        }
        @Override public Object execute(VirtualFrame frame) {
            order.add(name);
            if (delimited) throw new DelimitedCut(new PromptTag(Language.currentState()), null, null,
                SynchronousMasking.current(this), this);
            if (cut) throw new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this));
            return value;
        }
    }
    private static final class IndexRoot extends GuestRoot {
        @Child private IndexManagedScalarAddress body;
        private final boolean integer;
        private final SourceSection source;
        IndexRoot(Language language, ManagedAddressRead operation, Expr address, Expr element, boolean integer) {
            super(language, new FrameLayout().build());
            this.body = new IndexManagedScalarAddress(operation, address, element); this.integer = integer;
            source = address.getSourceSection();
        }
        @Override public SourceSection getSourceSection() { return source; }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            try { if (integer) return body.executeInt(frame); return body.executeLong(frame); }
            catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
        }
    }
    @Test void scalarIndexSavesOnlyRemainingOperandsAcrossSuccessiveCuts() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var attachment = context.getEngine().getInstruments().get("thc-activation-test")
                    .lookup(AstInstrumentationTest.Attachment.class);
                for (var operation : List.of(ManagedAddressRead.CHAR, ManagedAddressRead.WORD16, ManagedAddressRead.INT16)) {
                    var order = new ArrayList<String>();
                    var events = new ArrayList<String>();
                    var source = Source.newBuilder("thc", "a+i", "scalar-index-operands.hs").build();
                    var bytes = ManagedAddress.fromByteArray(new byte[]{(byte) 0xff, (byte) 0xff});
                    var address = new IndexOperand(bytes, order, "address", true, false);
                    var element = new IndexOperand(0L, order, "element", true, false);
                    address.setCoreSourceLocation(new CoreSourceLocation(source.createSection(0, 1), List.of()));
                    element.setCoreSourceLocation(new CoreSourceLocation(source.createSection(2, 1), List.of()));
                    var root = new IndexRoot(language, operation, address, element, operation.isInt());
                    var binding = attachment.attachStatements(source, new ExecutionEventListener() {
                        @Override public void onEnter(EventContext event, VirtualFrame frame) {
                            events.add(event.getInstrumentedSourceSection().getCharacters().toString());
                        }
                        @Override public void onReturnValue(EventContext event, VirtualFrame frame, Object value) {}
                        @Override public void onReturnExceptional(EventContext event, VirtualFrame frame, Throwable failure) {}
                    });
                    try {
                        var first = assertInstanceOf(AstContinuation.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                        assertEquals(List.of("address"), order);
                        var saved = assertInstanceOf(AstContinuation.class, first.continueWith(bytes));
                        assertEquals(List.of("address", "element"), order);
                        Object answer = saved.continueWith(0L);
                        if (operation.isInt()) assertEquals(operation == ManagedAddressRead.INT16 ? -1 : 65535, assertInstanceOf(Integer.class, answer));
                        else assertEquals(255L, assertInstanceOf(Long.class, answer));
                        assertEquals(List.of("address", "element"), order, "Neither completed operand is reevaluated");
                        assertEquals(List.of("a", "i"), events, "Original child statement events retain order across both cuts");
                        assertThrows(RuntimeFault.class, () -> saved.continueWith(0L));
                    } finally { binding.dispose(); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void scalarIndexDelimitedRecipeKeepsSharedAddressAndExactEnteredCarrier() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var order = new ArrayList<String>();
                var bytes = ManagedAddress.fromByteArray(new byte[]{41, 42});
                var address = new IndexOperand(bytes, order, "address", false, false);
                var element = new IndexOperand(0L, order, "element", false, true);
                var root = new IndexRoot(language, ManagedAddressRead.CHAR, address, element, false);
                var cut = assertThrows(DelimitedCut.class, () -> Calls.target(root.getCallTarget(), new Object[]{0L}));
                assertEquals(List.of("address", "element"), order);
                assertEquals(1, cut.getFrames().size());
                var recipe = cut.getFrames().getFirst().getStep();
                for (long index : new long[]{0, 1}) {
                    var copy = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, root.getFrameDescriptor());
                    Object answer = recipe.resume(copy, new DelimitedResume(index), MaskingState.UNMASKED, null);
                    assertEquals(index == 0 ? 41L : 73L, assertInstanceOf(Long.class, answer));
                    bytes.writeWord8(1, 73);
                }
                assertEquals(List.of("address", "element"), order, "Repeated invocations share heap values without replaying operands");
                var wrong = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, root.getFrameDescriptor());
                assertThrows(RuntimeFault.class, () -> recipe.resume(wrong, new DelimitedResume(0), MaskingState.UNMASKED, null));
                order.clear();
                var addressRoot = new IndexRoot(language, ManagedAddressRead.CHAR,
                    new IndexOperand(bytes, order, "address", false, true),
                    new IndexOperand(0L, order, "element", false, false), false);
                var addressCut = assertThrows(DelimitedCut.class, () -> Calls.target(addressRoot.getCallTarget(), new Object[]{0L}));
                assertEquals(List.of("address"), order);
                var addressRecipe = addressCut.getFrames().getFirst().getStep();
                for (int invocation = 0; invocation < 2; invocation++) {
                    var copy = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, addressRoot.getFrameDescriptor());
                    assertEquals(41L, addressRecipe.resume(copy, new DelimitedResume(bytes), MaskingState.UNMASKED, null));
                }
                assertEquals(List.of("address", "element", "element"), order,
                    "Only the remaining element executes for each captured-address invocation");
            } finally { context.leave(); }
        }
    }
    @Test void scalarIndexRejectsWrongResumedOperandsAndPreservesEnteredRoute() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var order = new ArrayList<String>();
                var bytes = ManagedAddress.fromByteArray(new byte[]{1});
                var address = new IndexOperand(bytes, order, "address", true, false);
                var element = new IndexOperand(0L, order, "element", false, false);
                var root = new IndexRoot(language, ManagedAddressRead.CHAR, address, element, false);
                var saved = assertInstanceOf(AstContinuation.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                assertTrue(assertThrows(RuntimeFault.class, () -> saved.continueWith(1L)).getMessage().contains("Addr#"));
                assertEquals(List.of("address"), order, "Invalid resumed address never evaluates the element");
                assertThrows(RuntimeFault.class, () -> saved.continueWith(bytes));
                order.clear();
                var wrongRoute = new IndexRoot(language, ManagedAddressRead.CHAR,
                    new IndexOperand(bytes, order, "address", false, false),
                    new IndexOperand(0L, order, "element", true, false), true);
                var wrong = assertInstanceOf(AstContinuation.class, Calls.target(wrongRoute.getCallTarget(), new Object[]{0L}));
                assertTrue(assertThrows(RuntimeFault.class, () -> wrong.continueWith(0L)).getMessage().contains("narrow address read"));
                assertEquals(List.of("address", "element"), order);
                assertThrows(RuntimeFault.class, () -> wrong.continueWith(0L));
            } finally { context.leave(); }
        }
    }
    private static VirtualFrame frame(int slots) {
        var builder = FrameDescriptor.newBuilder();
        for (int i = 0; i < slots; i++) builder.addSlot(FrameSlotKind.Illegal, "slot " + i, null);
        return Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
    }
    @FunctionalInterface private interface Action { Object run(VirtualFrame frame) throws Exception; }
    private static Expr expression(Action action) {
        return new Expr() {
            @Override public Object execute(VirtualFrame frame) {
                try { return action.run(frame); }
                catch (Exception failure) { return OperandRetentionTest.<RuntimeException, Object>rethrow(failure); }
            }
        };
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
    private final CoreRepresentation integral = new CoreRepresentation(CoreKind.LONG, true, false, null, null, null, null, null, null);
    private static void assertCleared(VirtualFrame frame, int[] slots) throws Exception {
        // Public reads reject a cleared (Illegal) slot. Inspect the pinned
        // frame's reference storage to verify release without GC timing.
        var field = frame.getClass().getDeclaredField("indexedLocals"); field.setAccessible(true);
        var references = (Object[]) field.get(frame);
        for (int slot : slots) {
            assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot));
            assertNull(references[slot], "Completed transfer must release scratch slot " + slot);
        }
    }
    private static List<FrameSlotKind> kinds(VirtualFrame frame, int[] slots) {
        var kinds = new ArrayList<FrameSlotKind>(slots.length);
        for (int slot : slots) kinds.add(frame.getFrameDescriptor().getSlotKind(slot));
        return kinds;
    }
    @Test void selfTransferReleasesEveryOperandIncludingUnusedFormalsWithoutWideningSlots() throws Exception {
        var frame = frame(7);
        var first = new Object(); var second = new Object(); var unused = new Object();
        int[] temporaries = {3, 4, 5, 6};
        var unknown = CoreRepresentation.UNKNOWN;
        var layout = new AstSelfLayout(null, new int[0], new int[]{0, 1, 2, -1},
            new CoreRepresentation[]{unknown, unknown, integral, unknown}, new boolean[4]);
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Self transfer must not call its target"); }
        }.getCallTarget();
        var function = new Closure(null, 4, target);
        FrameAccess.write(frame, 0, first); FrameAccess.write(frame, 1, second);
        FrameAccess.writeLong(frame, 2, Long.MIN_VALUE);
        for (long value : new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 3_000_000_017L}) {
            var oldFirst = FrameAccess.read(frame, 0); var oldSecond = FrameAccess.read(frame, 1);
            FrameAccess.write(frame, 3, oldSecond); FrameAccess.write(frame, 4, oldFirst);
            FrameAccess.writeLong(frame, 5, value); FrameAccess.write(frame, 6, unused);
            var kinds = kinds(frame, temporaries);
            assertSame(AstSelfCall.INSTANCE, assertThrows(AstSelfCall.class, () -> layout.transfer(frame, function, temporaries)));
            assertSame(oldSecond, FrameAccess.read(frame, 0)); assertSame(oldFirst, FrameAccess.read(frame, 1));
            assertEquals(value, frame.getLong(2)); assertCleared(frame, temporaries);
            assertEquals(kinds, kinds(frame, temporaries));
            assertEquals(FrameSlotKind.Long, frame.getFrameDescriptor().getSlotKind(5));
        }
    }
    @Test void joinTransferReleasesCompletedOperandsButDoesNotMoveOrClearOnOperandFailure() throws Exception {
        var frame = frame(6); var first = new Object(); var second = new Object();
        var failure = new RuntimeFault("join operand failure");
        boolean[] fail = {false};
        var order = new ArrayList<Integer>();
        var unknown = CoreRepresentation.UNKNOWN;
        var target = new LocalJoinTarget(new Object(), 0, new int[]{0, 1, 2}, new CoreRepresentation[]{unknown, unknown, integral});
        int[] temporaries = {3, 4, 5};
        var metrics = new Metrics(true);
        var call = new LocalJoinCall(new thc.Language(), target, new Expr[]{
            expression(f -> { order.add(0); return FrameAccess.read(f, 1); }),
            expression(f -> { order.add(1); return FrameAccess.read(f, 0); }),
            expression(f -> { order.add(2); if (fail[0]) throw failure; return f.getLong(2) + 1; })}, temporaries, metrics);
        FrameAccess.write(frame, 0, first); FrameAccess.write(frame, 1, second);
        FrameAccess.writeLong(frame, 2, Long.MAX_VALUE);
        assertSame(target.getJump(), assertThrows(LocalJoinJump.class, () -> call.execute(frame)));
        assertEquals(List.of(0, 1, 2), order);
        assertSame(second, FrameAccess.read(frame, 0)); assertSame(first, FrameAccess.read(frame, 1));
        assertEquals(Long.MIN_VALUE, frame.getLong(2)); assertCleared(frame, temporaries);
        assertEquals(FrameSlotKind.Long, frame.getFrameDescriptor().getSlotKind(5));
        assertEquals(1L, metrics.getLocalJoinTransfers());
        fail[0] = true; order.clear();
        assertSame(failure, assertThrows(RuntimeFault.class, () -> call.execute(frame)));
        assertEquals(List.of(0, 1, 2), order);
        assertSame(second, FrameAccess.read(frame, 0), "No destination changes before all operands finish");
        assertSame(first, FrameAccess.read(frame, 1)); assertEquals(Long.MIN_VALUE, frame.getLong(2));
        assertSame(first, FrameAccess.read(frame, 3), "An incomplete transfer has not cleared its operands");
        assertSame(second, FrameAccess.read(frame, 4)); assertEquals(1L, metrics.getLocalJoinTransfers());
    }
}
