// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.math.BigInteger
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

class FloatDecodeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/float-decode"
    private val names = listOf("floatDirect", "floatCall", "floatExponent", "doubleDirect", "doubleCall", "doubleExponent",
        "floatExampleExponent", "doubleExampleExponent")
    private fun context(inlining: Boolean = false) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun fixtureModule(stage: String) = Json.parse(File(root, "$directory/$stage-core/FloatDecodeAudit.json").readText()) as Map<String, Any?>
    private fun originalModule() = Json.parse(File(root, "$directory/original/GHC.Internal.Bignum.Integer.json").readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf(fixtureModule(stage), originalModule(),
        Json.parse(File(root, "$directory/$stage-core/THC.FloatDecode.json").readText()) as Map<String, Any?>))
    private fun reachableIds(name: String) = setOf("main:${if (name.contains("Example")) "THC.FloatDecode" else "FloatDecodeAudit"}.$name") + when (name) {
        "floatCall" -> setOf("main:FloatDecodeAudit.floatWorker")
        "doubleCall" -> setOf("main:FloatDecodeAudit.doubleWorker")
        "doubleExponent", "doubleExampleExponent" -> setOf("ghc-internal:GHC.Internal.Bignum.Integer.\$wintegerFromInt64#")
        else -> emptySet()
    }
    private fun count(p: ExecutableProgram) = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target)
        valid(target)
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }

    private fun inputs(width: Int): List<Long> {
        val p = if (width == 32) 23 else 52
        val e = if (width == 32) 8 else 11
        val top = (1L shl e) - 1
        val bias = (1L shl (e - 1)) - 1
        val magnitudes = buildSet {
            for (code in 0L..top) add(code shl p)
            for (code in listOf(0, 1, bias - 1, bias, bias + 1, top - 1, top))
                for (fraction in listOf(1L, (1L shl p) - 1, 1L shl (p - 1))) add((code shl p) or fraction)
            for (bit in 0 until p) for (delta in -1L..1L) add((1L shl bit) + delta)
            var state = 0xdec0deL
            repeat(128) {
                state = state * 6364136223846793005L + 1442695040888963407L
                add(state and if (width == 32) 0x7fffffffL else Long.MAX_VALUE)
            }
        }
        return (magnitudes + magnitudes.map { it or (1L shl (width - 1)) }).sorted()
    }

    /** Independent unbounded-integer dyadic model; no JVM floating arithmetic,
     * runtime decoder, or leading-zero intrinsic supplies expected fields. */
    private fun mathematical(name: String, raw: Long): List<Long> {
        val width = if (name.startsWith("float")) 32 else 64
        val p = if (width == 32) 23 else 52
        val exponentBits = if (width == 32) 8 else 11
        val bits = BigInteger.valueOf(raw).mod(BigInteger.ONE.shiftLeft(width))
        val code = bits.shiftRight(p).and(BigInteger.ONE.shiftLeft(exponentBits).subtract(BigInteger.ONE)).toInt()
        val fraction = bits.and(BigInteger.ONE.shiftLeft(p).subtract(BigInteger.ONE))
        var magnitude = if (code == 0) fraction else fraction.setBit(p)
        var exponent = if (code == 0) 1 - ((1 shl (exponentBits - 1)) - 1) - p
            else code - ((1 shl (exponentBits - 1)) - 1) - p
        if (magnitude.signum() == 0) exponent = 0
        else {
            val shift = p + 1 - magnitude.bitLength()
            magnitude = magnitude.shiftLeft(shift)
            exponent -= shift
        }
        val mantissa = if (bits.testBit(width - 1)) magnitude.negate() else magnitude
        if (name.endsWith("Exponent")) {
            val publicExponent = if (mantissa.signum() == 0) 0L else exponent.toLong() + p + 1
            return listOf(publicExponent, publicExponent)
        }
        return listOf(mantissa.longValueExact(), exponent.toLong())
    }
    private data class Row(val name: String, val bits: Long, val fields: List<Long>)
    private fun rows(text: String): List<Row> {
        val result = text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val fields = line.split('\t'); require(fields.size == 4)
            Row(fields[0], fields[1].toLong(), fields.drop(2).map(String::toLong))
        }.toList()
        require(result.map { it.name to it.bits } == names.flatMap { name ->
            inputs(if (name.startsWith("float")) 32 else 64).map { name to it } })
        for (row in result) require(mathematical(row.name, row.bits) == row.fields) { "Native/model mismatch: $row" }
        return result
    }
    private fun verifyEvidence(manifest: Map<String, Any?>) {
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"])
        assertEquals(inputs(32), manifest["floatInputs"]); assertEquals(inputs(64), manifest["doubleInputs"])
        assertEquals(4L * (inputs(32).size + inputs(64).size), manifest["nativeRows"])
        val requiredSources = setOf("compiler/test-fixtures/FloatDecodeAudit.hs", "compiler/test-fixtures/FloatDecodeNative.hs",
            "examples/THC/FloatDecode.hs", "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/FloatDecodeFixtures.hs", "scripts/core-capabilities.json", "scripts/audit-core.py",
            "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh", "compiler/export-boot.py",
            "compiler/toolchain.sh", "compiler/plugin.py") +
            listOf("BigNat", "Integer", "Natural").flatMap { name -> listOf(".hs", ".hs-boot").map {
                "vendor/ghc-9.14.1/GHC/Internal/Bignum/$name$it" } } +
            setOf("vendor/ghc-9.14.1/include/WordSize.h", "vendor/ghc-9.14.1/LICENSE") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        val commands = listOf("native-build", "native-oracle", "boot-export") + listOf("pre", "post").flatMap { stage ->
            listOf("$stage-export") + names.map { "$stage-$it-audit" } }
        val requiredArtifacts = setOf("$directory/inputs.tsv", "$directory/oracle.tsv", "$directory/native/oracle",
            "$directory/original/GHC.Internal.Bignum.Integer.json", "$directory/original/boot-provenance.json") +
            listOf("pre", "post").flatMap { stage -> listOf("$directory/$stage-core/FloatDecodeAudit.json", "$directory/$stage-core/THC.FloatDecode.json") +
                names.map { "$directory/$stage-$it-audit.json" } } +
            commands.flatMap { command -> listOf("stdout", "stderr", "command.json").map { "$directory/commands/$command.$it" } }
        assertEquals(requiredSources, (manifest["inputHashes"] as Map<*, *>).keys)
        assertEquals(requiredArtifacts, (manifest["artifactHashes"] as Map<*, *>).keys)
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale floating decode fixture: $path")
        }
        for (stage in listOf("pre", "post")) for (name in names) {
            val report = Json.parse(File(root, "$directory/$stage-$name-audit.json").readText()) as Map<*, *>
            assertEquals(true, report["accepted"]); assertEquals(emptyList<Any>(), report["issues"])
            assertEquals(emptyList<Any>(), report["missingGlobals"])
            assertEquals(reachableIds(name), (report["reachableBindings"] as List<Map<String, Any?>>).map { it["id"] }.toSet())
            val decode = if (name.startsWith("float")) "decodeFloat_Int#" else "decodeDouble_Int64#"
            val primitive = (report["primitives"] as List<Map<String, Any?>>).single { it["name"] == decode }
            assertEquals(1, (primitive["uses"] as List<*>).size, "$stage/$name saturated decode")
        }
        val original = originalModule()["bindings"] as List<Map<String, Any?>>
        assertEquals(139, original.size, "Complete pinned original Integer module, not a fabricated worker")
        val provenance = Json.parse(File(root, "$directory/original/boot-provenance.json").readText()) as Map<*, *>
        assertEquals("ghc-9.14.1-release", provenance["ghcTag"])
        assertEquals(emptyList<Any>(), provenance["sourcePatches"])
        val sources = provenance["sources"] as List<Map<String, String>>
        assertEquals(requiredSources.filter { it.startsWith("vendor/") }.toSet(), sources.map { it.getValue("path") }.toSet())
        for (source in sources) assertEquals((manifest["inputHashes"] as Map<*, *>)[source["path"]], source["sha256"])
    }
    @Test fun nativeIeeeCorpusHasExactIndependentFieldsAndProvenance() {
        val manifest = Json.parse(File(root, "$directory/manifest.json").readText()) as Map<String, Any?>
        verifyEvidence(manifest)
        rows(File(root, "$directory/oracle.tsv").readText())
        for (width in listOf(32, 64)) {
            val p = if (width == 32) 23 else 52
            val name = if (width == 32) "floatDirect" else "doubleDirect"
            assertEquals(listOf(0L, 0L), mathematical(name, 0))
            assertEquals(listOf(0L, 0L), mathematical(name, 1L shl (width - 1)))
            assertEquals(listOf(1L shl p, if (width == 32) -172L else -1126L), mathematical(name, 1))
            assertEquals(-(1L shl p), mathematical(name, (1L shl (width - 1)) or 1)[0])
        }
    }
    @Test fun missingReorderedCorruptRowsAndMissingProvenanceFailClosed() {
        val text = File(root, "$directory/oracle.tsv").readText()
        val lines = text.lines().filter { it.isNotEmpty() }
        for (bad in listOf(lines.drop(1), lines.reversed(), lines + lines.first(),
                listOf(lines.first().substringBeforeLast('\t') + "\t123456") + lines.drop(1)))
            assertThrows(IllegalArgumentException::class.java) { rows(bad.joinToString("\n")) }
        val manifest = Json.parse(File(root, "$directory/manifest.json").readText()) as Map<String, Any?>
        assertThrows(AssertionError::class.java) { verifyEvidence(manifest + ("artifactHashes" to emptyMap<String, String>())) }
        val hashes = manifest["inputHashes"] as Map<String, String>
        assertThrows(AssertionError::class.java) { verifyEvidence(manifest + ("inputHashes" to (hashes + (hashes.keys.first() to "0")))) }
    }
    @Test fun nativeResultsAcrossResidualCalls() = native(false)
    @Test fun nativeResultsWithInlining() = native(true)
    @Test fun publicDoubleExponentRequiresOriginalIntegerCore(@TempDir temporary: Path) {
        for (stage in listOf("pre", "post")) {
            val report = temporary.resolve("$stage.json").toFile()
            val process = ProcessBuilder("python3", "scripts/audit-core.py",
                "$directory/$stage-core/FloatDecodeAudit.json", "--entry", "doubleExponent", "--output", report.path)
                .directory(root).redirectOutput(temporary.resolve("$stage.stdout").toFile())
                .redirectError(temporary.resolve("$stage.stderr").toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(); fail<Unit>("Missing-original audit timed out")
            }
            assertEquals(1, process.exitValue())
            val audit = Json.parse(report.readText()) as Map<*, *>
            assertEquals(false, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(listOf("ghc-internal:GHC.Internal.Bignum.Integer.\$wintegerFromInt64#"),
                (audit["missingGlobals"] as List<Map<*, *>>).map { it["id"] })
        }
    }
    private fun native(inlining: Boolean) {
        verifyEvidence(Json.parse(File(root, "$directory/manifest.json").readText()) as Map<String, Any?>)
        val rows = rows(File(root, "$directory/oracle.tsv").readText()).groupBy { it.name }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (name in names) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module(stage), name)
                    val p = program(language, linked + ("instrument" to true), backend)
                    val entry = p.entryTarget(name)
                    val example = name.contains("Example")
                    val host = p.hostEntryTarget(if (example) 1 else 2); val value = p.entryValue(name)
                    fun check(row: Row, installed: Boolean) {
                        for ((field, expected) in row.fields.withIndex()) {
                            val before = count(p)
                            val label = "$stage/$backend/$name/${row.bits}/$field/inlining=$inlining"
                            val arguments = if (example) arrayOf(row.bits) else arrayOf(row.bits, field.toLong())
                            assertEquals(expected, Calls.target(host, arrayOf(value, arguments)), label)
                            if (installed) assertEquals(reachableIds(name).size.toLong(), count(p) - before,
                                "$label exact first-and-every compiled entry")
                            released(language)
                        }
                    }
                    rows.getValue(name).forEach { check(it, false) }
                    val targets = activeTargets(entry)
                    assertEquals(reachableIds(name).size, targets.size)
                    targets.forEach(::compile)
                    val allocations = language.handoffState.get().results.allocations
                    rows.getValue(name).forEach { check(it, true) }
                    targets.forEach(::valid)
                    val active = activeTargets(entry)
                    assertEquals(targets.size, active.size)
                    assertTrue(active.all { target -> targets.any { it === target } })
                    assertEquals(allocations, language.handoffState.get().results.allocations)
                    if (name.endsWith("Direct")) assertEquals(0L, allocations, "Direct decode needs no tuple carrier")
                    for (counter in listOf("unsupportedTraps", "blackholes"))
                        assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong(), counter)
                } finally { context.leave() }
            }
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun scalarCarriersTupleShapeArityAndSharedAuditorStayChecked(@TempDir temporary: Path) {
        for (name in listOf("floatDirect", "doubleDirect")) for (mutation in
            listOf("valid", "argument", "result-carrier", "result-arity", "partial", "over", "lifted", "bare")) {
            val linked = CoreModules.reachable(module("pre"), name)
            val primitive = if (name.startsWith("float")) "decodeFloat_Int#" else "decodeDouble_Int64#"
            val app = applications(linked).single { (it[1] as? List<*>)?.take(2) == listOf("prim", primitive) }
            val metadata = app[6] as MutableMap<String, Any?>
            val proof = metadata["rep"] as MutableMap<String, Any?>
            val args = app[2] as MutableList<Any?>
            when (mutation) {
                "argument" -> (CoreRepresentations.metadata(args.single() as List<Any?>) as MutableMap<String, Any?>)["rep"] =
                    mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
                "result-carrier" -> {
                    val fields = proof["components"] as MutableList<Any?>
                    fields[0] = mapOf("kind" to "float", "primReps" to listOf("FloatRep"), "evaluated" to true)
                    (proof["primReps"] as MutableList<Any?>)[0] = "FloatRep"
                }
                "result-arity" -> {
                    (proof["components"] as MutableList<Any?>).removeLast()
                    (proof["primReps"] as MutableList<Any?>).removeLast()
                }
                "partial" -> { args.clear(); (app[3] as MutableList<Any?>).clear(); metadata.remove("callDemand") }
                "over" -> { args.add(args.single()); (app[3] as MutableList<Any?>).add(false); metadata.remove("callDemand") }
                "lifted" -> (app[3] as MutableList<Any?>)[0] = true
                "bare" -> { val head = (app[1] as List<*>).toList(); app.clear(); app.addAll(head) }
            }
            val label = "$name-$mutation"
            val input = temporary.resolve("$label.json").toFile().apply { writeText(Json.stringify(linked)) }
            val report = temporary.resolve("$label-report.json").toFile()
            val process = ProcessBuilder("python3", "scripts/audit-core.py", input.path, "--entry", name, "--output", report.path)
                .directory(root).redirectOutput(temporary.resolve("$label.stdout").toFile())
                .redirectError(temporary.resolve("$label.stderr").toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                fail<Unit>("Shared auditor timeout: $label")
            }
            assertEquals(if (mutation == "valid") 0 else 1, process.exitValue(), label)
            assertEquals(mutation == "valid", (Json.parse(report.readText()) as Map<*, *>)["accepted"], label)
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    if (mutation == "valid") program(language, linked, backend)
                    else for (diagnostic in listOf(false, true)) {
                        if (mutation == "bare" && diagnostic) {
                            // Diagnostic mode deliberately defers UnsupportedCore,
                            // but demanding this unavailable tuple must still trap.
                            val p = program(language, linked + ("diagnosticUnsupported" to true), backend)
                            val failure = assertThrows(RuntimeFault::class.java) {
                                Calls.target(p.hostEntryTarget(2), arrayOf(p.entryValue(name), arrayOf(0L, 0L)))
                            }
                            assertTrue(failure.message!!.contains("Unsaturated primitive $primitive"))
                            assertEquals(1L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                            released(language)
                        } else assertThrows(RuntimeException::class.java,
                            { program(language, linked + ("diagnosticUnsupported" to diagnostic), backend) },
                            "$backend/$label/diagnostic=$diagnostic")
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun typedAstUsesPrimitiveOperandAndWritesLongSlots() {
        val descriptor = FrameDescriptor.newBuilder()
        val slots = IntArray(4) { descriptor.addSlot(FrameSlotKind.Long, null, null) }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor.build())
        for (operation in FloatDecodeOp.entries) {
            val proof = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true, present = true,
                components = List(operation.fields) { CoreRepresentation(CoreKind.LONG, evaluated = true, present = true) })
            var calls = 0
            val operand = object : Expr() {
                override fun execute(frame: VirtualFrame): Any? = error("Boxed operand execution")
                override fun executeFloat(frame: VirtualFrame): Float { calls++; return -1.5f }
                override fun executeDouble(frame: VirtualFrame): Double { calls++; return -1.5 }
            }
            val expression = FloatDecodeExpression(operation, proof, operand)
            expression.executeTuple(frame, slots, 0)
            assertEquals(1, calls)
            val expected = when (operation) {
                FloatDecodeOp.FLOAT -> listOf(-12582912L, -23L)
                FloatDecodeOp.DOUBLE -> listOf(-6755399441055744L, -52L)
                FloatDecodeOp.DOUBLE_WORDS -> listOf(-1L, 1572864L, 0L, -52L)
            }
            expected.forEachIndexed { i, value -> assertEquals(value, frame.getLong(slots[i])) }
        }
    }
}
