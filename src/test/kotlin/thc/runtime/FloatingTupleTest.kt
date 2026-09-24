@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

class FloatingTupleTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    @BeforeEach fun verifyEvidence() {
        val provenance = Json.parse(File(root, "build/floating-tuple/provenance.json").readText()) as Map<String, Any?>
        val sources = provenance["sources"] as List<Map<String, String>>
        for (record in sources + (provenance["artifacts"] as List<Map<String, String>>)) {
            val file = File(root, record.getValue("path"))
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(record["sha256"], hash, "Stale floating tuple evidence: $file")
        }
        assertTrue(sources.map { it["path"] }.containsAll(listOf("scripts/audit-core.py", "scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json", "scripts/generate-scalar-signatures.py")))
        for (stage in listOf("pre", "post")) assertEquals(true,
            (Json.parse(File(root, "build/floating-tuple/$stage-audit.json").readText()) as Map<*, *>)["accepted"])
    }
    private fun module(stage: String = "pre") = Json.parse(File(root,
        "build/floating-tuple/$stage-core/FloatingTupleAudit.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun withLanguage(inlining: Boolean = true, action: (Language) -> Unit) = context(inlining).use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial compilation")
    }
    private fun program(language: Language, backend: String, linked: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
    }
    private fun shape(language: Language, name: String): TupleShape {
        val binding = (module()["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
        return TupleShape(CoreRepresentations.lambdaResult(binding["expr"] as List<Any?>), language)
    }

    @Test fun nativeComplexAndMixedResultsExecuteInlined() = native(true)
    @Test fun nativeComplexAndMixedResultsExecuteAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val rows = File(root, "build/floating-tuple/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(44, rows.values.sumOf { it.size })
        // Counts include the entry and every actual guest function invocation,
        // whether inlined or residual. Same-frame joins add no root entries.
        val expectedCalls = mapOf("complexFloatCase" to 2L, "complexDoubleCase" to 2L,
            "mixedCase" to 4L, "joinedCase" to 2L, "ieeeCase" to 2L)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) withLanguage(inlining) { language ->
            for ((name, inputs) in rows) {
                val linked = CoreModules.reachable(module(stage), name)
                val bindings = linked["bindings"] as List<Map<String, Any?>>
                val program = program(language, backend, linked)
                val target = program.entryTarget(bindings.single { it["name"] == name }["id"] as String)
                fun checkRows(compiled: Boolean) {
                    for (row in inputs) {
                        val label = "$stage/$backend/$name/${row[1]}/inlining=$inlining"
                        val before = count(program)
                        assertEquals(row[2].toLong(), Calls.target(target, arrayOf(0L, row[1].toLong())), label)
                        if (compiled) {
                            valid(target, label)
                            assertEquals(expectedCalls.getValue(name), count(program) - before, "$label installed entries ${program.diagnostics()}")
                        }
                        released(language)
                    }
                }
                checkRows(false)
                bindings.filter { (it["expr"] as List<*>)[0] == "lam" }.forEach { compile(program.entryTarget(it["id"] as String)) }
                val allocations = language.handoffState.get().results.allocations
                checkRows(true)
                assertEquals(allocations, language.handoffState.get().results.allocations, "$backend/$name pooled reuse")
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                if (name == "joinedCase") assertTrue((program.diagnostics().getValue("localJoinTransfers") as Number).toLong() > 0)
            }
        }
    }

    @Test fun compiledResidualProducerPreservesNativeIeeeBits() {
        val rows = File(root, "build/floating-tuple/bits.tsv").readLines().map { it.split('\t') }
        for (backend in listOf("ast", "bytecode")) withLanguage(false) { language ->
            val linked = CoreModules.reachable(module(), "ieeePair")
            val bindings = linked["bindings"] as List<Map<String, Any?>>
            val program = program(language, backend, linked)
            val target = program.entryTarget(bindings.single { it["name"] == "ieeePair" }["id"] as String)
            val shape = shape(language, "ieeePair")
            val layout = FrameLayout(); val slots = intArrayOf(layout.bind("float"), layout.bind("double"))
            val descriptor = layout.build()
            fun checkRows(compiled: Boolean) {
                for (row in rows) {
                    val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                    val before = count(program)
                    shape.consume(frame, Calls.target(target, arrayOf(0L, row[0].toLong())), slots, 0)
                    assertTrue(frame.isFloat(slots[0])); assertTrue(frame.isDouble(slots[1]))
                    if (row[0] == "6") {
                        assertTrue(frame.getFloat(slots[0]).isNaN()); assertTrue(frame.getDouble(slots[1]).isNaN())
                    } else {
                        assertEquals(row[1].toULong().toInt(), frame.getFloat(slots[0]).toRawBits(), "$backend/${row[0]}")
                        assertEquals(row[2].toULong().toLong(), frame.getDouble(slots[1]).toRawBits(), "$backend/${row[0]}")
                    }
                    if (compiled) { assertEquals(1L, count(program) - before); valid(target, "$backend/${row[0]}") }
                    released(language)
                }
            }
            checkRows(false); compile(target); checkRows(true)
        }
    }

    @Test fun concreteFieldsPreserveNanPayloadsAndReleaseOnlyReferences() = withLanguage { language ->
        val shape = shape(language, "mixed")
        assertEquals(listOf("float", "double", "reference"), shape.layout.reps)
        assertEquals(3, shape.width); assertArrayEquals(intArrayOf(0, 0, 2), shape.offsets)
        assertFalse(TupleShape.compatible(shape.components[0], shape.components[1].components!![1]))
        val storage = shape.layout.create()
        val types = storage.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.type }
        assertTrue(Float::class.javaPrimitiveType in types); assertTrue(Double::class.javaPrimitiveType in types)
        assertFalse(types.any { it.isArray || it == Float::class.javaObjectType || it == Double::class.javaObjectType })
        val layout = FrameLayout(); val from = IntArray(3) { layout.bind("from$it") }; val to = IntArray(3) { layout.bind("to$it") }
        val descriptor = layout.build(); val pointer = Any()
        for ((f, d) in listOf(-0.0f to -0.0, Float.MIN_VALUE to Double.MIN_VALUE,
                Float.fromBits(0x7fc01234) to Double.fromBits(0x7ff8000000005678L))) {
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
            FrameAccess.writeFloat(frame, from[0], f); FrameAccess.writeDouble(frame, from[1], d); FrameAccess.write(frame, from[2], pointer)
            TupleLocalRead(shape, from).executeTuple(frame, to, 0)
            val completion = shape.finish(frame, to)
            assertEquals(1, language.handoffState.get().results.depth)
            shape.consume(frame, completion, from, 0)
            assertEquals(f.toRawBits(), frame.getFloat(from[0]).toRawBits())
            assertEquals(d.toRawBits(), frame.getDouble(from[1]).toRawBits()); assertSame(pointer, frame.getObject(from[2]))
            released(language)
            // A materialized fresh carrier owns no pool loan.
            shape.layout.setFloat(storage, 0, f); shape.layout.setDouble(storage, 1, d); shape.layout.setObject(storage, 2, pointer)
            shape.consume(frame, storage, to, 0)
            assertEquals(f.toRawBits(), frame.getFloat(to[0]).toRawBits()); assertEquals(d.toRawBits(), frame.getDouble(to[1]).toRawBits())
            assertSame(pointer, frame.getObject(to[2])); released(language)
        }
        val wrong = shape(language, "ieeePair")
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        FrameAccess.writeFloat(frame, from[0], 1.0f); FrameAccess.writeDouble(frame, from[1], 2.0); FrameAccess.write(frame, from[2], pointer)
        val result = shape.finish(frame, from)
        assertThrows(IllegalStateException::class.java) { wrong.consume(frame, result, to, 0) }
        released(language)
    }

    @Test fun floatingTupleInputsKeepScalarHandoffAndHostAggregateFrontiersDistinct() = withLanguage { language ->
        val integer = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
        for ((kind, rep) in listOf(CoreKind.FLOAT to "FloatRep", CoreKind.DOUBLE to "DoubleRep")) {
            assertFalse(HandoffLayout.supports(rep)); assertTrue(HandoffLayout.supportsResult(rep))
            val proof = CoreRepresentation(kind, true, true, listOf(rep))
            assertNull(HandoffEntry.create(language, FrameLayout(), listOf(proof), integer, false))
        }
        if (language.handoffLayouts.enabled) assertNotNull(HandoffEntry.create(language, FrameLayout(), listOf(integer), integer, false))
        for (backend in listOf("ast", "bytecode")) {
            val p = program(language, backend, CoreModules.reachable(module(), "floatingTupleArgument"))
            val input = (p.entryTarget("floatingTupleArgument").rootNode as GuestRoot).inputLayout!!
            assertTrue(input.requiresTyped)
            assertEquals(1, input.logicalArity); assertEquals(2, input.physicalArity)
            assertEquals(listOf(CoreKind.FLOAT, CoreKind.DOUBLE), input.physicalProofs.map { it.kind })
            // Guest tuple parameters are supported, but a scalar host argument
            // cannot stand in for the exact logical tuple shape.
            assertThrows(RuntimeFault::class.java) {
                Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue("floatingTupleArgument"), arrayOf<Any?>(0L)))
            }
            released(language)
        }
    }

    @Test fun equalPhysicalWidthsCannotEraseNestedTupleIdentity() = withLanguage { language ->
        for (backend in listOf("ast", "bytecode")) {
            val module = module()
            val worker = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == "\$wcomplexFloat" }
            val proof = ((worker["expr"] as List<*>)[3] as Map<*, *>)["resultRep"] as MutableMap<String, Any?>
            val components = proof["components"] as MutableList<Any?>
            components[0] = mapOf("kind" to "unknown", "primReps" to listOf("FloatRep"), "evaluated" to true,
                "aggregate" to "unboxed-tuple", "components" to listOf(components[0]))
            assertThrows(RuntimeFault::class.java) { program(language, backend, CoreModules.reachable(module, "complexFloatCase")) }
        }
    }

    @Test fun wholeNestedTupleCaseBinderCopiesFloatingSlotsOnBothBackends() = withLanguage { language ->
        for (backend in listOf("ast", "bytecode")) {
            val module = module()
            val bindings = module["bindings"] as List<Map<String, Any?>>
            val forward = bindings.single { it["name"] == "mixedForward" }["expr"] as MutableList<Any?>
            val proof = (forward[3] as Map<String, Any?>)["resultRep"]
            // GHC optimizes this identity case away. Retain its valid Core form
            // explicitly to exercise lexical whole-tuple reads, not reconstruction.
            val binder = "whole-floating-tuple"
            forward[2] = listOf("case", forward[2], binder,
                listOf(listOf("default", null, emptyList<String>(), listOf("var", binder, mapOf("rep" to proof)))),
                mapOf("rep" to proof, "binder" to mapOf("id" to binder, "rep" to proof)))
            val linked = CoreModules.reachable(module, "mixedCase")
            val program = program(language, backend, linked)
            val target = program.entryTarget(bindings.single { it["name"] == "mixedCase" }["id"] as String)
            for (input in listOf(-7L, 0L, 7L)) assertEquals(20L * input - 23,
                Calls.target(target, arrayOf(0L, input)))
            // Compile producers too: copying the whole tuple must stay compiled
            // even when Graal keeps a producer call out of line.
            (linked["bindings"] as List<Map<String, Any?>>)
                .filter { (it["expr"] as List<*>)[0] == "lam" }
                .forEach { compile(program.entryTarget(it["id"] as String)) }
            for (input in listOf(-7L, 0L, 7L)) {
                val before = count(program)
                assertEquals(20L * input - 23, Calls.target(target, arrayOf(0L, input)))
                valid(target, backend); assertEquals(4L, count(program) - before); released(language)
            }
        }
    }
}
