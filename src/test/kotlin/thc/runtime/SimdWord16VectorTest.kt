// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class SimdWord16VectorTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-word16x8")
    private fun module(stage: String = "pre") = Json.parse(File(directory, "$stage-core/SimdWord16X8.json").readText()) as Map<String, Any?>
    private fun metadata() = mapOf("kind" to "vector", "evaluated" to true,
        "primReps" to listOf("VecRep 8 Word16ElemRep"), "vector" to mapOf("lanes" to 8L, "element" to "Word16ElemRep"))
    private fun withLanguage(inlining: Boolean = true, action: (Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
        }
    private fun program(language: Language, backend: String, input: Map<String, Any?>, entry: String, diagnostic: Boolean = false): ExecutableProgram {
        val linked = CoreModules.reachable(input, entry) + mapOf("instrument" to true, "diagnosticUnsupported" to diagnostic)
        return if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    }
    private fun lanes(value: Word16X8) = listOf(value.first, value.second, value.third, value.fourth,
        value.fifth, value.sixth, value.seventh, value.eighth).map { it.toLong() and 0xffffL }
    private fun pack(values: List<Long>) = Word16X8(values[0].toShort(), values[1].toShort(), values[2].toShort(), values[3].toShort(),
        values[4].toShort(), values[5].toShort(), values[6].toShort(), values[7].toShort())
    private fun unsigned(value: Long): Long = value and 0xffffL

    @Test fun exactShapeRequiresEightShortFieldsAndEightWord16TupleLanes() {
        val proof = CoreRepresentations.parse(metadata())
        assertEquals(CoreVectors.proofWord16, proof)
        assertFalse(proof.isTuple); assertFalse(proof.isLong)
        val fields = Word16X8::class.java.declaredFields
        assertEquals(List(8) { Short::class.javaPrimitiveType }, fields.map { it.type })
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers) })
        assertEquals(List(8) { "Word16Rep" }, CoreVectors.unpackedWord16.primReps)
        assertTrue(CoreVectors.unpackedWord16.components!!.all { it.isLong })
        assertEquals(6, CoreVectors.operationsWord16.size)
        assertFalse("negateWord16X8#" in CoreVectors.operations)
        assertFalse(Word16X8::class.java.declaredMethods.any { it.name == "negate" })
        for (wrong in listOf(CoreVectors.proof, CoreVectors.proof8, CoreVectors.proof16, CoreVectors.proofWord8, CoreVectors.proof32, CoreVectors.proofFloat, CoreVectors.proofDouble, CoreVectors.unpackedWord16)) {
            assertFalse(TupleShape.compatible(proof, wrong))
            assertThrows(RuntimeFault::class.java) { proof.refine(wrong) }
        }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packWord16X8#", CoreVectors.unpackedWord16.components!!, proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("plusWord16X8#", listOf(proof), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("timesWord16X8#", listOf(proof, CoreVectors.proof32), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("unpackWord16X8#", listOf(proof), proof) }
        for (wrong in listOf("IntRep", "WordRep", "Int16Rep", "Int8Rep", "Word8Rep", "Int32Rep")) for (index in 0 until 8) {
            val bad = CoreVectors.unpackedWord16.copy(components = CoreVectors.unpackedWord16.components!!.mapIndexed { i, lane ->
                if (i == index) lane.copy(primReps = listOf(wrong)) else lane })
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packWord16X8#", listOf(bad), proof) }
        }
        for (name in listOf("plus", "minus", "times")) {
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate("${name}Word16X8#", listOf(proof, CoreVectors.proof16), proof) }
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate("${name}Int16X8#", listOf(CoreVectors.proof16, proof), CoreVectors.proof16) }
        }
        val bad = CoreVectors.unpackedWord16.copy(components = CoreVectors.unpackedWord16.components!!.map { it.copy(kind = CoreKind.UNKNOWN) })
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packWord16X8#", listOf(bad), proof) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata() - "vector") }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata() + ("primReps" to List(8) { "Word16Rep" })) }
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(metadata() + mapOf("primReps" to listOf("VecRep 4 Word16ElemRep"),
            "vector" to mapOf("lanes" to 4L, "element" to "Word16ElemRep"))) }
    }

    @Test fun allBitPatternsAndBoundaryPairsWrapWithoutSaturationOrHighProduct() {
        fun check(a: List<Long>, b: List<Long>, broadcast: Long) {
            val left = pack(a); val right = pack(b)
            assertEquals(a, lanes(left))
            assertEquals(List(8) { unsigned(broadcast) }, lanes(Word16X8.broadcast(broadcast.toShort())))
            assertEquals(a.zip(b).map { unsigned(it.first + it.second) }, lanes(Word16X8.add(left, right)))
            assertEquals(a.zip(b).map { unsigned(it.first - it.second) }, lanes(Word16X8.subtract(left, right)))
            assertEquals(a.zip(b).map { unsigned(it.first * it.second) }, lanes(Word16X8.multiply(left, right)))
        }
        // All 65,536 encodings in every lane; arithmetic samples are correlated,
        // not a claim of exhaustive coverage of all 2^32 binary operand pairs.
        for (bits in 0L..65535L) {
            val a = List(8) { unsigned(bits + it * 7919L) }
            val b = List(8) { unsigned(bits * (2 * it + 1) + 32767L - it * 3571L) }
            check(a, b, bits)
        }
        // Independent Cartesian boundary pairs at every selected lane, with
        // other lanes remaining distinct to expose misordered pack/unpack.
        val edges = listOf(0L, 1L, 2L, 255L, 256L, 16383L, 32767L, 32768L, 32769L, 65534L, 65535L)
        for (lane in 0 until 8) for (x in edges) for (y in edges) {
            val a = MutableList(8) { unsigned(40009L + it * 7919L) }; a[lane] = x
            val b = MutableList(8) { unsigned(50021L - it * 3571L) }; b[lane] = y
            check(a, b, x)
        }
    }

    private fun broadcastModule(operand: List<Any?>, flag: Any? = false): Map<String, Any?> {
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val vector = listOf("app", listOf("prim", "broadcastWord16X8#", mapOf("rep" to closure)),
            listOf(operand), listOf(flag), false, true, mapOf("rep" to metadata()))
        val body = listOf("case", vector, "v", listOf(listOf("default", null, emptyList<String>(),
            listOf("lit", "int", "1", mapOf("rep" to long)), mapOf("binders" to emptyList<Any>()))),
            mapOf("rep" to long, "binder" to mapOf("id" to "v", "lifted" to false, "rep" to metadata())))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "constructors" to emptyList<Any>(), "bindings" to listOf(
            mapOf("id" to "root", "name" to "root", "arity" to 1, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", listOf(mapOf("id" to "unused", "name" to "unused", "lifted" to false, "rep" to long)),
                    body, mapOf("rep" to closure, "resultRep" to long)))))
    }
    @Test fun literalRefinementAndUnliftedFlagsAreCheckedBeforeExecution() = withLanguage { language ->
        val lane = mapOf("kind" to "long", "primReps" to listOf("Word16Rep"), "evaluated" to true)
        val exact = listOf("lit", "word16", "65535", mapOf("rep" to lane))
        val unconstrained = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            for (operand in listOf(exact, listOf("lit", "word16", "1"), listOf("lit", "word16", "1", mapOf("rep" to unconstrained)))) {
                val p = program(language, backend, broadcastModule(operand), "root", diagnostic)
                assertEquals(1L, Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue("root"), arrayOf(0L))))
            }
            for (flag in listOf(true, null, 0L, "false")) assertThrows(RuntimeFault::class.java) {
                program(language, backend, broadcastModule(exact, flag), "root", diagnostic)
            }
            for (wrong in listOf(lane + ("kind" to "unknown"), lane + ("primReps" to listOf("Int16Rep")), lane + ("primReps" to listOf("Int32Rep")))) {
                assertThrows(RuntimeFault::class.java) { program(language, backend,
                    broadcastModule(listOf("lit", "word16", "1", mapOf("rep" to wrong))), "root", diagnostic) }
            }
            for (operand in listOf(listOf("lit", "int16", "32767"),
                listOf("lit", "int16", "32767", mapOf("rep" to lane)),
                listOf("lit", "int16", "32767", mapOf("rep" to unconstrained)))) {
                assertThrows(RuntimeFault::class.java) { program(language, backend, broadcastModule(operand), "root", diagnostic) }
            }
            for (wrong in listOf(
                unconstrained + mapOf("primReps" to emptyList<String>(), "aggregate" to "unboxed-tuple", "components" to emptyList<Any>()),
                lane + mapOf("aggregate" to "unboxed-tuple", "components" to listOf(lane)))) {
                assertThrows(RuntimeFault::class.java) { program(language, backend,
                    broadcastModule(listOf("lit", "word16", "1", mapOf("rep" to wrong))), "root", diagnostic) }
            }
            val unresolved = unconstrained + mapOf("aggregate" to "unboxed-tuple", "components" to emptyList<Any>())
            val input = broadcastModule(listOf("lit", "word16", "1", mapOf("rep" to unresolved)))
            val reason = "Unsupported Core aggregate representation: unboxed-tuple has unresolved fields"
            if (!diagnostic) assertEquals(reason, assertThrows(UnsupportedCore::class.java) {
                program(language, backend, input, "root")
            }.message) else {
                // Existing diagnostic policy defers unresolved aggregates, but
                // cannot execute them as an unsigned scalar or silently erase them.
                val p = program(language, backend, input, "root", true)
                assertTrue((p.diagnostics().getValue("deferredUnsupported") as List<*>).contains(reason))
                assertEquals(0L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                val failure = assertThrows(RuntimeFault::class.java) {
                    Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue("root"), arrayOf(0L)))
                }
                assertEquals("Diagnostic unsupported path reached: $reason", failure.message)
                assertEquals(1L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                val state = language.handoffState.get()
                assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
            }
        }
    }

    @Test fun canonicalWord16LiteralsAndAlternativesRejectOutOfRangeAndNoncanonicalText() = withLanguage { language ->
        val lane = mapOf("kind" to "long", "primReps" to listOf("Word16Rep"), "evaluated" to true)
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        fun alternative(text: String): Map<String, Any?> {
            val base = broadcastModule(listOf("lit", "word16", "0"))
            val binding = (base["bindings"] as List<Map<String, Any?>>).single()
            val lambda = (binding["expr"] as List<Any?>).toMutableList()
            val word = mapOf("kind" to "long", "primReps" to listOf("WordRep"), "evaluated" to true)
            val converted = listOf("app", listOf("prim", "int2Word#"), listOf(listOf("var", "unused", mapOf("rep" to long))),
                listOf(false), false, true, mapOf("rep" to word))
            val narrowed = listOf("app", listOf("prim", "wordToWord16#"), listOf(converted),
                listOf(false), false, true, mapOf("rep" to lane))
            lambda[2] = listOf("case", narrowed, "x", listOf(
                listOf("lit", listOf("word16", text), emptyList<String>(), listOf("lit", "int", "1", mapOf("rep" to long))),
                listOf("default", null, emptyList<String>(), listOf("lit", "int", "0", mapOf("rep" to long)))),
                mapOf("rep" to long, "binder" to mapOf("id" to "x", "lifted" to false, "rep" to lane)))
            return base + ("bindings" to listOf(binding + ("expr" to lambda)))
        }
        val malformed = listOf("-1", "-32768", "65536", "", "+1", "01", "-0", " 1", "1.0", "18446744073709551616")
        for (value in 0L..65535L) assertEquals(value, narrowWordLiteral("word16", value.toString()))
        for (text in malformed) assertThrows(RuntimeFault::class.java) { narrowWordLiteral("word16", text) }
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            for (text in malformed) for (input in listOf(broadcastModule(listOf("lit", "word16", text, mapOf("rep" to lane))), alternative(text)))
                assertThrows(RuntimeFault::class.java) { program(language, backend, input, "root", diagnostic) }
            for (value in listOf(0L, 1L, 32767L, 32768L, 65535L)) {
                val p = program(language, backend, alternative(value.toString()), "root", diagnostic)
                assertEquals(1L, Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue("root"), arrayOf(value))))
            }
        }
    }

    @Test fun bothLoadersRetainVectorBoundaryAndRejectForgedShapes() = withLanguage { language ->
        fun rewrite(value: Any?, mutate: (Map<String, Any?>) -> Map<String, Any?>): Any? = when (value) {
            is Map<*, *> -> (value as Map<String, Any?>).let { m -> if (m["kind"] == "vector") mutate(m) else m.mapValues { rewrite(it.value, mutate) } }
            is List<*> -> value.map { rewrite(it, mutate) }
            else -> value
        }
        for (backend in listOf("ast", "bytecode")) {
            assertNotNull(program(language, backend, module(), "vectorArgument"))
            for (diagnostic in listOf(false, true)) {
                val modified = rewrite(module()) { it - "vector" } as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, modified, "plusCase", diagnostic) }
                val wrong = rewrite(module()) { it + mapOf("primReps" to listOf("VecRep 4 Int32ElemRep"),
                    "vector" to mapOf("lanes" to 4L, "element" to "Int32ElemRep")) } as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, wrong, "plusCase", diagnostic) }
                val signed = rewrite(module()) { it + mapOf("primReps" to listOf("VecRep 8 Int16ElemRep"),
                    "vector" to mapOf("lanes" to 8L, "element" to "Int16ElemRep")) } as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, signed, "plusCase", diagnostic) }
            }
        }
    }

    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry); return targets
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    @Test fun nativeCoreHasExactCompiledEntriesWithInlining() = nativeCore(true)
    @Test fun nativeCoreHasExactCompiledEntriesWithoutInlining() = nativeCore(false)
    private fun nativeCore(inlining: Boolean) {
        val provenance = Json.parse(File(directory, "provenance.json").readText()) as Map<String, Any?>
        val stages = provenance["stages"] as List<String>
        assertTrue("pre" in stages); assertEquals(true, provenance["positiveAuditsAccepted"])
        for (file in (provenance["sources"] as List<Map<String, String>>) + (provenance["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, file.getValue("path")).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(file["sha256"], hash, "Stale Word16X8 source/artifact: ${file["path"]}")
        }
        fun rows(filename: String) = File(directory, filename).readLines().associate { row ->
            val parts = row.split('\t'); (parts.first() to parts.drop(1).dropLast(1).map(String::toLong)) to parts.last().toLong()
        }
        val expected = rows("expected.tsv")
        if (provenance["nativeRows"] != null) {
            assertEquals(expected, rows("oracle.tsv")); assertEquals(expected.size.toLong(), (provenance["nativeRows"] as Number).toLong())
        }
        val entries = provenance["entries"] as List<Map<String, Any?>>
        val declared = entries.flatMap { entry -> (entry["cases"] as List<List<Number>>).map { entry["name"] to it.map(Number::toLong) } }.toSet()
        assertEquals(declared, expected.keys)
        val counts = provenance["expectedGuestCallsByEntry"] as Map<String, Number>
        for (stage in stages) for (backend in listOf("ast", "bytecode")) withLanguage(inlining) { language ->
            for (entry in entries) {
                val name = entry["name"] as String; val arity = (entry["arity"] as Number).toInt()
                val cases = (entry["cases"] as List<List<Number>>).map { row -> row.map(Number::toLong) }
                assertTrue(cases.isNotEmpty()); assertTrue(cases.all { it.size == arity })
                val p = program(language, backend, module(stage), name)
                val host = p.hostEntryTarget(arity); val closure = p.entryValue(name); val target = p.entryTarget(name)
                val callCount = counts.getValue(name).toLong()
                assertEquals(if (name in listOf("scalarHelperCase", "tupleHelperCase")) 2L else 1L, callCount)
                assertEquals(callCount, (provenance["checkedGuestCallsByStage"] as Map<String, Number>).getValue("$stage/$name").toLong())
                var targets = emptyList<RootCallTarget>()
                fun check(compiled: Boolean) {
                    for (input in cases) {
                        val label = "$stage/$backend/$name/$input/inlining=$inlining"
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected.getValue(name to input), Calls.target(host, arrayOf(closure, input.toTypedArray())), label)
                        if (compiled) {
                            assertEquals(before + callCount, (p.diagnostics().getValue("compiledEntries") as Number).toLong(), "$label compiled guest entries")
                            val active = activeTargets(target)
                            assertEquals(targets.size, active.size, "$label active target count")
                            assertTrue(active.all { candidate -> targets.any { it === candidate } }, "$label active identities")
                            targets.forEach { valid(it, label) }
                            val calls = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java).filter { it.callTarget === target }
                            assertTrue(calls.isNotEmpty(), "$label selected entry")
                            calls.forEach { assertSame(target, it.currentCallTarget, "$label selected identity") }
                        }
                        val state = language.handoffState.get()
                        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
                    }
                }
                check(false)
                targets = activeTargets(target)
                assertEquals(callCount.toInt(), targets.size, "$stage/$backend/$name guest roots")
                for (t in targets) { t.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(t, true); valid(t, "initial installation") }
                check(true)
                for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong(), counter)
            }
        }
    }
}
