// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
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
                        assertEquals("Parameter specified as non-null is null: method kotlin.collections.ArraysKt___ArraysKt.firstOrNull, parameter <this>", failure.getMessage());
                    } else {
                        var failure = assertThrows(RuntimeFault.class, () -> layout.readFirstLifted(value));
                        assertEquals("atomicModifyMutVar2# requires a lifted first record field", failure.getMessage());
                    }
                } finally { field.set(layout, original); }
            }
        });
    }
}
