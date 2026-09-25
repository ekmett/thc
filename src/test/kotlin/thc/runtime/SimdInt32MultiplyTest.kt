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

class SimdInt32MultiplyTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-int32x4-multiply")
    private fun module(stage: String = "pre") = Json.parse(File(directory, "$stage-core/SimdInt32X4Multiply.json").readText()) as Map<String, Any?>
    private fun metadata() = mapOf("kind" to "vector", "evaluated" to true,
        "primReps" to listOf("VecRep 4 Int32ElemRep"), "vector" to mapOf("lanes" to 4L, "element" to "Int32ElemRep"))
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
    private fun signed(value: Long): Long = ((value and 0xffff_ffffL) xor 0x8000_0000L) - 0x8000_0000L
    private fun lanes(value: Int32X4) = listOf(value.first.toLong(), value.second.toLong(), value.third.toLong(), value.fourth.toLong())
    private fun pack(values: List<Long>) = Int32X4(values[0].toInt(), values[1].toInt(), values[2].toInt(), values[3].toInt())

    @Test fun multiplicationKeepsExactSignedFourLaneContract() {
        val proof = CoreRepresentations.parse(metadata())
        assertEquals(CoreVectors.proof32, proof)
        assertEquals(7, CoreVectors.operations32.size)
        assertTrue("timesInt32X4#" in CoreVectors.operations32)
        val fields = Int32X4::class.java.declaredFields
        assertEquals(List(4) { Int::class.javaPrimitiveType }, fields.map { it.type })
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers) })
        assertEquals(List(4) { "Int32Rep" }, CoreVectors.unpacked32.primReps)
        CoreVectors.validate("timesInt32X4#", listOf(proof, proof), proof)
        for (arity in listOf(0, 1, 3)) assertThrows(RuntimeFault::class.java) {
            CoreVectors.validate("timesInt32X4#", List(arity) { proof }, proof)
        }
        for (wrong in listOf(CoreVectors.proofWord32, CoreVectors.proof16, CoreVectors.proofWord16,
            CoreVectors.proof8, CoreVectors.proofWord8, CoreVectors.proof, CoreVectors.proofFloat, CoreVectors.unpacked32)) {
            for (index in 0 until 2) assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate("timesInt32X4#", List(2) { if (it == index) wrong else proof }, proof)
            }
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate("timesInt32X4#", listOf(proof, proof), wrong) }
        }
        assertThrows(RuntimeFault::class.java) {
            CoreVectors.validate("timesWord32X4#", listOf(CoreVectors.proofWord32, proof), CoreVectors.proofWord32)
        }
        for (lane in 0 until 4) for (wrong in listOf("Word32Rep", "IntRep", "Int16Rep")) {
            val bad = CoreVectors.unpacked32.copy(components = CoreVectors.unpacked32.components!!.mapIndexed { i, p ->
                if (i == lane) p.copy(primReps = listOf(wrong)) else p })
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packInt32X4#", listOf(bad), proof) }
        }
    }

    @Test fun productsKeepLowBitsThenSignExtendWithoutSaturationOrHighProduct() {
        fun check(a: List<Long>, b: List<Long>, independent: Boolean = false) {
            assertEquals(a, lanes(pack(a))); assertEquals(b, lanes(pack(b)))
            val expected = a.zip(b).map {
                if (independent) signed(java.math.BigInteger.valueOf(it.first).multiply(java.math.BigInteger.valueOf(it.second))
                    .and(java.math.BigInteger.valueOf(0xffff_ffffL)).toLong())
                else signed(it.first * it.second)
            }
            assertEquals(expected, lanes(Int32X4.multiply(pack(a), pack(b))))
        }
        // Both halves visit every 16-bit encoding in every lane. The constructed
        // samples are not exhaustive coverage of 2^32 words or 2^64 operand pairs.
        for (bits in 0L..65535L) {
            val a = List(4) { signed(((bits + it * 7919L) and 65535L) or (((bits * 40503L + it * 3571L) and 65535L) shl 16)) }
            val b = List(4) { signed((bits * (2 * it + 1) + 32767L) * 65537L + it * 1000000007L) }
            check(a, b)
        }
        val edges = (listOf(-2147483648L, -2147483647L, -65536L, -65535L, -1L, 0L, 1L, 2L,
            65535L, 65536L, 1073741824L, 2147483646L, 2147483647L) +
            (0 until 31).flatMap { bit -> (-1L..1L).flatMap { delta -> listOf((1L shl bit) + delta, -((1L shl bit) + delta)) } }).distinct()
        for (lane in 0 until 4) for (x in edges) for (y in edges) {
            val a = MutableList(4) { signed(0x8000_0001L + it * 1000000007L) }; a[lane] = x
            val b = MutableList(4) { signed(0xffff_fffeL - it * 591558727L) }; b[lane] = y
            check(a, b, true)
        }
        assertEquals(List(4) { -2147483648L }, lanes(Int32X4.multiply(
            pack(List(4) { -2147483648L }), pack(List(4) { -1L }))))
        assertEquals(List(4) { -2L }, lanes(Int32X4.multiply(
            pack(List(4) { 2147483647L }), pack(List(4) { 2L }))))
    }

    private fun multiplyModule(literal: List<Any?>, flags: List<Any?> = listOf(false, false)): Map<String, Any?> {
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val lane = mapOf("kind" to "long", "primReps" to listOf("Int32Rep"), "evaluated" to true)
        fun broadcast(value: List<Any?>) = listOf("app", listOf("prim", "broadcastInt32X4#", mapOf("rep" to closure)),
            listOf(value), listOf(false), false, true, mapOf("rep" to metadata()))
        val product = listOf("app", listOf("prim", "timesInt32X4#", mapOf("rep" to closure)),
            listOf(broadcast(literal), broadcast(listOf("lit", "int32", "-1", mapOf("rep" to lane)))),
            flags, false, true, mapOf("rep" to metadata()))
        val body = listOf("case", product, "v", listOf(listOf("default", null, emptyList<String>(),
            listOf("lit", "int", "1", mapOf("rep" to long)), mapOf("binders" to emptyList<Any>()))),
            mapOf("rep" to long, "binder" to mapOf("id" to "v", "lifted" to false, "rep" to metadata())))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "constructors" to emptyList<Any>(), "bindings" to listOf(
            mapOf("id" to "root", "name" to "root", "arity" to 1, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", listOf(mapOf("id" to "unused", "name" to "unused", "lifted" to false, "rep" to long)),
                    body, mapOf("rep" to closure, "resultRep" to long)))))
    }

    @Test fun bothLoadersRequireUnliftedOperandsAndCanonicalSignedLiterals() = withLanguage { language ->
        val lane = mapOf("kind" to "long", "primReps" to listOf("Int32Rep"), "evaluated" to true)
        val unconstrained = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
        val exact = listOf("lit", "int32", "-2147483648", mapOf("rep" to lane))
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            for (literal in listOf(exact, listOf("lit", "int32", "2147483647"),
                listOf("lit", "int32", "-1", mapOf("rep" to unconstrained)))) {
                val p = program(language, backend, multiplyModule(literal), "root", diagnostic)
                assertEquals(1L, Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue("root"), arrayOf(0L))))
            }
            for (index in 0 until 2) for (flag in listOf(true, null, 0L, "false")) assertThrows(RuntimeFault::class.java) {
                program(language, backend, multiplyModule(exact, List(2) { if (it == index) flag else false }), "root", diagnostic)
            }
            for (text in listOf("-2147483649", "2147483648", "", "+1", "01", "-0", " 1", "1.0")) {
                assertThrows(RuntimeFault::class.java) {
                    program(language, backend, multiplyModule(listOf("lit", "int32", text, mapOf("rep" to lane))), "root", diagnostic)
                }
            }
            for (literal in listOf(listOf("lit", "word32", "4294967295"),
                listOf("lit", "word32", "4294967295", mapOf("rep" to lane)),
                listOf("lit", "int32", "-1", mapOf("rep" to (lane + ("primReps" to listOf("Word32Rep"))))))) {
                assertThrows(RuntimeFault::class.java) { program(language, backend, multiplyModule(literal), "root", diagnostic) }
            }
        }
    }

    @Test fun bothLoadersRetainSignednessAndCallingFrontier() = withLanguage { language ->
        fun rewrite(value: Any?, mutate: (Map<String, Any?>) -> Map<String, Any?>): Any? = when (value) {
            is Map<*, *> -> (value as Map<String, Any?>).let { m ->
                if (m["kind"] == "vector") mutate(m) else m.mapValues { rewrite(it.value, mutate) } }
            is List<*> -> value.map { rewrite(it, mutate) }
            else -> value
        }
        for (backend in listOf("ast", "bytecode")) {
            assertNotNull(program(language, backend, module(), "vectorArgument"))
            for (diagnostic in listOf(false, true)) {
                val missing = rewrite(module()) { it - "vector" } as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, missing, "timesCase", diagnostic) }
                val unsigned = rewrite(module()) { it + mapOf("primReps" to listOf("VecRep 4 Word32ElemRep"),
                    "vector" to mapOf("lanes" to 4L, "element" to "Word32ElemRep")) } as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, unsigned, "timesCase", diagnostic) }
                val width = rewrite(module()) { it + mapOf("primReps" to listOf("VecRep 8 Int16ElemRep"),
                    "vector" to mapOf("lanes" to 8L, "element" to "Int16ElemRep")) } as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, width, "timesCase", diagnostic) }
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
            assertEquals(file["sha256"], hash, "Stale Int32X4 multiply source/artifact: ${file["path"]}")
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
