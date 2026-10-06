// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@SuppressWarnings("unchecked")
class DelimitedContinuationsTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> entries = List.of("promptPure", "abortSuffix", "resumeTwice", "nestedPrompts", "sameTagNearest", "capturedCatch", "capturedMask", "escapedResume", "ambientMask", "resumedTail", "resumedJoin", "resumedScalar", "recapturedMask", "resumedApplication", "resumedScalarApplication", "polymorphicApplications", "polymorphicScalarApplications");
    private List<Long> provenance() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(root, "build/delimited-continuations/manifest.json").toPath())); assertEquals(entries, manifest.get("entries"));
        for (String group : List.of("inputHashes", "artifactHashes")) for (var row : ((Map<?, ?>) manifest.get(group)).entrySet()) { byte[] bytes = Files.readAllBytes(new File(root, (String) row.getKey()).toPath()); assertEquals(row.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), (String) row.getKey()); }
        var nativeResults = new ArrayList<Long>(); for (var value : (List<?>) manifest.get("native")) nativeResults.add(((Number) value).longValue()); var model = new ArrayList<Long>(); for (long n : new long[]{-2, 0, 7}) for (String entry : entries) model.add(expected(entry, n)); assertEquals(model, nativeResults, "Independent arithmetic/state model agrees with actual native GHC"); return nativeResults;
    }
    private long expected(String entry, long n) { return switch (entry) {
        case "promptPure" -> n + 7; case "abortSuffix" -> n; case "resumeTwice" -> ((n + 1) * 100 + (n + 4)) * 10 + 3; case "nestedPrompts" -> n + 111; case "sameTagNearest" -> n + 100; case "capturedCatch" -> n + 17; case "capturedMask" -> n + 21; case "escapedResume" -> 2 * n + 14; case "ambientMask" -> n; case "resumedTail", "resumedJoin", "resumedScalar", "resumedApplication", "resumedScalarApplication" -> n + 117; case "polymorphicApplications", "polymorphicScalarApplications" -> 4 * n + 174; case "recapturedMask" -> n + 1; default -> throw new IllegalStateException(entry);
    }; }
    @ParameterizedTest @ValueSource(strings = {"direct", "generic", "tuple", "direct-tuple"})
    void freshCaptureInLaterOverapplicationKeepsRemainingArguments(String route) {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(false);
                var number = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
                var shape = new TupleShape(tuple(number), language);
                var layout = new FrameLayout(); int[] slots = {layout.bind("result", FrameSlotKind.Long)};
                boolean tupleResult = route.contains("tuple");
                int[] effects = new int[4];
                var middle = new GuestRoot(language, new FrameLayout().build()) {
                    { configureEntry(new boolean[]{false}, false); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        assertEquals(2L, frame.getArguments()[1]); effects[2]++;
                        throw new DelimitedCut(new PromptTag(Language.currentState()), null, null,
                            SynchronousMasking.current(this), this);
                    }
                };
                var first = new GuestRoot(language, new FrameLayout().build()) {
                    { configureEntry(new boolean[]{false}, false); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        assertEquals(1L, frame.getArguments()[1]); effects[1]++;
                        return new Closure(null, 1, middle.getCallTarget());
                    }
                };
                var closure = new Closure(null, 1, first.getCallTarget());
                var body = new Expr() {
                    @Child private Dispatch direct = Dispatch.create(3, false, metrics);
                    @Child private GenericDispatch generic = GenericDispatchNodeGen.create();
                    @Child private GenericTupleCaller tuples = new GenericTupleCaller(new AstTupleDestination(shape, slots, 0), metrics, false, 3, null);
                    @Child private TupleDispatch directTuples = new TupleDispatch(new AstTupleDestination(shape, slots, 0), metrics, 3, false);
                    { setRepresentation(tupleResult ? shape.getProof() : CoreRepresentation.UNKNOWN); }
                    private Object apply(VirtualFrame frame) {
                        assertTrue(AstControl.captures(this));
                        Object[] arguments = {1L, 2L, 37L};
                        if (tupleResult) {
                            if (route.equals("direct-tuple")) directTuples.execute(frame, closure, arguments);
                            else tuples.execute(frame, closure, arguments);
                            return null;
                        }
                        return route.equals("direct") ? direct.execute(frame, closure, arguments) :
                            generic.execute(frame, this, closure, arguments, 3, null, false, metrics, 0);
                    }
                    @Override public Object execute(VirtualFrame frame) {
                        effects[0]++;
                        throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> {
                            assertSame(Unit.INSTANCE, input); return apply(saved);
                        });
                    }
                    @Override public Object executeTuple(VirtualFrame frame, int[] destination, int offset) { return execute(frame); }
                };
                var function = new FunctionRoot(language, layout.build(), "parked overapplication", null, new int[0], new int[0], new int[0],
                    body, metrics, new CoreRepresentation[0], body.getRepresentation(), null, new boolean[0], null,
                    tupleResult ? shape : null, tupleResult ? slots : new int[0], null, true, new int[0][], true, FunctionRootRole.FUNCTION, false);
                var cut = assertThrows(DelimitedCut.class, () -> function.getCallTarget().call(0L));
                var image = new DelimitedStack(cut, tupleResult ? shape : null);
                class Owner extends GuestRoot {
                    @Child private DelimitedActionSite site = new DelimitedActionSite(language, metrics);
                    Owner() { super(language, new FrameLayout().build()); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) { return Unit.INSTANCE; }
                    Object resume(long add) {
                        var finalCallee = new GuestRoot(language, new FrameLayout().build()) {
                            { configureEntry(new boolean[]{false}, false); if (tupleResult) configureTupleResult(shape); }
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) {
                                effects[3]++; assertEquals(37L, frame.getArguments()[1]);
                                long result = (Long) frame.getArguments()[1] + add;
                                if (!tupleResult) return result;
                                var value = shape.getLayout().create(); shape.getLayout().setLong(value, 0, result); return value;
                            }
                        };
                        var action = new GuestRoot(language, new FrameLayout().build()) {
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) { return new Closure(null, 1, finalCallee.getCallTarget()); }
                        };
                        Object answer = image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()),
                            new Closure(null, 1, action.getCallTarget()));
                        return tupleResult ? shape.getLayout().getLong((HandoffStorage) answer, 0) : answer;
                    }
                }
                var owner = new Owner(); owner.getCallTarget();
                assertEquals(42L, owner.resume(5L)); assertEquals(50L, owner.resume(13L));
                assertArrayEquals(new int[]{1, 1, 1, 2}, effects);
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reentrantImageInvocationsOwnTheirPendingApplicationArguments(boolean tupleResult) {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var number = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
                var shape = new TupleShape(tuple(number), language);
                var layout = new FrameLayout(); int[] destination = {layout.bind("result", FrameSlotKind.Long)};
                int[] effects = new int[3]; boolean[] nested = {false}; long[] nestedResult = {0};
                class Root extends GuestRoot {
                    @Child private Expr application;
                    @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                    DelimitedStack image;
                    Root() {
                        super(language, layout.build());
                        var target = new GuestRoot(language, new FrameLayout().build()) {
                            { configureEntry(new boolean[]{false, false}, false); if (tupleResult) configureTupleResult(shape); }
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) {
                                effects[2]++;
                                long result = (Long) frame.getArguments()[1] * 100 + (Long) frame.getArguments()[2];
                                if (!tupleResult) return result;
                                var carrier = shape.getLayout().create(); shape.getLayout().setLong(carrier, 0, result); return carrier;
                            }
                        };
                        var fn = new Expr() { @Override public Object execute(VirtualFrame frame) { return new Closure(null, 2, target.getCallTarget()); } };
                        var first = new Expr() {
                            { setRepresentation(number); }
                            @Override public Object execute(VirtualFrame frame) {
                                effects[0]++;
                                throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> {
                                    throw new DelimitedCut(new PromptTag(Language.currentState()), null, null, SynchronousMasking.current(this), this);
                                });
                            }
                        };
                        var second = new Expr() {
                            { setRepresentation(number); }
                            @Override public Object execute(VirtualFrame frame) {
                                effects[1]++;
                                if (nested[0]) return 2L;
                                nested[0] = true;
                                try { nestedResult[0] = (Long) resume(20L); }
                                finally { nested[0] = false; }
                                return 1L;
                            }
                        };
                        application = tupleResult ? new TupleApplication(language, shape, fn, new Expr[]{first, second}, false, new Metrics(false)) :
                            new Application(fn, new Expr[]{first, second}, false, new Metrics(false));
                    }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        try {
                            if (!tupleResult) return application.execute(frame);
                            try { application.executeTuple(frame, destination, 0); }
                            catch (AstCapture cut) { throw cut.append((saved, input) -> saved.getLong(destination[0])); }
                            return frame.getLong(destination[0]);
                        } catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
                    }
                    Object resume(long value) {
                        var action = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) {
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) { return value; }
                        }.getCallTarget());
                        return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), action);
                    }
                }
                var root = new Root(); var saved = (AstContinuation) root.getCallTarget().call(0L);
                root.image = new DelimitedStack(assertThrows(DelimitedCut.class, () -> saved.continueWith(Unit.INSTANCE)), null);
                assertEquals(1001L, root.resume(10L)); assertEquals(2002L, nestedResult[0]);
                assertEquals(3001L, root.resume(30L)); assertEquals(2002L, nestedResult[0]);
                assertArrayEquals(new int[]{1, 4, 4}, effects);
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"operands", "typed", "let", "join"})
    void freshCaptureDuringResumedOperandPreparationKeepsTheInterruptedWrite(String route) {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var number = new CoreRepresentation(CoreKind.DOUBLE, true, true, List.of("DoubleRep"), null, null, null, null, null);
                var layout = new FrameLayout();
                int[] effects = new int[4];
                Expr[] arguments = new Expr[3];
                for (int i = 0; i < arguments.length; i++) {
                    final int index = i;
                    arguments[i] = new Expr() {
                        { setRepresentation(number); }
                        @Override public Object execute(VirtualFrame frame) {
                            effects[index]++;
                            if (index == 0) throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this))
                                .append((saved, input) -> 10.0);
                            if (index == 1) throw new DelimitedCut(new PromptTag(Language.currentState()), null, null,
                                SynchronousMasking.current(this), this);
                            return 30.0;
                        }
                    };
                }
                boolean typed = route.equals("typed");
                AstInputOperands inputs = typed ? new AstInputOperands(arguments, layout) : null;
                int[] slots = typed ? inputs.getSource().getSlots() : new int[]{layout.bind("first", FrameSlotKind.Double),
                    layout.bind("second", FrameSlotKind.Double), layout.bind("third", FrameSlotKind.Double)};
                Expr body = new Expr() {
                    @Override public Object execute(VirtualFrame frame) {
                        effects[3]++;
                        return frame.getDouble(slots[0]) + frame.getDouble(slots[1]) + frame.getDouble(slots[2]);
                    }
                };
                Expr selected;
                if (typed) selected = body;
                else if (route.equals("let")) selected = new Let(slots, arguments, new boolean[3], body, false);
                else if (route.equals("join")) {
                    Object group = new Object();
                    int[] temporaries = {layout.bind("pending first", FrameSlotKind.Double), layout.bind("pending second", FrameSlotKind.Double),
                        layout.bind("pending third", FrameSlotKind.Double)};
                    var target = new LocalJoinTarget(group, 1, slots, new CoreRepresentation[]{number, number, number});
                    var call = new LocalJoinCall(language, target, arguments, temporaries, new Metrics(false));
                    selected = new LocalJoinRegion(group, layout.bind("selector"), layout.bind("join result"),
                        new Expr[]{call, body}, number, false, null, new int[0], true);
                } else selected = new AstOperands(new LocalBinding[]{new LocalBinding(slots[0], arguments[0], false),
                    new LocalBinding(slots[1], arguments[1], false), new LocalBinding(slots[2], arguments[2], false)}, slots, body);
                class Root extends GuestRoot {
                    @Child private AstInputOperands operands = inputs;
                    @Child private Expr expression = selected;
                    @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                    Root() { super(language, layout.build()); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        try {
                            if (typed) try { operands.evaluate(frame); }
                            catch (AstCapture cut) { throw cut.append((saved, input) -> expression.execute(saved)); }
                            return expression.execute(frame);
                        } catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
                    }
                    Object resume(DelimitedStack image, double replacement) {
                        var action = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) {
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) { return replacement; }
                        }.getCallTarget());
                        return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), action);
                    }
                }
                var root = new Root();
                var saved = (AstContinuation) root.getCallTarget().call(0L);
                var cut = assertThrows(DelimitedCut.class, () -> saved.continueWith(Unit.INSTANCE));
                var image = new DelimitedStack(cut, null);
                assertEquals(60.0, root.resume(image, 20.0));
                assertEquals(70.0, root.resume(image, 30.0));
                assertArrayEquals(new int[]{1, 1, 2, 2}, effects);
                assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    @Test void freshCaptureDuringResumedKeepAliveStateRetainsItsAction() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Object kept = new Object(); int[] effects = new int[3];
                class Root extends GuestRoot {
                    @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                    @Child private Expr expression = new KeepAliveExpression(
                        new Expr() { @Override public Object execute(VirtualFrame frame) {
                            effects[0]++; throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> kept);
                        } },
                        new Expr() { @Override public Object execute(VirtualFrame frame) {
                            effects[1]++; throw new DelimitedCut(new PromptTag(Language.currentState()), null, null, SynchronousMasking.current(this), this);
                        } },
                        new Expr() { @Override public Object execute(VirtualFrame frame) { effects[2]++; return 42L; } }, CoreRepresentation.UNKNOWN);
                    Root() { super(language, new FrameLayout().build()); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        try { return expression.execute(frame); }
                        catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
                    }
                    Object resume(DelimitedStack image) {
                        var action = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) {
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) { return Unit.INSTANCE; }
                        }.getCallTarget());
                        return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), action);
                    }
                }
                var root = new Root(); var saved = (AstContinuation) root.getCallTarget().call(0L);
                var image = new DelimitedStack(assertThrows(DelimitedCut.class, () -> saved.continueWith(Unit.INSTANCE)), null);
                assertEquals(42L, root.resume(image)); assertEquals(42L, root.resume(image));
                assertArrayEquals(new int[]{1, 1, 2}, effects);
                assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
            } finally { context.leave(); }
        }
    }
    @Test void freshCaptureFindsPromptInsideSavedSuffixAndRebasesItsScopes() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null);
                var number = new CoreRepresentation(CoreKind.LONG, true, false, List.of("IntRep"), null, null, null, null, null);
                var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, List.of("IntRep"), List.of(state, number), null, null, null, null), language);
                var owners = new ArrayList<AstContinuation>();
                int[] effects = new int[4];
                Object inner = new Object(), ambient = new Object();
                class Root extends GuestRoot {
                    @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                    final PromptTag tag = new PromptTag(Language.currentState());
                    final Closure handler;
                    Root() {
                        super(language, FrameDescriptor.newBuilder().build());
                        configureEntry(new boolean[]{false}, false); configureTupleResult(shape);
                        handler = new Closure(null, 2, new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) {
                                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(this));
                                assertEquals(List.of(ambient), StackAnnotations.current(this).values());
                                Object k = frame.getArguments()[1];
                                long first = unpack(site.invoke(frame, k, new Object[]{action(11), Unit.INSTANCE}, shape));
                                long second = unpack(site.invoke(frame, k, new Object[]{action(23), Unit.INSTANCE}, shape));
                                return pack(first + second);
                            }
                        }.getCallTarget());
                    }
                    Object pack(long value) { var result = shape.getLayout().create(); shape.getLayout().setLong(result, 0, value); return result; }
                    long unpack(Object value) { return shape.getLayout().getLong((HandoffStorage) value, 0); }
                    Closure action(long value) {
                        return new Closure(null, 1, new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                            @Override public long bloom(VirtualFrame frame) { return 0; }
                            @Override public Object execute(VirtualFrame frame) { return pack(value); }
                        }.getCallTarget());
                    }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame ignored) {
                        var builder = FrameDescriptor.newBuilder(); builder.addSlot(FrameSlotKind.Long, "local", null);
                        var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, builder.build());
                        frame.setLong(0, 7);
                        MaskingState prior = SynchronousMasking.current(this);
                        StackAnnotationState annotations = StackAnnotations.enter(this, inner);
                        SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE);
                        try {
                            AstContinuation leaf = new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this))
                                .append((saved, input) -> { effects[0]++; throw new DelimitedCut(tag, handler, shape, SynchronousMasking.current(this), this); })
                                .freeze(this, frame);
                            owners.add(leaf);
                            try { return AstControl.completeCallback(this, leaf, null, shape, false); }
                            catch (AstCapture cut) {
                                cut.append((saved, input) -> {
                                    effects[1]++;
                                    assertEquals(7, saved.getLong(0), "Each invocation owns a fresh control slot");
                                    saved.setLong(0, 99);
                                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this));
                                    assertEquals(List.of(inner, ambient), StackAnnotations.current(this).values());
                                    return pack(unpack(input) + 7);
                                });
                                cut.enclose(steps -> new AstAnnotationScope(this, annotations, steps));
                                cut.enclose(steps -> new AstMaskScope(this, prior, steps));
                                AstContinuation parent = cut.freeze(this, frame); owners.add(parent); return parent;
                            }
                        } finally { SynchronousMasking.set(this, prior); StackAnnotations.set(this, annotations); }
                    }
                    DelimitedStack image() {
                        var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor());
                        var cut = new DelimitedCut(new PromptTag(Language.currentState()), null, shape, MaskingState.UNMASKED, this);
                        cut.append(frame, (saved, input, mask, outer) -> { effects[2]++; return pack(unpack(input.get()) + 10); });
                        cut.append(frame, new DelimitedPromptStep(tag, site, shape));
                        cut.append(frame, (saved, input, mask, outer) -> { effects[3]++; return pack(unpack(input.get()) + 100); });
                        return new DelimitedStack(cut, shape);
                    }
                    long run(DelimitedStack image) {
                        var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor());
                        return unpack(image.resume(site, frame, new Closure(null, 1, getCallTarget())));
                    }
                }
                var root = new Root(); var image = root.image();
                var priorAnnotations = StackAnnotations.enter(root, ambient);
                try {
                    assertEquals(168, root.run(image)); assertEquals(168, root.run(image));
                    assertArrayEquals(new int[]{2,4,4,2}, effects, "Capture prefix once; each inner suffix twice; outer prompt suffix once");
                    for (var owner : owners) assertThrows(RuntimeFault.class, () -> owner.continueWith(Unit.INSTANCE));
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root));
                    assertEquals(List.of(ambient), StackAnnotations.current(root).values());
                    assertEquals(0, AstStacks.astStackScope(root).getDepth());
                } finally { StackAnnotations.set(root, priorAnnotations); }
            } finally { context.leave(); }
        }
    }
    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @CsvSource({"0,11023000000,0,false", "96,107119096192,1,false", "96,107119096192,1,true"})
    void freshCaptureCopiesEveryRecursiveSuffix(long depth, long expected, int nativeRow, boolean prepared) throws Exception {
        Path evidence = Path.of(System.getProperty("thc.projectRoot"), "build/delimited-continuations/parked");
        provenance();
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(root, "build/delimited-continuations/manifest.json").toPath()));
        assertEquals(expected, ((Number) ((List<?>) manifest.get("parkedNative")).get(nativeRow)).longValue());
        var module = CoreCbdFixtures.read(evidence.resolve("core/ParkedControl.cbd"));
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var selected = CoreModules.reachable(module, "main:ParkedControl.observe");
                var program = prepared ? Program.prepareCode(language, selected, List.of("main:ParkedControl.observe")).newInstance(language)
                    : new Program(language, selected, true);
                var function = context.asValue(new EntryValue(program, "main:ParkedControl.observe", 1));
                assertEquals(expected, function.execute(depth).asLong());
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(null));
                var handoff = language.getHandoffState().get();
                assertEquals(0, handoff.getArguments().getDepth());
                assertEquals(0, handoff.getResults().getDepth());
                assertNull(handoff.getPending());
            } finally { context.leave(); }
        }
    }
    @Test void originalCoreRunsSavedSuffixesOnBothBackends() throws Exception { runEntries(entries, true); }
    @Test void unsplitFourTargetApplicationsExerciseBothGenericResultPaths() throws Exception { runEntries(List.of("polymorphicApplications", "polymorphicScalarApplications"), false); }
    private void runEntries(List<String> selected, boolean splitting) throws Exception {
        var nativeResults = provenance();
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) for (String entry : selected) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.WarnInterpreterOnly", "false").option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.Splitting", Boolean.toString(splitting)).option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter(); try {
                var core = new File(root, "build/delimited-continuations/" + stage + "/core");
                var module = CoreCbdFixtures.read(new File(core, "DelimitedContinuations.cbd").toPath());
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var identifier = "main:DelimitedContinuations." + entry;
                var linked = CoreModules.reachable(module, identifier);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); assertTrue(((GuestRoot) program.entryTarget(identifier).getRootNode()).getDelimitedControlEnabled(), stage + "/" + backend + "/" + entry + ": root policy is prepared before the first guest call"); var function = context.asValue(new EntryValue(program, identifier, 1)); long[] inputs = {-2, 0, 7};
                for (int index = 0; index < inputs.length; index++) { long n = inputs[index]; assertEquals(nativeResults.get(index * entries.size() + entries.indexOf(entry)), function.execute(n).asLong(), stage + "/" + backend + "/" + entry + "/" + n); var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences()); assertNull(handoff.getPending()); assertEquals(MaskingState.UNMASKED, Language.currentState(null).getMaskingState().get()); }
                // Include all reachable global functions: a megamorphic call may
                // no longer retain every target in a DirectCallNode. Discover
                // the actual targets without predicting their number or shape.
                var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
                var targets = new ArrayList<RootCallTarget>();
                for (var value : (List<?>) linked.get("bindings")) {
                    var binding = (Map<?, ?>) value;
                    if (Objects.equals(((List<?>) binding.get("expr")).getFirst(), "lam"))
                        for (var target : ThreadInventoryCoreEvidence.targets(program.entryTarget((String) binding.get("id"))))
                            if ((target.getRootNode() instanceof FunctionRoot || target.getRootNode() instanceof BytecodeRoot) && seen.add(target))
                                targets.add(target);
                }
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var interpreted = ThreadInventoryCoreEvidence.interpretedCalls(targets); try { ThreadInventoryCoreEvidence.install(targets); } catch (Throwable failure) { throw new AssertionError(stage + "/" + backend + "/" + entry + " first installation", failure); } assertTrue(function.invokeMember("compile").asBoolean()); assertEquals(before, ((Number) program.diagnostics().get("compiledEntries")).longValue()); var handoff = language.getHandoffState().get(); var allocations = List.of(handoff.getArguments().getAllocations(), handoff.getResults().getAllocations()); assertEquals(expected(entry, 11), function.execute(11L).asLong(), stage + "/" + backend + "/" + entry + " first installed call"); assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "First installed call enters compiled code"); assertEquals(interpreted, ThreadInventoryCoreEvidence.interpretedCalls(targets), "No interpreted settling call"); ThreadInventoryCoreEvidence.released(language); assertEquals(allocations, List.of(handoff.getArguments().getAllocations(), handoff.getResults().getAllocations())); assertEquals(MaskingState.UNMASKED, Language.currentState(null).getMaskingState().get());
            } finally { context.leave(); }
        }
    }
    @Test void rootPoliciesArePreparedWithoutExecutingBodiesAndRetainedByClones() {
        try (var context = Context.newBuilder("thc").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for (boolean enabled : new boolean[]{false, true}) {
                var body = new Expr() { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Metadata preparation executed guest code"); } }; var source = new FunctionRoot(language, new FrameLayout().build(), "unexecuted policy", null, new int[0], new int[0], new int[0], body, new Metrics(false), new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, false, new int[0][], enabled, FunctionRootRole.FUNCTION, false); var clone = NodeUtil.cloneNode(source);
                for (var root : List.of(source, clone)) { assertSame(root, root.getCallTarget().getRootNode()); assertEquals(enabled, root.getDelimitedControlEnabled()); assertEquals(enabled, DelimitedControl.enabled(root)); }
            }
            var other = new GuestRoot(language, FrameDescriptor.newBuilder().build()) { @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Metadata preparation executed guest code"); } }; other.getCallTarget(); assertFalse(DelimitedControl.enabled(other), "Unrecognized roots do not gain continuation authority"); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var value = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(), List.of(state, value), null, null, null, null), language);
            var cut = new DelimitedCut(new PromptTag(Language.currentState(null)), null, shape, MaskingState.UNMASKED, other); var continuation = (GuestRoot) new DelimitedStack(cut, shape).closure(language, new Metrics(false)).target.getRootNode(); assertTrue(continuation.getDelimitedControlEnabled()); assertTrue(DelimitedControl.enabled(continuation)); ThreadInventoryCoreEvidence.released(language);
        } finally { context.leave(); } }
    }
    @Test void liveAndSavedCatchAcknowledgeEachSelfRequestAndKeepItsPayloadLazy() {
        try (var context = Context.newBuilder("thc").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var threads = Language.currentState().getThreads(); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var value = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(), List.of(state, value), null, null, null, null), language); var payload = new Thunk(new GuestRoot(language, new FrameLayout().build()) { @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Delimited catch forced the payload"); } }.getCallTarget(), null); var deliveryTarget = new AtomicReference<GuestThreadId>(); var seen = new AtomicReference<AsyncRequest>();
            class Model { int calls; MaskingState handlerMask = MaskingState.UNMASKED; boolean failHandler, redeliver; } var model = new Model(); var handlerFailure = new RuntimeFault("handler failure");
            var action = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) { { configureEntry(new boolean[]{false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { try { return GuestThreadOps.killSelf(this, deliveryTarget.get(), payload); } catch (AsyncDelivery delivered) { seen.set(delivered.getRequest()); throw delivered; /* Preserve the real async-origin control object. */ } } }.getCallTarget());
            var handler = new Closure(null, 2, new GuestRoot(language, new FrameLayout().build()) {
                { configureEntry(new boolean[]{false, false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { model.calls++; assertSame(payload, frame.getArguments()[1]); assertSame(payload, seen.get().getPayload()); assertEquals(AsyncRequestState.ACKNOWLEDGED, seen.get().getState()); assertEquals(model.handlerMask, SynchronousMasking.current(this)); if (model.failHandler) throw handlerFailure; if (model.redeliver) { model.redeliver = false; try { GuestThreadOps.killSelf(this, deliveryTarget.get(), payload); } catch (AsyncDelivery delivered) { seen.set(delivered.getRequest()); throw delivered; } } var result = shape.getLayout().create(); shape.getLayout().setObject(result, 0, payload); return result; }
            }.getCallTarget());
            class CatchRoot extends GuestRoot {
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                CatchRoot() { super(language, new FrameLayout().build()); } @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { return site.caught(frame, action, handler, thc.runtime.Unit.INSTANCE, shape); }
                DelimitedStack save() { return save(1); }
                DelimitedStack save(int catches) { var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()); var cut = new DelimitedCut(new PromptTag(Language.currentState(this)), null, shape, SynchronousMasking.current(this), this); for (int i = 0; i < catches; i++) cut.getFrames().add(new DelimitedFrame(frame, new DelimitedCatchStep(site, handler, shape))); return new DelimitedStack(cut, shape); }
                Object resumeSaved(DelimitedStack stack) { var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()); return stack.resume(site, frame, action); }
            }
            var root = new CatchRoot(); threads.enterCurrent(null, false, false, null);
            try {
                var self = threads.currentIdentity(); deliveryTarget.set(self);
                for (var mask : MaskingState.values()) { SynchronousMasking.set(root, mask); model.handlerMask = mask == MaskingState.UNMASKED ? MaskingState.MASKED_INTERRUPTIBLE : mask; var result = (HandoffStorage) Calls.target(root.getCallTarget(), new Object[]{0L}); assertSame(payload, shape.getLayout().getObject(result, 0)); assertTrue(seen.get().getForceSelf()); assertEquals(self.getLogicalId(), seen.get().getTargetId()); assertEquals(mask, SynchronousMasking.current(root)); assertNull(threads.poll(root, false)); assertEquals(0, payload.getState()); }
                assertEquals(3, model.calls, "Exactly one handler invocation per self request"); SynchronousMasking.set(root, MaskingState.UNMASKED); model.handlerMask = MaskingState.MASKED_INTERRUPTIBLE; model.failHandler = true; assertSame(handlerFailure, assertThrows(RuntimeFault.class, () -> Calls.target(root.getCallTarget(), new Object[]{0L}))); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root)); assertEquals(4, model.calls);
                // Reuse the SAME immutable image, never the one-shot request.
                model.failHandler = false; var saved = root.save(); var previous = seen.get();
                for (var mask : MaskingState.values()) { SynchronousMasking.set(root, mask); model.handlerMask = mask == MaskingState.UNMASKED ? MaskingState.MASKED_INTERRUPTIBLE : mask; for (int i = 0; i < 2; i++) { var result = (HandoffStorage) root.resumeSaved(saved); assertSame(payload, shape.getLayout().getObject(result, 0)); assertNotSame(previous, seen.get(), "Every self send owns a fresh request"); assertEquals(AsyncRequestState.ACKNOWLEDGED, previous.getState()); assertEquals(AsyncRequestState.ACKNOWLEDGED, seen.get().getState()); assertEquals(self.getLogicalId(), seen.get().getTargetId()); assertTrue(seen.get().getForceSelf()); assertEquals(mask, SynchronousMasking.current(root)); assertNull(threads.poll(root, false)); assertEquals(0, payload.getState()); previous = seen.get(); } } assertEquals(10, model.calls);
                // The inner saved handler acknowledges its request before
                // throwing a new self request to the outer saved handler.
                SynchronousMasking.set(root, MaskingState.UNMASKED); model.handlerMask = MaskingState.MASKED_INTERRUPTIBLE; model.redeliver = true; assertSame(payload, shape.getLayout().getObject((HandoffStorage) root.resumeSaved(root.save(2)), 0)); assertEquals(12, model.calls); assertEquals(AsyncRequestState.ACKNOWLEDGED, seen.get().getState()); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root));
                // With no saved catch, preserve the original delivery for an
                // outer handler; neither the image nor its runner may ACK it.
                SynchronousMasking.set(root, MaskingState.MASKED_UNINTERRUPTIBLE); var escaped = assertThrows(AsyncDelivery.class, () -> root.resumeSaved(root.save(0))); assertSame(seen.get(), escaped.getRequest()); assertTrue(escaped.getRequest().getForceSelf()); assertEquals(AsyncRequestState.CLAIMED, escaped.getRequest().getState()); assertEquals(12, model.calls, "Uncaught delivery must not become GuestException"); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(root)); escaped.getRequest().acknowledge(); SynchronousMasking.set(root, MaskingState.UNMASKED);
                // The same carrier may host a distinct callback identity.
                // Its external send must not enter the caller's mailbox or handler.
                var foreign = threads.enterForeign(ForeignSafety.SAFE); try { threads.enterCurrent(null, false, false, null); try { assertNotSame(self, threads.currentIdentity()); assertThrows(UnsupportedCore.class, () -> Calls.target(root.getCallTarget(), new Object[]{0L})); assertEquals(12, model.calls); } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); } } finally { threads.leaveForeign(foreign); }
                assertSame(self, threads.currentIdentity()); assertNull(threads.poll(root, false), "Rejected external send never entered the mailbox"); assertEquals(0, payload.getState()); ThreadInventoryCoreEvidence.released(language);
            } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        } finally { context.leave(); } }
    }
    private static void joinSender(Thread sender) {
        try { sender.join(5000); } catch (InterruptedException failure) { DelimitedContinuationsTest.<RuntimeException>rethrow(failure); }
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void rethrow(Throwable failure) throws T { throw (T) failure; }
    @Test void savedExternalDeliveryKeepsTheAbandonedChildOneShotAndTheImageReusable() {
        try (var context = Context.create("thc")) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var threads = Language.currentState().getThreads(); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var value = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(), List.of(state, value), null, null, null, null), language); var payload = new Object(); var requests = new ArrayList<AsyncRequest>(); var children = new ArrayList<AstContinuation>(); int[] resumedChildren = {0}, handled = {0};
            var action = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) {
                { configureEntry(new boolean[]{false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { long target = threads.currentId(); var submitted = new AtomicReference<AsyncRequest>(); var sender = new Thread(() -> submitted.set(threads.send(target, payload))); sender.start(); joinSender(sender); assertFalse(sender.isAlive()); var request = Objects.requireNonNull(threads.poll(this, true)); assertSame(submitted.get(), request); assertFalse(request.getForceSelf()); requests.add(request);
                    var child = new AstCapture(request, SynchronousMasking.current(this)).append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { assertSame(thc.runtime.Unit.INSTANCE, input); resumedChildren[0]++; return thc.runtime.Unit.INSTANCE; } }).freeze(this, frame.materialize()); children.add(child); return child;
                }
            }.getCallTarget());
            var handler = new Closure(null, 2, new GuestRoot(language, new FrameLayout().build()) {
                { configureEntry(new boolean[]{false, false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { handled[0]++; assertSame(payload, frame.getArguments()[1]); assertEquals(AsyncRequestState.ACKNOWLEDGED, requests.getLast().getState()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this)); var result = shape.getLayout().create(); shape.getLayout().setObject(result, 0, payload); return result; }
            }.getCallTarget());
            class ImageRoot extends GuestRoot {
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                ImageRoot() { super(language, new FrameLayout().build()); } @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("model root is not an action"); }
                DelimitedStack image() { return image(true); }
                DelimitedStack image(boolean caught) { var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()); var cut = new DelimitedCut(new PromptTag(Language.currentState(this)), null, shape, MaskingState.UNMASKED, this); if (caught) cut.getFrames().add(new DelimitedFrame(frame, new DelimitedCatchStep(site, handler, shape))); return new DelimitedStack(cut, shape); }
                Object resume(DelimitedStack image) { return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), action); }
                Object deliver(AsyncRequest request) { return site.handleException(Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), handler, new AsyncDelivery(request, this), shape); }
            }
            var root = new ImageRoot(); threads.enterCurrent(null, false, true, null);
            try {
                var image = root.image(); for (int i = 0; i < 2; i++) { var result = (HandoffStorage) root.resume(image); assertSame(payload, shape.getLayout().getObject(result, 0)); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root)); }
                assertEquals(2, handled[0]); assertNotSame(requests.get(0), requests.get(1)); assertNotSame(children.get(0), children.get(1)); assertEquals(0, resumedChildren[0], "Catch abandons each interrupted child, not the reusable image"); assertThrows(IllegalStateException.class, () -> root.deliver(requests.get(0))); Calls.target(action.target, new Object[]{0L, thc.runtime.Unit.INSTANCE}); var pending = requests.getLast(); var foreign = threads.enterForeign(ForeignSafety.SAFE);
                try { threads.enterCurrent(null, false, true, null); try { assertNotEquals(pending.getTargetId(), threads.currentId()); assertThrows(IllegalStateException.class, () -> root.deliver(pending)); assertEquals(AsyncRequestState.CLAIMED, pending.getState()); } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); } } finally { threads.leaveForeign(foreign); }
                root.deliver(pending); assertEquals(AsyncRequestState.ACKNOWLEDGED, pending.getState()); var escaped = assertThrows(AsyncDelivery.class, () -> root.resume(root.image(false))); assertSame(requests.getLast(), escaped.getRequest()); assertEquals(AsyncRequestState.CLAIMED, escaped.getRequest().getState()); assertEquals(3, handled[0], "Only a reached catch acknowledges the original delivery"); escaped.getRequest().acknowledge();
                for (var child : children) { assertSame(thc.runtime.Unit.INSTANCE, child.continueWith(thc.runtime.Unit.INSTANCE)); assertThrows(RuntimeFault.class, () -> child.continueWith(thc.runtime.Unit.INSTANCE)); } assertEquals(4, resumedChildren[0], "An independent owner may resume each child once only"); assertEquals(3, handled[0]); assertNull(threads.poll(root, false));
            } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        } finally { context.leave(); } }
    }
    @Test void eachImageInvocationDrainsItsOwnCutsAndNeverStoresTheirOwners() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var threads = Language.currentState().getThreads(); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var value = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(), List.of(state, value), null, null, null, null), language); var payload = new Object(); var children = new ArrayList<AstContinuation>(); var requests = new ArrayList<AsyncRequest>(); int[] effects = {0}, handled = {0}, mode = {0};
            var handler = new Closure(null, 2, new GuestRoot(language, new FrameLayout().build()) {
                { configureEntry(new boolean[]{false, false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { handled[0]++; assertSame(payload, frame.getArguments()[1]); assertEquals(AsyncRequestState.ACKNOWLEDGED, requests.getLast().getState()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this)); var result = shape.getLayout().create(); shape.getLayout().setObject(result, 0, payload); return result; }
            }.getCallTarget());
            class ImageRoot extends GuestRoot {
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                ImageRoot() { super(language, new FrameLayout().build()); configureEntry(new boolean[]{false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; }
                AstContinuation cut(VirtualFrame frame, Object marker, java.util.function.Supplier<Object> next) { var child = new AstCapture(marker, SynchronousMasking.current(this)).append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { assertSame(thc.runtime.Unit.INSTANCE, input); effects[0]++; return next.get(); } }).freeze(this, frame.materialize()); children.add(child); return child; }
                @Override public Object execute(VirtualFrame frame) { return cut(frame, AstStackSpill.INSTANCE, () -> switch (mode[0]) {
                    case 0 -> cut(frame, thc.runtime.Unit.INSTANCE, () -> { var result = shape.getLayout().create(); shape.getLayout().setObject(result, 0, payload); return result; });
                    case 1 -> { var submitted = new AtomicReference<AsyncRequest>(); long target = threads.currentId(); var sender = new Thread(() -> submitted.set(threads.send(target, payload))); sender.start(); joinSender(sender); assertFalse(sender.isAlive()); var request = Objects.requireNonNull(threads.poll(this, true)); assertSame(submitted.get(), request); requests.add(request); yield cut(frame, request, () -> { throw new IllegalStateException("A reached catch must not resume its abandoned child"); }); }
                    case 2 -> throw new DelimitedCut(new PromptTag(Language.currentState(this)), null, shape, SynchronousMasking.current(this), this);
                    default -> cut(frame, new DelimitedCut(new PromptTag(Language.currentState(this)), null, shape, SynchronousMasking.current(this), this), () -> { throw new IllegalStateException("A recapture marker is not a Unit cut"); });
                }); }
                DelimitedStack image() {
                    var node = this; var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()); var cut = new DelimitedCut(new PromptTag(Language.currentState(this)), null, shape, MaskingState.UNMASKED, this);
                    cut.getFrames().add(new DelimitedFrame(frame, new DelimitedStep() { @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { var result = input.get(); throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(node)).append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { assertSame(thc.runtime.Unit.INSTANCE, input); effects[0]++; return result; } }); } })); cut.getFrames().add(new DelimitedFrame(frame, new DelimitedCatchStep(site, handler, shape))); return new DelimitedStack(cut, shape);
                }
                Object resume(DelimitedStack image) { return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), new Closure(null, 1, getCallTarget())); }
            }
            var root = new ImageRoot(); threads.enterCurrent(null, false, true, null);
            try {
                var image = root.image(); for (int i = 0; i < 2; i++) { var answer = (HandoffStorage) root.resume(image); assertSame(payload, shape.getLayout().getObject(answer, 0)); } assertEquals(6, effects[0], "Two action cuts and the saved suffix run once per invocation"); assertEquals(4, children.size()); for (var child : children) assertThrows(RuntimeFault.class, () -> child.continueWith(thc.runtime.Unit.INSTANCE)); mode[0] = 1; for (int i = 0; i < 2; i++) assertSame(payload, shape.getLayout().getObject((HandoffStorage) root.resume(image), 0)); assertEquals(8, effects[0], "Delivery must not replay or resume the interrupted action"); assertEquals(2, handled[0]); assertNotSame(requests.get(0), requests.get(1)); boolean allAcknowledged = true; for (var request : requests) if (request.getState() != AsyncRequestState.ACKNOWLEDGED) { allAcknowledged = false; break; } assertTrue(allAcknowledged);
                // A fresh cut propagates to the matching prompt (this deliberately
                // unmatched tag escapes). The already-consumed owner cannot replay.
                mode[0] = 2; assertThrows(DelimitedCut.class, () -> root.resume(image));
                assertThrows(RuntimeFault.class, () -> children.getLast().continueWith(thc.runtime.Unit.INSTANCE));
                // A hand-built yielded cut is not an AST resume point and remains rejected.
                mode[0] = 3; assertEquals("control0# cannot recapture a parked one-shot invocation chain", assertThrows(UnsupportedCore.class, () -> root.resume(image)).getMessage());
                mode[0] = 0; assertSame(payload, shape.getLayout().getObject((HandoffStorage) root.resume(image), 0)); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root)); assertEquals(0, AstStacks.astStackScope(root).getDepth()); assertNull(threads.poll(root, false));
            } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        } finally { context.leave(); } }
    }
    @Test void savedBytecodeSuffixDrainsRepeatedUnitCutsInFreshOwners() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var value = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(), List.of(state, value), null, null, null, null), language); var marker = new Object(); var answer = shape.getLayout().create(); shape.getLayout().setObject(answer, 0, marker);
            class Owner extends GuestRoot {
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                Owner() { super(language, new FrameLayout().build()); configureEntry(new boolean[]{false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { return answer; }
                Object resume(DelimitedStack image) { return resume(image, new Closure(null, 1, getCallTarget())); }
                Object resume(DelimitedStack image, Object action) { return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), action); }
                Object finish(Object result, RootCallTarget target) { return site.finish(result, shape, target); }
            }
            var owner = new Owner(); var cut = new DelimitedCut(new PromptTag(Language.currentState()), null, shape, MaskingState.UNMASKED, owner);
            var bytecode = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> { b.beginRoot(); b.beginYield(); b.emitLoadConstant(cut); b.endYield(); for (int i = 0; i < 2; i++) { b.beginYield(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endYield(); } b.beginReturn(); b.emitLoadConstant(answer); b.endReturn(); b.endRoot(); }).getNode(0); bytecode.configureTupleResult(shape); var saved = (ContinuationResult) Calls.target(bytecode.getCallTarget(), new Object[]{0L}); assertSame(cut, saved.getResult()); cut.append(saved.getFrame(), new DelimitedBytecodeStep(saved, shape)); var image = new DelimitedStack(cut, shape); for (int i = 0; i < 2; i++) assertSame(marker, shape.getLayout().getObject((HandoffStorage) owner.resume(image), 0));
            var closure = new Closure(null, 1, owner.getCallTarget()); var head = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> { b.beginRoot(); for (int i = 0; i < 2; i++) { b.beginYield(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endYield(); } b.beginReturn(); b.emitLoadConstant(closure); b.endReturn(); b.endRoot(); }).getNode(0); var lazyAction = new Thunk(head.getCallTarget(), null);
            for (int i = 0; i < 2; i++) { assertSame(marker, shape.getLayout().getObject((HandoffStorage) owner.resume(image, lazyAction), 0)); assertEquals(2, lazyAction.getState()); assertSame(closure, lazyAction.getValue()); assertNull(lazyAction.getOwner()); }
            var unrelated = saved.continueWith(new DelimitedResume(answer)); assertEquals("Delimited invocation returned an unrelated continuation", assertThrows(RuntimeFault.class, () -> owner.finish(unrelated, owner.getCallTarget())).getMessage()); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(owner)); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        } finally { context.leave(); } }
    }
    @Test void savedScalarJoinTransferRetainsCompletionAfterScheduling() { scheduledJoinTransfer(false, false); }
    @Test void savedTupleJoinTransferRetainsCompletionAfterScheduling() { scheduledJoinTransfer(true, false); }
    @Test void savedScalarJoinTransferRetainsItsNextLexicalJump() { scheduledJoinTransfer(false, true); }
    @Test void savedTupleJoinTransferRetainsItsNextLexicalJump() { scheduledJoinTransfer(true, true); }
    private void scheduledJoinTransfer(boolean tupleResult, boolean nextJump) {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var longRep = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null); var tupleProof = new CoreRepresentation(CoreKind.UNKNOWN, true, true, longRep.getPrimReps(), List.of(longRep), null, null, null, null); var shape = new TupleShape(tupleProof, language); var layout = new FrameLayout(); int selector = layout.bind("join selector"), result = layout.bind("join scalar result"), source = layout.bind("join private tuple"), destination = layout.bind("join caller tuple"); var group = new Object(); var targets = new ArrayList<LocalJoinTarget>(); for (int i = 1; i <= 2; i++) targets.add(new LocalJoinTarget(group, i, new int[0], new CoreRepresentation[0])); var events = new ArrayList<String>();
            var initial = new Expr() {
                @Override public Object execute(VirtualFrame frame) { events.add("capture"); throw new DelimitedCut(new PromptTag(Language.currentState()), null, shape, SynchronousMasking.current(this), this).append(frame, new DelimitedStep() { @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { input.get(); throw targets.get(0).getJump(); } }); }
                @Override public long executeLong(VirtualFrame frame) { return (Long) execute(frame); }
                @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return execute(frame); }
            };
            var scheduled = new Expr() {
                private Object cut(int[] slots, int offset) { events.add("join prefix"); throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { assertSame(thc.runtime.Unit.INSTANCE, input); events.add("join suffix"); if (nextJump) throw targets.get(1).getJump(); if (slots == null) return 42L; FrameAccess.writeLong(frame, slots[offset], 42L); return null; } }); }
                @Override public Object execute(VirtualFrame frame) { return cut(null, 0); }
                @Override public long executeLong(VirtualFrame frame) { return (Long) cut(null, 0); }
                @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return cut(slots, offset); }
            };
            var after = new Expr() { @Override public Object execute(VirtualFrame frame) { return executeLong(frame); } @Override public long executeLong(VirtualFrame frame) { events.add("second join"); return 43L; } @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { FrameAccess.writeLong(frame, slots[offset], executeLong(frame)); return null; } };
            class Owner extends GuestRoot {
                @Child private LocalJoinRegion region = new LocalJoinRegion(group, selector, result, new Expr[]{initial, scheduled, after}, tupleResult ? tupleProof : longRep, nextJump, tupleResult ? shape : null, tupleResult ? new int[]{source} : new int[0], true);
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                Owner() { super(language, layout.build()); } @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { FrameAccess.writeLong(frame, destination, -99L); return tupleResult ? region.executeTuple(frame, new int[]{destination}, 0) : region.execute(frame); }
                Object resume(DelimitedStack image, Closure action) { return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), action); }
            }
            var owner = new Owner(); var cut = assertThrows(DelimitedCut.class, () -> Calls.target(owner.getCallTarget(), new Object[]{0L})); if (tupleResult) cut.getFrames().add(new DelimitedFrame(cut.getFrames().getLast().getFrame(), new DelimitedStep() { @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { input.get(); return frame.getLong(destination); } })); var image = new DelimitedStack(cut, shape);
            var action = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) { { configureEntry(new boolean[]{false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { var result = shape.getLayout().create(); shape.getLayout().setLong(result, 0, 7L); return result; } }.getCallTarget());
            for (int i = 0; i < 2; i++) assertEquals(nextJump ? 43L : 42L, owner.resume(image, action)); var one = new ArrayList<>(List.of("join prefix", "join suffix")); if (nextJump) one.add("second join"); var expected = new ArrayList<>(List.of("capture")); expected.addAll(one); expected.addAll(one); assertEquals(expected, events); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(owner)); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        } finally { context.leave(); } }
    }
    @Test void savedRootTransferDetachesItsTupleBeforeTheNextSavedStep() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var longRep = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null); var proof = new CoreRepresentation(CoreKind.UNKNOWN, true, true, longRep.getPrimReps(), List.of(longRep), null, null, null, null); var shape = new TupleShape(proof, language); var layout = new FrameLayout(); int slot = layout.bind("root tuple result"); int[] effects = {0};
            var body = new Expr() {
                { setRepresentation(proof); } @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("tuple-only model"); }
                @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { effects[0]++; throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { assertSame(thc.runtime.Unit.INSTANCE, input); FrameAccess.writeLong(frame, slots[offset], 42L); return null; } }); }
            };
            var function = new FunctionRoot(language, layout.build(), "saved tuple root", null, new int[0], new int[0], new int[0], body, new Metrics(false), new CoreRepresentation[0], proof, body.getCoreSourceLocation(), new boolean[0], null, shape, new int[]{slot}, null, true, new int[0][], true, FunctionRootRole.FUNCTION, false); function.getCallTarget(); // Adopt the real FunctionBody and its completion step.
            class Owner extends GuestRoot {
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                Owner() { super(language, new FrameLayout().build()); configureEntry(new boolean[]{false}, false); configureTupleResult(shape); } @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { var result = shape.getLayout().create(); shape.getLayout().setLong(result, 0, 7L); return result; }
                Object resume(DelimitedStack image) { return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), new Closure(null, 1, getCallTarget())); }
            }
            var owner = new Owner(); var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, function.getFrameDescriptor()); var cut = new DelimitedCut(new PromptTag(Language.currentState()), null, shape, MaskingState.UNMASKED, owner);
            cut.getFrames().add(new DelimitedFrame(frame, new DelimitedStep() { @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { input.get(); throw AstSelfCall.INSTANCE; } })); cut.getFrames().add(new DelimitedFrame(frame, new DelimitedRootStep(function)));
            cut.getFrames().add(new DelimitedFrame(frame, new DelimitedStep() { @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { var answer = input.get(); assertEquals(0, language.getHandoffState().get().getResults().getDepth(), "The transferred root must detach its tuple before another saved step runs"); FrameAccess.writeLong(frame, slot, 99L); TupleResults.ownedTupleResult(shape.finish(frame, new int[]{slot}), shape); return shape.getLayout().getLong((HandoffStorage) answer, 0); } })); var image = new DelimitedStack(cut, shape);
            for (int i = 0; i < 2; i++) assertEquals(42L, owner.resume(image)); assertEquals(2, effects[0]); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        } finally { context.leave(); } }
    }
    @Test void frameImagesCopyControlLocalsButShareHeapReferences() {
        var builder = FrameDescriptor.newBuilder(); int scalar = builder.addSlot(FrameSlotKind.Long, null, null), reference = builder.addSlot(FrameSlotKind.Object, null, null), floating = builder.addSlot(FrameSlotKind.Double, null, null); var descriptor = builder.build(); int auxiliary = descriptor.findOrAddAuxiliarySlot("continuation-test"); var heap = new ArrayList<>(List.of(1L)); var original = Truffle.getRuntime().createMaterializedFrame(new Object[]{heap, 7L}, descriptor); original.setLong(scalar, Long.MIN_VALUE); original.setObject(reference, heap); original.setDouble(floating, Double.longBitsToDouble(0x7ff8000000000042L)); original.setAuxiliarySlot(auxiliary, heap); var image = DelimitedContinuations.copyContinuationFrame(original); original.setLong(scalar, 11); original.getArguments()[1] = 12L; var first = DelimitedContinuations.copyContinuationFrame(image); var second = DelimitedContinuations.copyContinuationFrame(image); first.setLong(scalar, 13); first.getArguments()[1] = 14L;
        assertEquals(Long.MIN_VALUE, second.getLong(scalar)); assertEquals(7L, second.getArguments()[1]); assertEquals(0x7ff8000000000042L, Double.doubleToRawLongBits(second.getDouble(floating))); assertSame(heap, second.getObject(reference)); assertSame(heap, second.getAuxiliarySlot(auxiliary)); heap.set(0, 15L); assertEquals(List.of(15L), second.getObject(reference)); first.setObject(reference, null); assertSame(heap, image.getObject(reference));
    }
    @Test void scalarEntryRejectsTupleOnlyOperationsBeforeEvaluatingOperands() {
        try (var context = Context.newBuilder("thc").option("engine.WarnInterpreterOnly", "false").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var value = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(), List.of(state, value), null, null, null, null), language); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
            java.util.function.Supplier<Expr[]> operands = () -> { Expr[] result = new Expr[3]; for (int i = 0; i < result.length; i++) result[i] = new Expr() { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("scalar rejection evaluated an operand"); } }; return result; };
            for (String name : List.of("newPromptTag#", "prompt#", "control0#")) { var node = new DelimitedPrimitive(name, shape, operands.get(), language, new Metrics(false)); assertEquals(name + " requires a tuple destination", assertThrows(RuntimeFault.class, () -> node.execute(frame)).getMessage()); }
            for (String name : List.of("catch#", "maskAsyncExceptions#", "maskUninterruptible#", "unmaskAsyncExceptions#")) { var node = new DelimitedIOBoundary(name, shape, operands.get(), language, new Metrics(false)); assertEquals(name + " requires a tuple destination", assertThrows(RuntimeFault.class, () -> node.execute(frame)).getMessage()); }
        } finally { context.leave(); } }
    }
    private CoreRepresentation tuple(CoreRepresentation... fields) { var reps = new ArrayList<String>(); for (var field : fields) reps.addAll(Objects.requireNonNull(field.getPrimReps())); return new CoreRepresentation(CoreKind.UNKNOWN, false, false, reps, Arrays.asList(fields), null, null, null, null); }
    @Test void continuationLoweringRejectsWrongCarrierAndTupleContracts() {
        var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var tag = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null); var closure = new CoreRepresentation(CoreKind.CLOSURE, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var integer = new CoreRepresentation(CoreKind.LONG, false, false, List.of("IntRep"), null, null, null, null, null);
        DelimitedControl.validate("newPromptTag#", List.of(state), List.of(false), tuple(state, tag)); assertThrows(RuntimeFault.class, () -> DelimitedControl.validate("newPromptTag#", List.of(state), List.of(false), tuple(state, integer)));
        for (String name : List.of("prompt#", "control0#")) { DelimitedControl.validate(name, List.of(tag, closure, state), List.of(false, true, false), tuple(state, tag)); assertThrows(RuntimeFault.class, () -> DelimitedControl.validate(name, List.of(integer, closure, state), List.of(false, true, false), tuple(state, tag))); assertThrows(RuntimeFault.class, () -> DelimitedControl.validate(name, List.of(tag, tag, state), List.of(false, true, false), tuple(state, tag))); assertThrows(RuntimeFault.class, () -> DelimitedControl.validate(name, List.of(tag, closure, state), List.of(false, true, false), tuple(tag, state))); }
    }
    @Test void closedContextPromptAndContinuationCannotEnterAnotherContext() {
        PromptTag foreignTag; DelimitedStack foreignStack;
        try (var first = Context.newBuilder("thc").option("engine.WarnInterpreterOnly", "false").build()) { first.initialize("thc"); first.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); foreignTag = new PromptTag(Language.currentState(null)); assertNotSame(foreignTag, new PromptTag(Language.currentState(null))); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var value = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(), List.of(state, value), null, null, null, null), language); foreignStack = new DelimitedStack(new DelimitedCut(foreignTag, null, shape, MaskingState.UNMASKED, new Node() {}), shape);
        } finally { first.leave(); } }
        try (var second = Context.newBuilder("thc").option("engine.WarnInterpreterOnly", "false").build()) { second.initialize("thc"); second.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var local = new PromptTag(Language.currentState(null)); var probe = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false)); @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { var operation = frame.getArguments()[0]; if (Objects.equals(operation, 0)) return DelimitedControl.tag(this, local); if (Objects.equals(operation, 1)) return DelimitedControl.tag(this, foreignTag); if (Objects.equals(operation, 2)) return foreignStack.resume(site, frame.materialize(), null); return DelimitedControl.tag(this, 0L); }
            }.getCallTarget(); assertSame(local, probe.call(0)); for (int i = 1; i <= 3; i++) { int operation = i; assertThrows(RuntimeFault.class, () -> probe.call(operation)); } ThreadInventoryCoreEvidence.released(language); assertEquals(MaskingState.UNMASKED, Language.currentState(null).getMaskingState().get());
        } finally { second.leave(); } }
    }
}
