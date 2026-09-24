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
import thc.*
import java.io.File
import java.math.BigInteger
import java.nio.ByteOrder
import org.graalvm.polyglot.PolyglotException
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class BigNatLiteralTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("integerRoundTrip", "naturalRoundTrip", "integerLiteral", "naturalLiteral",
        "magnitudeSize", "magnitudeByte", "magnitudeWord", "magnitudeSign")
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial installation")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry); return targets
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }

    private data class Row(val name: String, val seed: Long, val index: Long, val expected: Long)
    private fun expected(name: String, seed: Long, index: Long, values: List<BigInteger>): Long {
        val signed = values[(seed and 15).toInt()]; val magnitude = signed.abs()
        val length = ((magnitude.bitLength() + 63) / 64) * 8
        return when (name) {
            "integerRoundTrip", "naturalRoundTrip" -> seed
            "integerLiteral" -> signed.toLong()
            "naturalLiteral" -> magnitude.toLong()
            "magnitudeSize" -> length.toLong()
            "magnitudeSign" -> if (signed.signum() < 0) 1L else 0L
            "magnitudeWord" -> if (index < 0 || index >= length / 8) -1 else magnitude.shiftRight(index.toInt()*64).toLong()
            "magnitudeByte" -> if (index < 0 || index >= length) -1 else {
                val position = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) index else (index/8)*8+7-index%8
                magnitude.shiftRight(position.toInt()*8).and(BigInteger.valueOf(255)).toLong()
            }
            else -> error(name)
        }
    }
    @Test fun originalIntegerNaturalConversionsWithInlining() = native(true)
    @Test fun originalIntegerNaturalConversionsAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = Json.parse(File(root, "build/bignat-literals/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("sources", "artifacts")) for (item in manifest[kind] as List<Map<String, String>>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, item.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(item["sha256"], hash, "Stale BigNat preparation: ${item["path"]}")
        }
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(entries, manifest["entries"])
        val values = (manifest["values"] as List<String>).map(::BigInteger)
        val seeds = (manifest["seeds"] as List<Number>).map { it.toLong() }
        assertEquals(16, values.size)
        assertTrue(seeds.containsAll(listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1)))
        val rows = File(root, "build/bignat-literals/oracle.tsv").readLines().map { line ->
            val p = line.split('\t'); Row(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong())
        }
        val modeled = buildList {
            for (name in entries) for (seed in seeds) {
                val length = ((values[(seed and 15).toInt()].abs().bitLength()+63)/64)*8
                val indices = when (name) { "magnitudeByte" -> -1L..length.toLong()
                    "magnitudeWord" -> -1L..(length/8).toLong(); else -> 0L..0L }
                for (index in indices) add(Row(name, seed, index, expected(name, seed, index, values)))
            }
        }
        assertEquals(modeled, rows, "Every size, sign, limb, byte, sentinel and wrapped public conversion")
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.size)
        val stages = manifest["stages"] as Map<String, List<String>>
        for ((stage, paths) in stages) {
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            for (name in entries) {
                val audit = Json.parse(File(root, "build/bignat-literals/$stage-$name.audit.json").readText()) as Map<String, Any?>
                assertEquals(true, audit["accepted"])
                val selected = rows.filter { it.name == name }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val instrumented = CoreModules.reachable(module, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, instrumented) else BytecodeProgram(language, instrumented)
                        val function = context.asValue(EntryValue(program, name, 2))
                        val host = program.hostEntryTarget(2); val original = program.entryTarget(name)
                        val worker = "ghc-internal:GHC.Internal.Bignum." + when {
                            name.startsWith("integer") -> "Integer.integerToInt#"
                            name.startsWith("natural") -> "Natural.naturalToWord#"
                            else -> "Integer.integerToBigNatSign#"
                        }
                        assertTrue((audit["reachableBindings"] as List<Map<String, Any?>>).any { it["id"] == worker })
                        val workerTarget = program.entryTarget(worker)
                        val label = "$stage/$backend/$name/inlining=$inlining"
                        fun check(row: Row) {
                            assertEquals(row.expected, function.execute(row.seed, row.index).asLong(), "$label/${row.seed}/${row.index}")
                            released(language)
                        }
                        // Warm the unchanged native corpus once; no settling or retries.
                        selected.forEach(::check)
                        val targets = activeTargets(host)
                        assertTrue(targets.size > 1, "$label adopted guest path")
                        targets.filter { it !== host }.forEach(::compile)
                        compile(workerTarget)
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val allocations = language.handoffState.get().results.allocations
                        for (row in selected.asReversed()) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before, "$label actual compiled entry")
                            assertEquals(targets, activeTargets(host), "$label active identities")
                            valid(original, "$label original"); valid(workerTarget, "$label original worker")
                            targets.forEach { valid(it, "$label active") }
                        }
                        assertEquals(allocations, language.handoffState.get().results.allocations, "$label result slabs reused")
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        println("BigNatLiteral PASS $label rows=${selected.size} activeTargets=${targets.size}")
                    } finally { context.leave() }
                }
            }
        }
    }
    @Test fun canonicalPrivateBytesAndCheckedSizeMatchGhcLimbLayout() {
        for (bits in listOf(0, 1, 63, 64, 65, 127, 128, 129, 192, 256)) {
            val number = if (bits == 0) BigInteger.ZERO else BigInteger.ONE.shiftLeft(bits-1).add(BigInteger.ONE)
            val first = BigNatLiterals.decode(number.toString()); val second = BigNatLiterals.decode(number.toString())
            assertNotSame(first, second); assertArrayEquals(first, second)
            assertEquals(((number.bitLength()+63)/64)*8, first.size)
            var reconstructed = BigInteger.ZERO
            for (index in first.indices) {
                val position = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) index else (index/8)*8+7-index%8
                reconstructed = reconstructed.or(BigInteger.valueOf((first[index].toInt() and 255).toLong()).shiftLeft(position*8))
            }
            assertEquals(number, reconstructed)
            if (first.isNotEmpty()) { first[0] = (first[0].toInt() xor 255).toByte(); assertFalse(first.contentEquals(second)) }
        }
        assertEquals(0, BigNatLiterals.byteSize(0))
        assertEquals(2147483640, BigNatLiterals.byteSize(17179869120))
        for (bits in listOf(-1L, 17179869121L, Long.MAX_VALUE)) assertThrows(RuntimeFault::class.java) { BigNatLiterals.byteSize(bits) }
        for (value in listOf("", "-1", "+1", "00", "01", " 1", "1 ", "1.0", "0x10", "١"))
            assertThrows(RuntimeFault::class.java) { BigNatLiterals.decode(value) }
    }
    private fun request(backend: String, body: List<Any?>): String {
        val binding = mapOf("id" to "root", "name" to "root", "lifted" to true, "arity" to 1,
            "expr" to listOf("lam", listOf(mapOf("id" to "x", "lifted" to false)), body))
        return Json.stringify(mapOf("entry" to "root", "backend" to backend, "modules" to listOf(
            mapOf("schema" to 1, "ghc" to "9.14.1", "constructors" to emptyList<Any>(), "bindings" to listOf(binding)))))
    }
    @Test fun exactUnliftedLiteralProofRejectsScalarAggregateAndBoxedForgeries() {
        val exact = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
        val bad = listOf(
            exact + ("primReps" to listOf("BoxedRep (Just Lifted)")), exact + ("primReps" to listOf("BoxedRep Nothing")),
            exact + ("kind" to "unknown"),
            mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true),
            mapOf("kind" to "long", "primReps" to listOf("WordRep"), "evaluated" to true),
            mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true),
            mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
                "aggregate" to "unboxed-tuple", "components" to emptyList<Any>()),
            mapOf("kind" to "unknown", "primReps" to listOf("WordRep"), "evaluated" to true,
                "aggregate" to "unboxed-sum", "tagSlot" to 0, "alternativeSlots" to listOf(emptyList<Int>(), emptyList<Int>()),
                "alternatives" to List(2) { mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true) }))
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            fun size(proof: Map<String, Any?>?) = listOf("app", listOf("prim", "sizeofByteArray#"),
                listOf(listOf("lit", "bignat", "18446744073709551616") + if (proof == null) emptyList() else listOf(mapOf("rep" to proof))),
                listOf(false), false, false, mapOf("rep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)))
            for (proof in listOf(exact, null, mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)))
                assertEquals(16L, context.eval("thc", request(backend, size(proof))).execute(0L).asLong())
            for (proof in bad) assertThrows(PolyglotException::class.java) { context.eval("thc", request(backend, size(proof))) }
        }
    }
    @Test fun bignatLiteralAlternativesRemainForbidden() {
        val body = listOf("case", listOf("lit", "int", "0"), "scrutinee", listOf(
            listOf("lit", listOf("bignat", "0"), emptyList<String>(), listOf("lit", "int", "1")),
            listOf("default", null, emptyList<String>(), listOf("lit", "int", "0"))))
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            val failure = assertThrows(PolyglotException::class.java) { context.eval("thc", request(backend, body)) }
            assertTrue(failure.message.orEmpty().contains("BigNat literal alternatives are invalid GHC Core"), failure.message)
        }
    }
}
