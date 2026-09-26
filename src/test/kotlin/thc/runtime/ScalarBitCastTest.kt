// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

class ScalarBitCastTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("floatRoundtrip", "floatField", "floatCaptured", "floatDecode", "floatEncode",
        "doubleRoundtrip", "doubleField", "doubleCaptured", "doubleDecode", "doubleEncode")
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "installed")
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
        visit(entry); return result
    }
    private fun count(p: ExecutableProgram) = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    private fun model(name: String, raw: Long): Long = if (name.startsWith("float")) raw and 0xffffffffL else raw
    private fun patterns(width: Int): Set<Long> {
        val fraction = if (width == 32) 23 else 52
        val exponent = ((1L shl (if (width == 32) 8 else 11)) - 1) shl fraction
        val edges = listOf(0L, 1L, (1L shl fraction) - 1, 1L shl fraction, exponent, exponent - 1)
        return buildSet {
            addAll(edges); add(if (width == 32) 0xffffffffL else -1L)
            for (sign in listOf(0L, 1L shl (width - 1))) {
                addAll(edges.map { sign or it })
                for (quiet in listOf(0L, 1L shl (fraction - 1))) {
                    val payloads = (0L..256L).toList() + (0 until fraction - 1).map { 1L shl it } +
                        (1 until fraction).map { (1L shl it) - 1 }
                    addAll(payloads.map { sign or exponent or quiet or it })
                }
                addAll((0 until width).map { sign or (1L shl it) })
            }
        }
    }
    private fun inputs(width: Int): List<Long> = (patterns(width) + if (width == 32)
        setOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, -(1L shl 32), 1L shl 32,
            (1L shl 48) or 0x7f800001L, -((1L shl 40) or 0x123456L)) else emptySet()).sorted()
    private val expectedCalls = names.associateWith { name -> when {
        name.endsWith("Roundtrip") -> 5L
        name.endsWith("Field") -> 6L
        name.endsWith("Captured") -> 7L
        else -> 4L
    } }
    private fun evidence() = Json.parse(File(root, "build/scalar-bitcasts/manifest.json").readText()) as Map<String, Any?>
    private fun verifyEvidence(manifest: Map<String, Any?>) {
        val prefix = "build/scalar-bitcasts"
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"]); assertEquals(13555L, manifest["nativeRows"])
        assertEquals(mapOf("pre" to "$prefix/pre-core/ScalarBitCastAudit.json",
            "post" to "$prefix/post-core/ScalarBitCastAudit.json"), manifest["stages"])
        assertEquals(mapOf("32" to inputs(32), "64" to inputs(64)), manifest["inputsByWidth"])
        assertEquals(expectedCalls, manifest["expectedGuestCalls"])
        assertEquals(signatures.keys.associateWith { 1L }, manifest["bitcastPrimitiveArities"])
        val keys = listOf("pre", "post").flatMap { stage -> names.map { "$stage/$it" } }.toSet()
        assertEquals(keys, (manifest["audits"] as Map<*, *>).keys)
        assertEquals(keys, (manifest["structure"] as Map<*, *>).keys)
        val requiredSources = setOf("compiler/test-fixtures/ScalarBitCastAudit.hs", "compiler/test-fixtures/ScalarBitCastNative.hs",
            "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/ScalarBitCastFixtures.hs", "scripts/core-capabilities.json", "scripts/audit-core.py",
            "scripts/generate-scalar-signatures.py", "src/main/resources/thc/scalar-primop-signatures.json",
            "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        assertEquals(requiredSources, (manifest["inputHashes"] as Map<*, *>).keys)
        val commands = listOf("native-build", "native-oracle") + listOf("pre", "post").flatMap { stage ->
            listOf("$stage-export") + names.map { "$stage-$it-audit" }
        }
        val requiredArtifacts = setOf("$prefix/inputs.tsv", "$prefix/oracle.tsv", "$prefix/native/scalar-bitcast-oracle") +
            listOf("pre", "post").flatMap { stage -> listOf("$prefix/$stage-core/ScalarBitCastAudit.json", "$prefix/$stage-audit.json") +
                names.map { "$prefix/$stage-$it-audit.json" } } +
            commands.flatMap { name -> listOf("stdout", "stderr", "command.json").map { "$prefix/commands/$name.$it" } }
        assertEquals(requiredArtifacts.toSet(), (manifest["artifactHashes"] as Map<*, *>).keys)
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale bitcast fixture: $path")
        }
        for (stage in listOf("pre", "post")) for (name in names) {
            val report = Json.parse(File(root, "$prefix/$stage-$name-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, report["accepted"]); assertEquals(emptyList<Any>(), report["issues"])
            assertEquals(emptyList<Any>(), report["missingGlobals"])
            val shape = (manifest["structure"] as Map<String, Map<String, Any?>>).getValue("$stage/$name")
            assertEquals(expectedCalls[name], shape["guestCalls"])
        }
    }
    @Test fun evidenceFailsClosedOnMissingHashesStagesCountsAndCorruption() {
        val good = evidence(); verifyEvidence(good)
        for ((field, value) in listOf("nativeRows" to 13554L, "stages" to mapOf("pre" to "other"),
            "inputsByWidth" to mapOf("32" to emptyList<Long>(), "64" to inputs(64)),
            "expectedGuestCalls" to (expectedCalls + ("floatRoundtrip" to 4L)),
            "bitcastPrimitiveArities" to emptyMap<String, Long>())) {
            assertThrows(AssertionError::class.java, { verifyEvidence(good + (field to value)) }, field)
        }
        for (field in listOf("inputHashes", "artifactHashes")) {
            val hashes = good[field] as Map<String, String>
            for (path in hashes.keys) assertThrows(AssertionError::class.java,
                { verifyEvidence(good + (field to (hashes - path))) }, "$field/$path")
            assertThrows(AssertionError::class.java) {
                verifyEvidence(good + (field to (hashes + (hashes.keys.first() to "0".repeat(64)))))
            }
        }
    }
    @Test fun integerCorpusRetainsEveryIeeeClassSignAndPayloadBit() {
        assertEquals(1211, inputs(32).size); assertEquals(1500, inputs(64).size)
        for (width in listOf(32, 64)) {
            val fraction = if (width == 32) 23 else 52
            val maximumExponent = if (width == 32) 255L else 2047L
            val exponent = maximumExponent shl fraction
            val values = patterns(width)
            val classes = values.map { bits ->
                val mantissa = bits and ((1L shl fraction) - 1)
                val exp = (bits ushr fraction) and maximumExponent
                val kind = when {
                    exp == maximumExponent && mantissa != 0L -> if (mantissa ushr (fraction - 1) == 0L) "signalling-nan" else "quiet-nan"
                    exp == maximumExponent -> "infinity"
                    exp == 0L -> if (mantissa == 0L) "zero" else "subnormal"
                    else -> "normal"
                }
                (bits ushr (width - 1) and 1L) to kind
            }.toSet()
            assertEquals(listOf(0L, 1L).flatMap { sign -> listOf("zero", "subnormal", "normal", "infinity", "quiet-nan", "signalling-nan").map { sign to it } }.toSet(), classes)
            for (sign in listOf(0L, 1L shl (width - 1))) for (bit in 0 until fraction)
                assertTrue((sign or exponent or (1L shl bit)) in values)
            for (raw in inputs(width)) assertEquals(if (width == 32) raw.toInt().toUInt().toLong() else raw,
                model(if (width == 32) "floatDecode" else "doubleDecode", raw))
        }
        assertEquals(0xffffffffL, model("floatDecode", -1)); assertEquals(Long.MIN_VALUE, model("doubleDecode", Long.MIN_VALUE))
    }
    @Test fun nativeRawBitsWithInlining() = native(true)
    @Test fun nativeRawBitsAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = evidence(); verifyEvidence(manifest)
        val rows = File(root, "build/scalar-bitcasts/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, path) in manifest["stages"] as Map<String, String>) {
            val module = Json.parse(File(root, path).readText()) as Map<String, Any?>
            for (name in names) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val p = program(language, CoreModules.reachable(module, name) + ("instrument" to true), backend)
                    val entry = p.entryTarget(name); val host = p.hostEntryTarget(1)
                    val function = context.asValue(EntryValue(p, name, 1)); val label = "$stage/$backend/$name/inline=$inlining"
                    val cases = rows.getValue(name)
                    assertEquals((manifest["inputsByWidth"] as Map<String, List<Number>>).getValue(if (name.startsWith("float")) "32" else "64").map { it.toLong() }, cases.map { it[1].toLong() })
                    fun check(row: List<String>) {
                        val input = row[1].toLong(); val expected = row[2].toLong()
                        assertEquals(model(name, input), expected, "native $label/$input")
                        assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                    }
                    cases.forEach(::check)
                    val active = activeTargets(host)
                    assertTrue(active.size > 1, "$label actual guest call target")
                    active.filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean(), "$label host installation")
                    for (row in cases.asReversed()) {
                        val before = count(p); check(row)
                        assertEquals(before + (manifest["expectedGuestCalls"] as Map<String, Number>).getValue(name).toLong(),
                            count(p), "$label/${row[1]} exact retained guest entries")
                        assertEquals(active, activeTargets(host), "$label active target identities")
                        valid(entry, "$label original"); active.forEach { valid(it, "$label active") }; released(language)
                    }
                    assertEquals(0L, (p.diagnostics().getValue("blackholes") as Number).toLong())
                    assertEquals(0L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }

    private val signatures = linkedMapOf("castFloatToWord32#" to ("FloatRep" to "Word32Rep"),
        "castWord32ToFloat#" to ("Word32Rep" to "FloatRep"), "castDoubleToWord64#" to ("DoubleRep" to "Word64Rep"),
        "castWord64ToDouble#" to ("Word64Rep" to "DoubleRep"))
    private fun proof(rep: String) = mapOf("kind" to when (rep) { "FloatRep" -> "float"; "DoubleRep" -> "double"; else -> "long" },
        "primReps" to listOf(rep), "evaluated" to true)
    private fun synthetic(name: String): MutableMap<String, Any?> {
        val (input, output) = signatures.getValue(name)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val app = listOf("app", listOf("prim", name), listOf(listOf("var", "x", mapOf("rep" to proof(input)))),
            listOf(false), false, false, mapOf("rep" to proof(output)))
        return Json.parse(Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "instrument" to true,
            "constructors" to emptyList<Any>(), "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", listOf(mapOf("id" to "x", "lifted" to false, "rep" to proof(input))),
                    app, mapOf("rep" to closure, "resultRep" to proof(output)))))))) as MutableMap<String, Any?>
    }
    private fun lambda(module: Map<String, Any?>) = (module["bindings"] as List<Map<String, Any?>>).single()["expr"] as MutableList<Any?>
    @Test fun pinnedSignaturesAndSharedAuditorPositiveNegativeControls(@TempDir directory: Path) {
        val table = (Json.parse(File(root, "src/main/resources/thc/scalar-primop-signatures.json").readText()) as Map<*, *>)["primitives"] as Map<*, *>
        // The producer checks this subset using Aeson: the complete capability
        // document also has unsigned bounds outside the Core reader's Long range.
        val capabilities = evidence()["bitcastPrimitiveArities"] as Map<*, *>
        for ((name, signature) in signatures) {
            assertEquals(mapOf("arguments" to listOf(signature.first), "result" to signature.second), table[name])
            assertEquals(1L, capabilities[name])
            for (mutation in listOf("valid", "argument", "result", "lexical", "partial", "over", "bare")) {
                val module = synthetic(name); val lam = lambda(module); val app = lam[2] as MutableList<Any?>
                when (mutation) {
                    "argument" -> ((app[2] as List<List<Any?>>).single()[2] as MutableMap<String, Any?>)["rep"] = proof("IntRep")
                    "result" -> (app[6] as MutableMap<String, Any?>)["rep"] = proof("WordRep")
                    "lexical" -> (lam[1] as List<MutableMap<String, Any?>>).single()["rep"] = proof("WordRep")
                    "partial" -> { (app[2] as MutableList<Any?>).clear(); (app[3] as MutableList<Any?>).clear() }
                    "over" -> { (app[2] as MutableList<Any?>).add((app[2] as List<Any?>).single()); (app[3] as MutableList<Any?>).add(false) }
                    "bare" -> lam[2] = listOf("prim", name)
                }
                val label = "${name.removeSuffix("#")}-$mutation"
                val source = directory.resolve("$label.json").toFile().apply { writeText(Json.stringify(module)) }
                val report = directory.resolve("$label-report.json").toFile()
                // Preserve the shared auditor's controls, not just JVM admission.
                val process = ProcessBuilder("python3", "scripts/audit-core.py", source.path,
                    "--entry", "entry", "--output", report.path).directory(root)
                    .redirectOutput(directory.resolve("$label.stdout").toFile())
                    .redirectError(directory.resolve("$label.stderr").toFile()).start()
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor()
                    fail<Unit>("Shared bitcast auditor timed out: $label")
                }
                assertEquals(if (mutation == "valid") 0 else 1, process.exitValue(), label)
                val actual = Json.parse(report.readText()) as Map<*, *>
                assertEquals(mutation == "valid", actual["accepted"], label)
                if (mutation == "valid") {
                    assertEquals(emptyList<Any>(), actual["issues"])
                    assertEquals(emptyList<Any>(), actual["missingGlobals"])
                } else assertTrue((actual["issues"] as List<*>).isNotEmpty(), label)
            }
        }
    }
    @Test fun lexicalMetadataArityAndScalarFrontiersAreChecked() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in signatures.keys) for (mutation in listOf("argument", "result", "lexical", "partial", "over", "bare", "tuple", "sum")) {
                    val module = synthetic(name); val lam = lambda(module); val app = lam[2] as MutableList<Any?>
                    val argument = ((app[2] as List<List<Any?>>).single()[2] as MutableMap<String, Any?>)
                    when (mutation) {
                        "argument" -> argument["rep"] = proof("IntRep")
                        "result" -> (app[6] as MutableMap<String, Any?>)["rep"] = proof("WordRep")
                        "lexical" -> (lam[1] as List<MutableMap<String, Any?>>).single()["rep"] = proof("WordRep")
                        "partial" -> { (app[2] as MutableList<Any?>).clear(); (app[3] as MutableList<Any?>).clear() }
                        "over" -> { (app[2] as MutableList<Any?>).add((app[2] as List<Any?>).single()); (app[3] as MutableList<Any?>).add(false) }
                        "bare" -> lam[2] = listOf("prim", name)
                        "tuple" -> argument["rep"] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                            "primReps" to emptyList<String>(), "components" to emptyList<Any>(), "evaluated" to true)
                        "sum" -> argument["rep"] = mapOf("kind" to "unknown", "aggregate" to "unboxed-sum",
                            "primReps" to listOf("WordRep", "WordRep"), "alternatives" to listOf(proof("IntRep"), proof("IntRep")),
                            "tagSlot" to 0, "alternativeSlots" to listOf(listOf(1), listOf(1)), "evaluated" to true)
                    }
                    val (inputRep, outputRep) = signatures.getValue(name)
                    val sameCarrier = when (mutation) {
                        "argument", "lexical" -> proof(inputRep)["kind"] == "long"
                        "result" -> proof(outputRep)["kind"] == "long"
                        else -> false
                    }
                    if (sameCarrier) {
                        val p = program(language, module, backend)
                        val target = p.entryTarget("entry"); val host = p.hostEntryTarget(1)
                        val bits = if (name.contains("32")) 0xff800123L else 0xfff0000000000123UL.toLong()
                        val input: Any = when (inputRep) {
                            "FloatRep" -> java.lang.Float.intBitsToFloat(bits.toInt())
                            "DoubleRep" -> java.lang.Double.longBitsToDouble(bits)
                            else -> bits
                        }
                        fun check() {
                            val result = Calls.target(host, arrayOf(p.entryValue("entry"), arrayOf(input)))
                            val actual = when (outputRep) {
                                "FloatRep" -> java.lang.Float.floatToRawIntBits(result as Float).toLong() and 0xffffffffL
                                "DoubleRep" -> java.lang.Double.doubleToRawLongBits(result as Double)
                                else -> result as Long
                            }
                            assertEquals(bits, actual, "$backend/$name/$mutation")
                        }
                        check(); compile(target)
                        val before = count(p); check()
                        assertEquals(before + 1, count(p), "$backend/$name/$mutation first installed entry")
                        assertSame(target, p.entryTarget("entry")); valid(target, "$backend/$name/$mutation")
                        released(language)
                    } else assertThrows(RuntimeException::class.java,
                        { program(language, module, backend) }, "$backend/$name/$mutation")
                }
            } finally { context.leave() }
        }
    }
    @Test fun typedNodesKeepRawBitsAndNeverUseBoxedOperandExecution() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val fbits = 0xff800123L; val dbits = 0xfff0000000000123UL.toLong()
        fun argument(name: String) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any = error("bitcast operand was boxed")
            override fun executeLong(frame: VirtualFrame): Long = if (name.contains("32")) fbits else dbits
            override fun executeFloat(frame: VirtualFrame): Float = java.lang.Float.intBitsToFloat(fbits.toInt())
            override fun executeDouble(frame: VirtualFrame): Double = java.lang.Double.longBitsToDouble(dbits)
        }
        for (name in signatures.keys) {
            val node = rawBitCastPrimitive(name, arrayOf(argument(name)))!!
            val actual = when (name) {
                "castWord32ToFloat#" -> java.lang.Float.floatToRawIntBits(node.executeFloat(frame)).toLong() and 0xffffffffL
                "castWord64ToDouble#" -> java.lang.Double.doubleToRawLongBits(node.executeDouble(frame))
                else -> node.executeLong(frame)
            }
            assertEquals(if (name.contains("32")) fbits else dbits, actual, name)
        }
    }
    @Test fun everyFloatNanEncodingAndSelectedDoubleNanPayloadsRemainExact() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        var input = 0L
        fun source() = object : Expr() {
            override fun execute(frame: VirtualFrame): Any = error("bitcast operand was boxed")
            override fun executeLong(frame: VirtualFrame): Long = input
        }
        val floatValue = rawBitCastPrimitive("castWord32ToFloat#", arrayOf(source()))!!
        val floatRoundTrip = rawBitCastPrimitive("castFloatToWord32#", arrayOf(floatValue))!!
        val doubleValue = rawBitCastPrimitive("castWord64ToDouble#", arrayOf(source()))!!
        val doubleRoundTrip = rawBitCastPrimitive("castDoubleToWord64#", arrayOf(doubleValue))!!
        for (sign in longArrayOf(0, 0x80000000L)) for (payload in 1 until (1 shl 23)) {
            val bits = sign or 0x7f800000L or payload.toLong()
            input = bits
            if (floatRoundTrip.executeLong(frame) != bits)
                fail<Unit>("Float NaN changed: ${bits.toString(16)}")
        }
        for (sign in longArrayOf(0, Long.MIN_VALUE)) for (quiet in longArrayOf(0, 1L shl 51)) for (payload in 1..65535) {
            val bits = sign or 0x7ff0000000000000L or quiet or payload.toLong()
            input = bits
            if (doubleRoundTrip.executeLong(frame) != bits)
                fail<Unit>("Double NaN changed: ${bits.toULong().toString(16)}")
        }
    }
    @Test fun directTypedCallsPreserveBitsWithExactlyOneCompiledEntryAndRejectWrongCarriers() {
        val fbits = longArrayOf(0, 0x80000000L, 1, 0x007fffff, 0x7f800000, 0xff800000L, 0x7f800001, 0xffc12345L)
        val dbits = longArrayOf(0, Long.MIN_VALUE, 1, 0x000fffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000UL.toLong(),
            0x7ff0000000000001L, 0xfff8000000001234UL.toLong())
        for (backend in listOf("ast", "bytecode")) for (name in signatures.keys) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p = program(language, synthetic(name), backend); val target = p.entryTarget("entry")
                val bits = if (name.contains("32")) fbits else dbits
                fun input(bits: Long): Any = when (name) {
                    "castFloatToWord32#" -> java.lang.Float.intBitsToFloat(bits.toInt())
                    "castDoubleToWord64#" -> java.lang.Double.longBitsToDouble(bits)
                    else -> bits
                }
                fun invoke(bits: Long): Long {
                    val value = Calls.target(target, arrayOf(0L, input(bits)))
                    return when (name) {
                        "castWord32ToFloat#" -> java.lang.Float.floatToRawIntBits(value as Float).toLong() and 0xffffffffL
                        "castWord64ToDouble#" -> java.lang.Double.doubleToRawLongBits(value as Double)
                        else -> value as Long
                    }
                }
                bits.forEach { assertEquals(it, invoke(it)) }; compile(target)
                bits.forEach {
                    val before = count(p); assertEquals(it, invoke(it), "$backend/$name/${it.toULong().toString(16)}")
                    assertEquals(before+1, count(p)); valid(target, "$backend/$name"); released(language)
                }
                assertThrows(RuntimeException::class.java) { Calls.target(target, arrayOf(0L, Any())) }
                released(language); assertEquals(bits.last(), invoke(bits.last()))
            } finally { context.leave() }
        }
    }
}
