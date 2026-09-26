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
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.io.File
import java.math.BigInteger
import java.nio.ByteOrder
import java.nio.file.Path
import org.graalvm.polyglot.PolyglotException
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

class BigNatLiteralTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("integerRoundTrip", "naturalRoundTrip", "integerLiteral", "naturalLiteral",
        "magnitudeSize", "magnitudeByte", "magnitudeWord", "magnitudeSign")
    private val arithmetic = listOf("integerAddFrontier", "naturalAddFrontier")
    private val modules = listOf("BigNat", "Integer", "Natural")
    private val directory = "build/bignat-literals"
    private val values = listOf("0", "1", "-1", "9223372036854775807", "9223372036854775808",
        "18446744073709551615", "18446744073709551616", "18446744073709551617",
        "170141183460469231731687303715884105727", "170141183460469231731687303715884105728",
        "340282366920938463463374607431768211456", "340282366920938463472597979468622987265",
        "-340282366920938463481821351505477763071", "6277101735386680763835789423207666416102355444464034512895",
        "6277101735386680763835789423207666416102355444464034512897",
        "57896044618658097711785492504343953926975274699741220483192166611388333031427").map(::BigInteger)
    private val seeds = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1000L, -17L, -1L) + (0L..16L) + listOf(31L, 1L shl 32)
    private val vendorSources = modules.flatMap { name -> listOf(".hs", ".hs-boot").map {
        "vendor/ghc-9.14.1/GHC/Internal/Bignum/$name$it" } } +
        listOf("vendor/ghc-9.14.1/include/WordSize.h", "vendor/ghc-9.14.1/LICENSE")
    private fun evidence() = Json.parse(File(root, "$directory/manifest.json").readText()) as Map<String, Any?>
    private fun report(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun verifyEvidence(manifest: Map<String, Any?>) {
        assertEquals(1L, manifest["schema"]); assertEquals(699L, manifest["nativeRows"])
        assertEquals(64L, manifest["wordBits"])
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(entries, manifest["entries"]); assertEquals(arithmetic, manifest["arithmeticControls"])
        assertEquals(listOf("integerAddFrontier"), manifest["frontiers"])
        assertEquals(values.map { it.toString() }, manifest["values"]); assertEquals(seeds, manifest["seeds"])
        assertEquals(listOf("pre", "post").associateWith { stage -> listOf("$directory/$stage-core/BigNatLiteralAudit.json") +
            modules.map { "$directory/boot/core/GHC.Internal.Bignum.$it.json" } }, manifest["stages"])
        val sources = vendorSources + listOf("compiler/test-fixtures/BigNatLiteralAudit.hs", "compiler/test-fixtures/BigNatLiteralAuditNative.hs",
            "test/haskell-fixtures/BigNatLiteralFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs",
            "thc.cabal", "compiler/export-boot.py", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
            "scripts/audit-core.py", "scripts/core-capabilities.json", "scripts/generate-scalar-signatures.py",
            "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        val commands = listOf("plugin-build", "boot-export", "native-build", "native-oracle") + listOf("pre", "post").flatMap { stage ->
            listOf("$stage-export") + (entries + arithmetic + "missing-source").map { "$stage-$it-audit" } }
        val artifacts = listOf("$directory/requests.tsv", "$directory/oracle.tsv", "$directory/boot/boot-provenance.json") +
            modules.map { "$directory/boot/core/GHC.Internal.Bignum.$it.json" } +
            listOf("bignat-literal-oracle", "Main.hi", "Main.o", "BigNatLiteralAudit.hi", "BigNatLiteralAudit.o").map { "$directory/native/$it" } +
            listOf("pre", "post").flatMap { stage -> listOf("$directory/$stage-core/BigNatLiteralAudit.json", "$directory/$stage-core/THC.InterfaceClosure.json") +
                (entries + arithmetic + "missing-source").map { "$directory/$stage-$it.audit.json" } } +
            commands.flatMap { name -> listOf("stdout", "stderr", "command.json").map { "$directory/commands/$name.$it" } }
        for ((kind, required) in listOf("sources" to sources, "artifacts" to artifacts)) {
            val records = manifest[kind] as List<Map<String, String>>
            assertEquals(required.sorted(), records.map { it.getValue("path") }.sorted(), "$kind exact inventory")
            for (item in records) {
                val hash = MessageDigest.getInstance("SHA-256").digest(File(root, item.getValue("path")).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(item["sha256"], hash, "Stale BigNat preparation: ${item["path"]}")
            }
        }
        val bootSources = report("$directory/boot/boot-provenance.json")["sources"] as List<Map<String, Any?>>
        assertEquals(vendorSources.toSet(), bootSources.map { it["path"] }.toSet())
        for (source in bootSources) assertTrue((manifest["sources"] as List<Map<String, Any?>>).any {
            it["path"] == source["path"] && it["sha256"] == source["sha256"] })
        val counts = manifest["sourceBindings"] as Map<String, Long>
        assertEquals(modules.toSet(), counts.keys)
        val originalIds = modules.flatMap { name ->
            val original = report("$directory/boot/core/GHC.Internal.Bignum.$name.json")
            assertEquals("9.14.1", original["ghc"])
            assertEquals("optimized-Core-after-Tidy-before-CorePrep", original["boundary"])
            assertNotNull(original["sourceCore"]); assertNotNull(original["sourceSpans"])
            val bindings = original["bindings"] as List<Map<String, Any?>>
            assertEquals(counts[name], bindings.size.toLong())
            bindings.map { it["id"] }
        }.toSet()
        val coverage = manifest["coverage"] as Map<String, Map<String, Map<String, Any?>>>
        assertEquals(setOf("pre", "post"), coverage.keys)
        for (stage in listOf("pre", "post")) {
            val public = report("$directory/$stage-core/BigNatLiteralAudit.json")
            assertEquals("9.14.1", public["ghc"])
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep", public["boundary"])
            val closure = report("$directory/$stage-core/THC.InterfaceClosure.json")["bindings"] as List<Map<String, Any?>>
            assertTrue(originalIds.containsAll(closure.map { it["id"] }))
            assertEquals((entries + arithmetic).toSet(), coverage.getValue(stage).keys)
            for (name in entries + arithmetic) {
                val audit = report("$directory/$stage-$name.audit.json")
                verifyAudit(name, audit)
                assertEquals(mapOf("accepted" to audit["accepted"], "reachable" to (audit["reachableBindings"] as List<*>).size.toLong(),
                    "issues" to (audit["issues"] as List<*>).size.toLong(), "missing" to (audit["missingGlobals"] as List<*>).size.toLong()),
                    coverage.getValue(stage)[name])
            }
            verifyAudit("missing-source", report("$directory/$stage-missing-source.audit.json"))
        }
    }
    private fun verifyAudit(name: String, audit: Map<String, Any?>) {
        assertEquals(emptyList<Any>(), audit["issues"])
        val missing = (audit["missingGlobals"] as List<Map<String, Any?>>).map { it["id"] }.sortedBy { it.toString() }
        if (name == "missing-source") {
            assertEquals(false, audit["accepted"])
            assertEquals(listOf("BigNat.bigNatZero", "Integer.integerToInt#", "Natural.naturalToWord#")
                .map { "ghc-internal:GHC.Internal.Bignum.$it" }, missing)
        } else {
            assertEquals(name != "integerAddFrontier", audit["accepted"])
            assertEquals(if (name == "integerAddFrontier") listOf("ghc-internal:GHC.Internal.Prim.Exception.raiseUnderflow") else emptyList<String>(), missing)
            if (name in arithmetic) {
                val wanted = mapOf("__gmpn_add" to 2, "__gmpn_add_1" to 1) +
                    if (name == "integerAddFrontier") mapOf("__gmpn_cmp" to 1, "__gmpn_sub" to 1) else emptyMap()
                assertEquals(wanted, (audit["foreignCalls"] as List<Map<String, Any?>>).groupingBy { it["symbol"] }.eachCount())
                assertEquals(if (name == "integerAddFrontier") 7 else 5, (audit["primitives"] as List<Map<String, Any?>>)
                    .filter { it["name"] == "shrinkMutableByteArray#" }.sumOf { (it["uses"] as List<*>).size })
            }
        }
    }
    @Test fun evidenceRejectsMissingHashesDomainsAndChangedFrontiers() {
        val good = evidence(); verifyEvidence(good)
        for ((key, value) in listOf("nativeRows" to 698L, "values" to emptyList<String>(), "seeds" to listOf(0L),
            "entries" to entries.dropLast(1), "stages" to emptyMap<String, Any>(), "sourceBindings" to emptyMap<String, Long>(),
            "coverage" to emptyMap<String, Any>(), "frontiers" to emptyList<String>(), "arithmeticControls" to emptyList<String>()))
            assertThrows(AssertionError::class.java, { verifyEvidence(good + (key to value)) }, key)
        for (kind in listOf("sources", "artifacts")) {
            val records = good[kind] as List<Map<String, String>>
            for (record in records) assertThrows(AssertionError::class.java, {
                verifyEvidence(good + (kind to (records - record))) }, "$kind/${record["path"]}")
            assertThrows(AssertionError::class.java) { verifyEvidence(good + (kind to
                (listOf(records.first() + ("sha256" to "0".repeat(64))) + records.drop(1)))) }
        }
        for (stage in listOf("pre", "post")) for (name in arithmetic + "missing-source") {
            val goodAudit = report("$directory/$stage-$name.audit.json")
            for ((key, value) in listOf("accepted" to !(goodAudit["accepted"] as Boolean), "issues" to listOf("unexpected")))
                assertThrows(AssertionError::class.java) { verifyAudit(name, goodAudit + (key to value)) }
            if (name != "naturalAddFrontier") assertThrows(AssertionError::class.java) {
                verifyAudit(name, goodAudit + ("missingGlobals" to emptyList<Any>())) }
            if (name in arithmetic) for (key in listOf("foreignCalls", "primitives")) assertThrows(AssertionError::class.java) {
                verifyAudit(name, goodAudit + (key to emptyList<Any>())) }
        }
    }
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
        val manifest = evidence(); verifyEvidence(manifest)
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
        assertEquals(699, rows.distinct().size)
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
    @Test fun sharedAuditorRetainsCanonicalIntrinsicAndMalformedControls(@TempDir temporary: Path) {
        val exact = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
        val unknown = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
        var serial = 0
        fun audit(body: List<Any?>, accepted: Boolean, issue: String? = null) {
            val module = ((Json.parse(request("ast", body)) as Map<*, *>)["modules"] as List<*>).single()
            val name = "control-${serial++}"
            val source = temporary.resolve("$name.json").toFile().apply { writeText(Json.stringify(module)) }
            val output = temporary.resolve("$name-report.json").toFile()
            val process = ProcessBuilder("python3", "scripts/audit-core.py", source.path, "--entry", "root", "--output", output.path)
                .directory(root).redirectOutput(temporary.resolve("$name.stdout").toFile())
                .redirectError(temporary.resolve("$name.stderr").toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(); fail<Unit>("Shared BigNat auditor timed out: $name")
            }
            assertEquals(if (accepted) 0 else 1, process.exitValue(), name)
            val actual = Json.parse(output.readText()) as Map<*, *>
            assertEquals(accepted, actual["accepted"], name)
            if (accepted) { assertEquals(emptyList<Any>(), actual["issues"]); assertEquals(emptyList<Any>(), actual["missingGlobals"]) }
            else {
                val issues = actual["issues"] as List<Map<String, Any?>>
                assertTrue(issues.isNotEmpty(), name)
                if (issue != null) assertTrue(issues.any { it["code"] == issue }, "$name/$issue")
            }
        }
        fun literal(value: String, proof: Map<String, Any?>?) = listOf("lit", "bignat", value) +
            if (proof == null) emptyList() else listOf(mapOf("rep" to proof))
        fun size(literal: List<Any?>) = listOf("app", listOf("prim", "sizeofByteArray#"), listOf(literal),
            listOf(false), false, false, mapOf("rep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)))
        for (value in listOf("0", "1", values.last().toString())) for (proof in listOf(exact, null, unknown)) {
            audit(literal(value, proof), true)
            // CLI consumption checks the recovered ByteArray# kind/PrimRep.
            // The auditor API self-test separately asserts evaluated=true.
            audit(size(literal(value, proof)), true)
        }
        for (value in listOf("", "-1", "+1", "00", "01", " 1", "1 ", "1.0", "0x10", "١"))
            audit(literal(value, exact), false, "invalid-literal-value")
        val bad = listOf("long" to "IntRep", "long" to "WordRep", "object" to "BoxedRep (Just Lifted)",
            "object" to "BoxedRep Nothing", "unknown" to "BoxedRep (Just Unlifted)", "data" to "BoxedRep (Just Unlifted)",
            "closure" to "BoxedRep (Just Unlifted)").map { (kind, rep) -> mapOf("kind" to kind, "primReps" to listOf(rep), "evaluated" to true) } + listOf(
            mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true),
            mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true, "aggregate" to "unboxed-tuple", "components" to emptyList<Any>()),
            mapOf("kind" to "unknown", "primReps" to listOf("WordRep"), "evaluated" to true, "aggregate" to "unboxed-sum", "tagSlot" to 0,
                "alternativeSlots" to listOf(emptyList<Int>(), emptyList<Int>()), "alternatives" to List(2) { mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true) }),
            mapOf("kind" to "vector", "primReps" to listOf("VecRep 2 Int64ElemRep"), "evaluated" to true, "vector" to mapOf("lanes" to 2, "element" to "Int64ElemRep")))
        for (proof in bad) audit(literal("1", proof), false)
        for (proof in listOf(exact, null, unknown)) audit(size(literal("18446744073709551616", proof)), true)
        for (proof in listOf("data", "closure", "unknown").map { exact + ("kind" to it) } +
            listOf("BoxedRep (Just Lifted)", "BoxedRep Nothing", "IntRep", "WordRep").map { exact + ("primReps" to listOf(it)) })
            audit(size(literal("18446744073709551616", proof)), false)
        audit(listOf("case", listOf("lit", "int", "0"), "scrutinee", listOf(
            listOf("lit", listOf("bignat", "1"), emptyList<String>(), listOf("lit", "int", "1")),
            listOf("default", null, emptyList<String>(), listOf("lit", "int", "0")))), false, "alternative-kind")
        assertEquals(50, serial)
    }
    @Test fun everyModelByteReconstructsMagnitudeAndSentinels() {
        for ((seed, value) in values.withIndex()) {
            val bytes = ((value.abs().bitLength() + 63) / 64) * 8
            var rebuilt = BigInteger.ZERO
            for (index in 0 until bytes) {
                val position = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) index else index / 8 * 8 + 7 - index % 8
                rebuilt = rebuilt.or(BigInteger.valueOf(expected("magnitudeByte", seed.toLong(), index.toLong(), values)).shiftLeft(position * 8))
            }
            assertEquals(value.abs(), rebuilt)
            assertEquals(-1L, expected("magnitudeByte", seed.toLong(), -1, values))
            assertEquals(-1L, expected("magnitudeByte", seed.toLong(), bytes.toLong(), values))
        }
        assertEquals(0L, expected("magnitudeSize", 0, 0, values))
    }
    @Test fun exactUnliftedLiteralProofRejectsScalarAggregateAndBoxedForgeries() {
        val exact = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
        val bad = listOf(
            exact + ("primReps" to listOf("BoxedRep (Just Lifted)")), exact + ("primReps" to listOf("BoxedRep Nothing")),
            exact + ("kind" to "unknown"), exact + ("kind" to "data"), exact + ("kind" to "closure"),
            mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true),
            mapOf("kind" to "long", "primReps" to listOf("WordRep"), "evaluated" to true),
            mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true),
            mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
                "aggregate" to "unboxed-tuple", "components" to emptyList<Any>()),
            mapOf("kind" to "unknown", "primReps" to listOf("WordRep"), "evaluated" to true,
                "aggregate" to "unboxed-sum", "tagSlot" to 0, "alternativeSlots" to listOf(emptyList<Int>(), emptyList<Int>()),
                "alternatives" to List(2) { mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true) }),
            mapOf("kind" to "vector", "primReps" to listOf("VecRep 2 Int64ElemRep"), "evaluated" to true,
                "vector" to mapOf("lanes" to 2, "element" to "Int64ElemRep")))
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            fun size(proof: Map<String, Any?>?, bind: Boolean): List<Any?> {
                val scalar = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
                val literal = listOf("lit", "bignat", "18446744073709551616") +
                    if (proof == null) emptyList() else listOf(mapOf("rep" to proof))
                // Direct calls must recover the same intrinsic proof as the
                // auditor; the exact case binder is an independent control.
                val operand = if (bind) listOf("var", "bytes", mapOf("rep" to exact)) else literal
                val read = listOf("app", listOf("prim", "sizeofByteArray#"),
                    listOf(operand), listOf(false), false, false, mapOf("rep" to scalar))
                if (!bind) return read
                return listOf("case", literal, "bytes", listOf(listOf("default", null, emptyList<String>(), read)),
                    mapOf("rep" to scalar, "binder" to mapOf("id" to "bytes", "lifted" to false, "rep" to exact)))
            }
            for (bind in listOf(false, true)) {
                for (proof in listOf(exact, null, mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)))
                    assertEquals(16L, context.eval("thc", request(backend, size(proof, bind))).execute(0L).asLong())
                for (proof in bad) assertThrows(PolyglotException::class.java) { context.eval("thc", request(backend, size(proof, bind))) }
            }
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
