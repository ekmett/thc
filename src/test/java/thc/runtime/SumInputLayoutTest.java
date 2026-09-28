// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class SumInputLayoutTest {
    private final CoreRepresentation integer = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
    private final CoreRepresentation word = integer.copy(integer.getKind(), integer.getEvaluated(), integer.getPresent(), List.of("WordRep"), integer.getComponents(), integer.getVector(), integer.getAlternatives(), integer.getTagSlot(), integer.getAlternativeSlots());
    private final CoreRepresentation reference = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
    private final CoreRepresentation sum = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("WordRep", "BoxedRep (Just Lifted)", "WordRep"), null, null, List.of(reference, integer), 0, List.of(List.of(1), List.of(2)));
    @FunctionalInterface private interface Action { void run(Language language) throws ReflectiveOperationException; }
    private void withLanguage(Action action) throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
    @Test void resumedScalarSumArmsUpdateTheirOriginalProfilesWithoutReplay() throws ReflectiveOperationException { resumedArmProfiles(false); }
    @Test void resumedTupleSumArmsUpdateTheirOriginalProfilesWithoutReplay() throws ReflectiveOperationException { resumedArmProfiles(true); }
    private void resumedArmProfiles(boolean tuple) throws ReflectiveOperationException {
        withLanguage(language -> {
            var layout = new FrameLayout(); int tag = layout.bind("sum tag"), destination = layout.bind("narrow tuple result");
            var narrow = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Int32Rep"), null, null, null, null, null);
            var events = new ArrayList<String>();
            var scrutinee = new Expr() {
                @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("sum scrutinee needs a destination"); }
                @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
                    events.add("prefix");
                    if (Objects.equals(frame.getArguments()[2], false)) { FrameAccess.writeLong(frame, slots[offset], (Long) frame.getArguments()[1]); return null; }
                    throw new AstCapture(kotlin.Unit.INSTANCE, MaskingState.UNMASKED).append((resumed, input) -> {
                        assertSame(kotlin.Unit.INSTANCE, input); events.add("suffix"); FrameAccess.writeLong(resumed, slots[offset], (Long) resumed.getArguments()[1]); return null;
                    });
                }
            };
            Expr[] arms = new Expr[2];
            for (int index = 0; index < arms.length; index++) {
                final int arm = index;
                arms[index] = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("sum branch lost its narrow result route"); }
                    @Override public int executeInt(VirtualFrame frame) { events.add("arm" + arm); return arm == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE; }
                    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { FrameAccess.writeInt(frame, slots[offset], executeInt(frame)); return null; }
                };
            }
            var choice = new SumCase(scrutinee, new int[]{tag}, arms, new int[]{0, 1, 0, -1}, tuple
                ? new CoreRepresentation(CoreKind.UNKNOWN, true, true, narrow.getPrimReps(), List.of(narrow), null, null, null, null) : narrow);
            var root = new GuestRoot(language, layout.build()) {
                @Child private SumCase branch = choice;
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) {
                    try {
                        if (tuple) { branch.executeTuple(frame, new int[]{destination}, 0); return frame.getInt(destination); }
                        return branch.executeInt(frame);
                    } catch (AstCapture cut) {
                        if (tuple) cut.append((resumed, input) -> {
                            assertNull(input);
                            try { return resumed.getInt(destination); } catch (com.oracle.truffle.api.frame.FrameSlotTypeException failure) { throw rethrow(failure); }
                        });
                        return cut.freeze(this, frame.materialize());
                    } catch (com.oracle.truffle.api.frame.FrameSlotTypeException | com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw rethrow(failure); }
                }
            };
            // Inspect the existing pinned profiles, not a second test-only seen-state.
            var field = SumCase.class.getDeclaredField("armProfiles"); field.setAccessible(true); var profiles = (Object[]) field.get(choice);
            class Counts {
                List<List<Integer>> get() throws ReflectiveOperationException {
                    var result = new ArrayList<List<Integer>>();
                    for (var profile : profiles) {
                        var trueCount = Objects.requireNonNull(profile).getClass().getDeclaredMethod("getTrueCount"); trueCount.setAccessible(true);
                        int yes = (Integer) trueCount.invoke(profile);
                        var falseCount = profile.getClass().getDeclaredMethod("getFalseCount"); falseCount.setAccessible(true);
                        int no = (Integer) falseCount.invoke(profile); result.add(List.of(yes, no));
                    }
                    return result;
                }
            }
            var counts = new Counts(); var expectedEvents = new ArrayList<String>();
            var observations = List.of(List.of(List.of(1, 0), List.of(0, 0)), List.of(List.of(1, 1), List.of(1, 0)), List.of(List.of(2, 1), List.of(1, 0)));
            for (long selected = 1L; selected <= 3L; selected++) {
                var before = counts.get(); var saved = (AstContinuation) Calls.target(root.getCallTarget(), new Object[]{0L, selected, true});
                assertEquals(before, counts.get(), "No arm is observed before the scrutinee resumes");
                int expected = selected == 2L ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                assertEquals(expected, saved.continueWith(kotlin.Unit.INSTANCE)); assertEquals(observations.get((int) (selected - 1)), counts.get());
                assertThrows(RuntimeFault.class, () -> saved.continueWith(kotlin.Unit.INSTANCE));
                expectedEvents.addAll(List.of("prefix", "suffix", "arm" + (selected == 2L ? 1 : 0))); assertEquals(expectedEvents, events);
            }
            assertEquals(Integer.MAX_VALUE, Calls.target(root.getCallTarget(), new Object[]{0L, 2L, false}));
            assertEquals(List.of(List.of(2, 2), List.of(2, 0)), counts.get(), "Ordinary and resumed arms share the same profiles"); expectedEvents.addAll(List.of("prefix", "arm1"));
            var missing = (AstContinuation) Calls.target(root.getCallTarget(), new Object[]{0L, 4L, true});
            assertEquals("Non-exhaustive unboxed sum case", assertThrows(RuntimeFault.class, () -> missing.continueWith(kotlin.Unit.INSTANCE)).getMessage());
            assertEquals(List.of(List.of(2, 3), List.of(2, 1)), counts.get()); expectedEvents.addAll(List.of("prefix", "suffix")); assertEquals(expectedEvents, events);
            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root)); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        });
    }
    @Test void logicalSumIdentitySurvivesEqualPhysicalInputAndPrefixLayouts() throws ReflectiveOperationException {
        withLanguage(language -> {
            var formal = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(integer, sum, integer)));
            assertEquals(3, formal.getLogicalArity()); assertEquals(5, formal.getPhysicalArity());
            var offsets = new ArrayList<Integer>(); for (int i = 0; i <= 3; i++) offsets.add(formal.offset(i)); assertEquals(List.of(0, 1, 4, 5), offsets);
            var kinds = new ArrayList<CoreKind>(); for (var proof : formal.getPhysicalProofs()) kinds.add(proof.getKind());
            assertEquals(List.of(CoreKind.LONG, CoreKind.LONG, CoreKind.OBJECT, CoreKind.LONG, CoreKind.LONG), kinds);
            var changedSum = sum.copy(sum.getKind(), sum.getEvaluated(), sum.getPresent(), sum.getPrimReps(), sum.getComponents(), sum.getVector(), List.of(reference, word), sum.getTagSlot(), sum.getAlternativeSlots());
            var changed = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(integer, changedSum, integer)));
            var input = Objects.requireNonNull(TypedInputLayout.create(language, formal, false)); var other = Objects.requireNonNull(TypedInputLayout.create(language, changed, false)); assertSame(input.getPacket(), other.getPacket());
            assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(formal, 0, changed, 0, 3)); assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(formal, 1, null, 0, 1));
            CoreRepresentations.requireInput(sum); CoreRepresentations.requireJoinArgument(sum, sum);
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.requireJoinArgument(sum, changedSum));
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.requireJoinArgument(sum, integer)); assertThrows(RuntimeFault.class, () -> CoreRepresentations.requireJoinArgument(integer, sum));
            ArgumentLayout.validate(formal, 1, formal.suffix(1), 0, 2); assertEquals(4, input.prefix(2).getReps().size());
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.requireInput(sum.copy(sum.getKind(), sum.getEvaluated(), sum.getPresent(), sum.getPrimReps(), sum.getComponents(), sum.getVector(), sum.getAlternatives(), 1, sum.getAlternativeSlots())));
        });
    }
    @Test void durablePrefixesAndOwnedCapturesDoNotBorrowInputsOrRetainNullRoots() throws ReflectiveOperationException {
        withLanguage(language -> {
            var logical = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(sum, integer))); var input = Objects.requireNonNull(TypedInputLayout.create(language, logical, false));
            var prefix = input.prefix(1); var retained = prefix.create(); var marker = new Object();
            prefix.setLong(retained, 0, 1); prefix.setObject(retained, 1, marker); prefix.setLong(retained, 2, 0);
            var loan = input.state().getArguments().acquire(input.getPacket()); loan.setInputMode(1); input.getPacket().setObject(loan, input.getHeader() + 1, new Object()); input.release(loan);
            assertSame(marker, prefix.getObject(retained, 1)); assertFalse(retained.getLive()); assertEquals(0, retained.getInputMode()); assertEquals(0, input.state().getArguments().retainedReferences());
            var target = new RootNode(language) { @Override public Object execute(VirtualFrame frame) { return kotlin.Unit.INSTANCE; } }.getCallTarget();
            var captureLayout = new CaptureLayout(language, new boolean[]{true, false, true}, new boolean[]{true, false, true}); var environment = captureLayout.captureValues(new Object[]{2L, null, 37L});
            var closure = new Closure(environment, 1, target); var image = ClosureInspection.image(closure);
            assertEquals(32, image.getBytes().length); assertEquals(0, image.getPointers().length); assertNull(environment.getObject(1)); assertEquals(37L, environment.getLong(2));
            var liveEnvironment = captureLayout.captureValues(new Object[]{1L, marker, 0L}); assertArrayEquals(new Object[]{marker}, ClosureInspection.image(new Closure(liveEnvironment, 1, target)).getPointers());
            var emptyPrefix = prefix.create(); prefix.setLong(emptyPrefix, 0, 2); prefix.setObject(emptyPrefix, 1, null); prefix.setLong(emptyPrefix, 2, 41);
            var pap = new Closure(null, Closure.NO_PAP_ARGUMENTS, 1, target, 1, emptyPrefix); assertEquals(0, ClosureInspection.image(pap).getPointers().length);
        });
    }
}
