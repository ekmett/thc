@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.CoreRepresentations
import thc.runtime.CoreKind
import thc.runtime.RuntimeFault
import thc.runtime.UnsupportedCore
import java.io.File

/** Supported tuple and binary-sum results execute; aggregate arguments remain explicit boundaries. */
class AggregateFrontierTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val constructors = listOf("tupleOutstanding", "tupleZeroLazy", "sumPayload", "sumZeroLazy", "coldTuple", "coldSum")
    private val supported = constructors.toSet()
    private val boundaries = mapOf("emptyIdentity" to "unboxed-tuple", "emptyDiscard" to "unboxed-tuple",
        "singletonIdentity" to "unboxed-tuple", "pairIdentity" to "unboxed-tuple", "sumIdentity" to "unboxed-sum")
    private fun exported(stage: String): Map<String, Any?> =
        Json.parse(File(root, "build/$stage/AggregateFrontier.json").readText()) as Map<String, Any?>
    private fun request(module: Map<String, Any?>, entry: String, backend: String): String =
        Json.stringify(mapOf("modules" to listOf(module), "entry" to entry, "backend" to backend,
            "diagnosticUnsupported" to false))

    @Test fun strictLoadingRejectsOptimizedAggregatesBeforeAnyInputRuns() {
        for (stage in listOf("aggregate-core", "aggregate-post-core")) {
            val module = exported(stage)
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                for (entry in (constructors - supported) + boundaries.keys) {
                    val error = assertThrows(PolyglotException::class.java) {
                        context.eval("thc", request(module, entry, backend))
                    }
                    val message = error.message.orEmpty()
                    assertTrue(message.contains("Unsupported Core aggregate representation:"), "$stage/$backend/$entry: $message")
                    boundaries[entry]?.let { assertTrue(message.contains(it), "$stage/$backend/$entry: $message") }
                }
                // Unreachable aggregate globals and constructor metadata do not reject a supported root.
                val identity = context.eval("thc", request(module, "abstractIdentity", backend))
                assertEquals(1234L, identity.execute(1234L).asLong())
                context.eval("thc", request(module, "stateIdentity", backend))
            }
        }
    }

    private fun withoutMarkers(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.filterKeys { it != "aggregate" }.mapValues { withoutMarkers(it.value) }
        is List<*> -> value.map(::withoutMarkers)
        else -> value
    }

    @Test fun existingConstructorRejectionAlsoCoversColdAndZeroWidthArms() {
        val legacy = withoutMarkers(exported("aggregate-core")) as Map<String, Any?>
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (entry in constructors) {
                val error = assertThrows(PolyglotException::class.java) {
                    context.eval("thc", request(legacy, entry, backend))
                }
                assertTrue(error.message.orEmpty().contains("Unsupported constructor representation unboxed-"), "$backend/$entry: ${error.message}")
            }
        }
    }

    @Test fun diagnosticModeKeepsLegacyColdAggregatePathsLazyAndTrapsWhenReached() {
        val module = withoutMarkers(exported("aggregate-core")) as Map<String, Any?>
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for ((entry, expected) in listOf("coldSum" to -7L)) {
                val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                    "entry" to entry, "backend" to backend, "diagnosticUnsupported" to true)))
                repeat(8) { assertEquals(expected, function.execute(0L).asLong()) }
                assertTrue(function.invokeMember("compile").asBoolean())
                fun compiledEntries(): Long = ((Json.parse(function.getMember("diagnostics").asString())
                    as Map<String, Any?>)["compiledEntries"] as Number).toLong()
                val before = compiledEntries()
                assertEquals(expected, function.execute(0L).asLong())
                assertTrue(compiledEntries() > before, "$backend/$entry: must enter compiled code before the cold trap")
                val error = assertThrows(PolyglotException::class.java) { function.execute(31337L) }
                assertTrue(error.message.orEmpty().contains("Diagnostic unsupported path reached: Unsupported constructor representation unboxed-sum"), "$backend/$entry: ${error.message}")
            }
        }
    }

    @Test fun diagnosticModeTrapsConstructorFreeBoundariesIncludingUnusedFormals() {
        val module = exported("aggregate-core")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for ((entry, kind) in boundaries) {
                val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                    "entry" to entry, "backend" to backend, "diagnosticUnsupported" to true)))
                val error = assertThrows(PolyglotException::class.java) { function.execute(0L) }
                assertTrue(error.message.orEmpty().contains("Diagnostic unsupported path reached: Unsupported Core aggregate representation: $kind"), "$backend/$entry: ${error.message}")
            }
        }
    }

    @Test fun ordinaryUnknownMetadataRemainsOptionalAndDoesNotInferAggregateShape() {
        assertFalse(CoreRepresentations.parse(null).present)
        for (reps in listOf(null, emptyList(), listOf("IntRep"), listOf("IntRep", "IntRep"),
            listOf("BoxedRep (Just Lifted)"), listOf("FloatRep"))) {
            val proof = mapOf("kind" to "unknown", "primReps" to reps, "evaluated" to true)
            assertEquals(CoreKind.UNKNOWN, CoreRepresentations.parse(proof).kind)
            for (aggregate in listOf("unboxed-tuple", "unboxed-sum")) {
                val error = assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(proof + ("aggregate" to aggregate)) }
                assertTrue(error.message.orEmpty().startsWith("Unsupported Core aggregate representation: $aggregate"))
            }
            assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(proof + ("aggregate" to "guessed")) }
        }
    }
}
