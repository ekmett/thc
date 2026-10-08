// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.RootNode;
import jam.vm.Lifted;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class DataLayoutFirstFieldTest {
    private void inLanguage(CheckedConsumer<Language> action) throws Exception {
        for (var strategy : list("field-based", "array-based")) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.StaticObjectStorageStrategy", strategy).build()) {
                context.initialize("thc"); context.enter();
                try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
                finally { context.leave(); }
            }
        }
    }
    private DataLayout emptyFirstField(Language language) {
        var empty = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
            "primReps", list(), "components", list());
        var lifted = map("kind", "data", "evaluated", false, "primReps", list("BoxedRep (Just Lifted)"));
        var fields = new CoreFields(map("id", "test:EmptyFirst", "kind", "boxed", "arity", 2,
            "fieldTypes", list(empty, lifted), "fieldReps", list(empty.get("primReps"), lifted.get("primReps")),
            "fieldLifted", list(false, true), "strictFields", list(true, false)));
        return DataLayout.fromFields(language, "test:EmptyFirst", "EmptyFirst", fields);
    }
    private Thunk pending() {
        return new Thunk(RootNode.createConstantNode(Unit.INSTANCE).getCallTarget(), null);
    }
    private Thunk published(Object value) {
        var thunk = pending(); thunk.setValue(value); thunk.setState(2); return thunk;
    }
    @Test void liftedProjectionReturnsExistingReferencesWithoutBoxingLogicalFields() throws Exception {
        inLanguage(language -> {
            var integer = new DataLayout(language, "test:LiftedInt", "I#", new String[]{"IntRep"}).createLong(42L);
            var closure = new Closure(null, 1, RootNode.createConstantNode(Unit.INSTANCE).getCallTarget());
            var receiver = new Object();
            var foreign = new ForeignValue(Language.currentState(), receiver);
            var opaque = new Object();
            var layout = new DataLayout(language, "test:LiftedFields", "LiftedFields",
                new String[]{"LiftedRep", "UnliftedRep", "BoxedRep", "IntRep", "FloatRep", "DoubleRep", "AddrRep", "VoidRep", "LiftedRep"});
            var record = layout.create(new Object[]{integer, closure, foreign, 7L, 1.25f, 2.5, ManagedAddress.nullAddress(), Unit.INSTANCE, opaque});
            assertNull(record.resolve());
            assertSame(integer, record.project(0)); assertSame(closure, record.project(1)); assertSame(foreign, record.project(2));
            for (int i : new int[]{-1, 3, 4, 5, 6, 7, 8, 9}) assertNull(record.project(i));
            assertEquals(42L, integer.getLayout().readLong(integer, 0));
            assertNull(integer.resolve()); assertNull(integer.project(0));
            assertNull(closure.resolve()); assertNull(closure.project(0));
            assertNull(foreign.resolve()); assertNull(foreign.project(0)); assertSame(receiver, foreign.getReceiver());
            var aggregate = emptyFirstField(language).create(new Object[]{integer});
            assertNull(aggregate.project(0)); assertSame(integer, aggregate.project(1));
            var vector = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 4 Int32ElemRep"),
                "vector", map("lanes", 4, "element", "Int32ElemRep"));
            var vectorFields = new CoreFields(map("id", "test:Vector", "kind", "boxed", "arity", 1,
                "fieldTypes", list(vector), "fieldReps", list(vector.get("primReps")), "fieldLifted", list(false), "strictFields", list(true)));
            assertNull(DataLayout.fromFields(language, "test:Vector", "Vector", vectorFields).allocate().project(0));
            var child = pending();
            var lazy = new DataLayout(language, "test:LazyField", "LazyField", new String[]{"LiftedRep"}).create(new Object[]{child});
            assertSame(child, lazy.project(0)); assertEquals(0, child.getState());
        });
    }
    @Test void liftedResolutionUsesPublishedAnswersAndPreservesCyclesAndOpaqueComputations() throws Exception {
        inLanguage(language -> {
            var terminal = new DataLayout(language, "test:Terminal", "Terminal", new String[0]).allocate();
            var record = new DataLayout(language, "test:Record", "Record", new String[]{"LiftedRep"}).create(new Object[]{terminal});
            for (int state : new int[]{0, 1, 3, 4, 5}) {
                var thunk = pending(); thunk.setValue(record); thunk.setState(state);
                assertNull(thunk.resolve()); assertNull(thunk.project(0)); assertSame(thunk, LiftedValues.resolveBoxed(thunk));
                assertEquals(state, thunk.getState()); assertSame(record, thunk.getValue());
            }
            var result = published(record);
            assertSame(record, result.resolve()); assertSame(terminal, result.project(0));
            Lifted chain = result;
            for (int i = 0; i < 256; i++) chain = published(chain);
            assertSame(record, LiftedValues.resolveBoxed(chain)); assertSame(terminal, chain.project(0));
            assertSame(record, LiftedValues.resolveBoxed(LiftedValues.resolveBoxed(chain)));
            var a = pending(); var b = published(a); a.setValue(b); a.setState(2);
            assertSame(a, LiftedValues.resolveBoxed(a)); assertNull(a.project(0));
            chain = a;
            for (int i = 0; i < 256; i++) chain = published(chain);
            assertSame(chain, LiftedValues.resolveBoxed(chain)); assertNull(chain.project(0));
            a.setValue(a); assertSame(a, LiftedValues.resolveBoxed(a)); assertNull(a.project(0));
            assertNull(LiftedValues.resolveBoxed(null));
            for (Object value : new Object[]{new Object(), 42L, 7, 1.25f, 2.5, Unit.INSTANCE, ManagedAddress.nullAddress()}) {
                var primitive = published(value);
                assertNull(primitive.resolve()); assertNull(primitive.project(0)); assertSame(value, primitive.getValue());
                assertSame(value, LiftedValues.resolveBoxed(primitive));
            }
        });
    }
    @Test void selectorRequiresALiftedLogicalFirstFieldAndPreservesReferenceIdentity() throws Exception {
        inLanguage(language -> {
            var reference = new Object();
            var lifted = new DataLayout(language, "test:LiftedFirst", "LiftedFirst", new String[]{"LiftedRep", "IntRep"});
            assertSame(reference, lifted.readFirstLifted(lifted.create(new Object[]{reference, 42L})));
            var scalar = new DataLayout(language, "test:ScalarFirst", "ScalarFirst", new String[]{"IntRep"});
            var empty = new DataLayout(language, "test:NoFields", "NoFields", new String[0]);
            var aggregate = emptyFirstField(language);
            for (var value : list(scalar.create(new Object[]{42L}), empty.create(new Object[0]), aggregate.create(new Object[]{reference}))) {
                var failure = assertThrows(RuntimeFault.class, () -> value.getLayout().readFirstLifted(value));
                assertEquals("atomicModifyMutVar2# requires a lifted first record field", failure.getMessage());
            }
        });
    }
    @Test void brokenFieldsRetainTheHelperNullDiagnosticAndAggregateShortCircuit() throws Exception {
        inLanguage(language -> {
            var field = DataLayout.class.getDeclaredField("fields"); field.setAccessible(true);
            var lifted = new DataLayout(language, "test:BrokenFirst", "BrokenFirst", new String[]{"LiftedRep"});
            var aggregate = emptyFirstField(language);
            for (var layout : list(lifted, aggregate)) {
                var value = layout.create(new Object[]{new Object()});
                var original = field.get(layout);
                try {
                    field.set(layout, null);
                    if (layout == lifted) {
                        var failure = assertThrows(NullPointerException.class, () -> layout.readFirstLifted(value));
                        assertEquals("Constructor fields must not be null", failure.getMessage());
                    } else {
                        var failure = assertThrows(RuntimeFault.class, () -> layout.readFirstLifted(value));
                        assertEquals("atomicModifyMutVar2# requires a lifted first record field", failure.getMessage());
                    }
                } finally { field.set(layout, original); }
            }
        });
    }
}
