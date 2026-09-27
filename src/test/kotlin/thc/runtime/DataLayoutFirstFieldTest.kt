// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class DataLayoutFirstFieldTest {
    private fun inLanguage(action: (Language) -> Unit) {
        for (strategy in listOf("field-based", "array-based")) Context.newBuilder("thc")
            .allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy).build().use { context ->
                context.initialize("thc"); context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
                finally { context.leave() }
            }
    }

    private fun emptyFirstField(language: Language): DataLayout {
        val empty = mapOf("kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-tuple",
            "primReps" to emptyList<String>(), "components" to emptyList<Any>())
        val lifted = mapOf("kind" to "data", "evaluated" to false,
            "primReps" to listOf("BoxedRep (Just Lifted)"))
        val fields = CoreFields(mapOf("id" to "test:EmptyFirst", "kind" to "boxed", "arity" to 2,
            "fieldTypes" to listOf(empty, lifted), "fieldReps" to listOf(empty["primReps"], lifted["primReps"]),
            "fieldLifted" to listOf(false, true), "strictFields" to listOf(true, false)))
        return DataLayout.fromFields(language, "test:EmptyFirst", "EmptyFirst", fields)
    }

    @Test fun selectorRequiresALiftedLogicalFirstFieldAndPreservesReferenceIdentity() = inLanguage { language ->
        val reference = Any()
        val lifted = DataLayout(language, "test:LiftedFirst", "LiftedFirst", arrayOf("LiftedRep", "IntRep"))
        assertSame(reference, lifted.readFirstLifted(lifted.create(arrayOf(reference, 42L))))
        val scalar = DataLayout(language, "test:ScalarFirst", "ScalarFirst", arrayOf("IntRep"))
        val empty = DataLayout(language, "test:NoFields", "NoFields", emptyArray())
        val aggregate = emptyFirstField(language)
        for (value in listOf(scalar.create(arrayOf(42L)), empty.create(emptyArray()), aggregate.create(arrayOf(reference)))) {
            val failure = assertThrows(RuntimeFault::class.java) { value.layout.readFirstLifted(value) }
            assertEquals("atomicModifyMutVar2# requires a lifted first record field", failure.message)
        }
    }

    @Test fun brokenFieldsRetainTheHelperNullDiagnosticAndAggregateShortCircuit() = inLanguage { language ->
        val field = DataLayout::class.java.getDeclaredField("fields").also { it.isAccessible = true }
        val lifted = DataLayout(language, "test:BrokenFirst", "BrokenFirst", arrayOf("LiftedRep"))
        val aggregate = emptyFirstField(language)
        for (layout in listOf(lifted, aggregate)) {
            val value = layout.create(arrayOf(Any()))
            val original = field.get(layout)
            try {
                field.set(layout, null)
                if (layout === lifted) {
                    val failure = assertThrows(NullPointerException::class.java) { layout.readFirstLifted(value) }
                    assertEquals("Parameter specified as non-null is null: method kotlin.collections.ArraysKt___ArraysKt.firstOrNull, parameter <this>", failure.message)
                } else {
                    val failure = assertThrows(RuntimeFault::class.java) { layout.readFirstLifted(value) }
                    assertEquals("atomicModifyMutVar2# requires a lifted first record field", failure.message)
                }
            } finally { field.set(layout, original) }
        }
    }
}
