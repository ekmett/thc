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
import thc.*
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class PinnedAddressTest {
    private fun literal(value: Any?) = object : Expr() { override fun execute(frame: VirtualFrame): Any? = value }
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/pinned-addresses"
    private val entries = linkedMapOf("pinnedBytes" to 3L, "alignedBytes" to 4L, "keepAliveWord8" to 1L,
        "keepAliveLazy" to 1L, "fingerprintByte" to 3L)
    private val frontiers = linkedMapOf("publicFingerprintByte" to 3L, "publicFingerprintRoundtrip" to 3L)
    private val guestCalls = mapOf("pinnedBytes" to 4L, "alignedBytes" to 4L, "keepAliveWord8" to 3L,
        "keepAliveLazy" to 3L, "fingerprintByte" to 3L)
    private val values = listOf(Long.MIN_VALUE, -257L, -256L, -1L, 0L, 1L, 127L, 128L, 255L, 256L, 257L,
        0x0123456789abcdefL, Long.MAX_VALUE)
    private val words = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 0x0123456789abcdefL, 0x7f0080ff0102fe03L, Long.MAX_VALUE)
    private val sizes = listOf(0L, 1L, 2L, 3L, 8L, 16L, 17L, 31L, 64L)
    private val negatives = listOf("read-word-not-word8", "write-word-not-word8", "read-address-is-word",
        "read-state-is-int", "read-offset-is-word", "contents-lifted-array", "contents-result-is-word",
        "allocation-size-is-word", "allocation-state-is-int", "aligned-alignment-is-word",
        "keepalive-state-is-int", "keepalive-result-word-not-word8")
    private fun report(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private data class Case(val name: String, val arguments: List<Long>)
    private fun domain(): List<Case> = buildList {
        for (size in sizes) for (offset in if (size == 0L) listOf(0L) else (0L until size).toList()) for (raw in values) {
            add(Case("pinnedBytes", listOf(size, offset, raw)))
            for (alignment in listOf(8L, 16L)) add(Case("alignedBytes", listOf(size, alignment, offset, raw)))
        }
        for (name in listOf("keepAliveWord8", "keepAliveLazy")) for (raw in values) add(Case(name, listOf(raw)))
        for (high in words) for (low in words) {
            for (index in 0L..15L) for (name in listOf("fingerprintByte", "publicFingerprintByte")) add(Case(name, listOf(high, low, index)))
            for (index in 0L..1L) add(Case("publicFingerprintRoundtrip", listOf(high, low, index)))
        }
    }
    private fun expected(name: String, arguments: List<Any?>): Long {
        assertTrue(name in entries || name in frontiers, "Unknown entry")
        assertEquals((entries + frontiers).getValue(name), arguments.size.toLong(), "Host arity")
        assertTrue(arguments.all { it is Long }, "Native machine Int domain")
        val args = arguments.map { it as Long }
        if (name in listOf("pinnedBytes", "alignedBytes")) {
            val size = args[0]; val offset = args[args.size - 2]; val raw = args.last()
            if (name == "alignedBytes") assertTrue(args[1] in listOf(8L, 16L), "Native alignment domain")
            assertTrue(size in 0L..64L && if (size == 0L) offset == 0L else offset in 0L until size, "Native bounds domain")
            if (size == 0L) return 0
            val before = Math.floorMod(raw, 256L); val after = if (before < 128) before + 128 else before - 128
            val first = if (offset == 0L) after else 11L; val last = if (offset == size - 1) after else 13L
            return size * 19 + before * 257 + after * 65537 + first * 17 + last * 23
        }
        if (name in listOf("keepAliveWord8", "keepAliveLazy")) {
            val before = Math.floorMod(args[0], 256L); val delta = if (name == "keepAliveWord8") 7 else 11
            return before * 257 + (before + delta) % 256
        }
        val index = args[2]
        if (name == "publicFingerprintRoundtrip") {
            assertTrue(index in 0L..1L); return args[index.toInt()]
        }
        assertTrue(index in 0L..15L, "Byte selector domain")
        return (ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(args[0]).putLong(args[1]).array()[index.toInt()].toInt() and 255).toLong()
    }
    private fun checkedRows(text: String, cases: List<Case> = domain()): List<List<String>> {
        assertEquals(7269, cases.size); assertEquals(cases.size, cases.toSet().size)
        val rows = text.lineSequence().filter { it.isNotEmpty() }.map { it.split('\t') }.toList()
        assertEquals(cases.size, rows.size, "Exact native corpus size")
        val seen = mutableSetOf<Case>()
        for ((index, row) in rows.withIndex()) {
            assertTrue(row.size >= 3)
            val actual = Case(row[0], row.subList(1, row.lastIndex).map { it.toLong() })
            assertTrue(seen.add(actual), "Duplicate native row")
            assertEquals(cases[index], actual, "Exact ordered native domain")
            assertEquals(expected(actual.name, actual.arguments), row.last().toLong(), "Native/model mismatch: $row")
        }
        return rows
    }
    private fun requiredHashes(): Map<String, Set<String>> {
        val sources = listOf("compiler/test-fixtures/PinnedAddressAudit.hs", "compiler/test-fixtures/PinnedAddressAuditNative.hs",
            "test/haskell-fixtures/PinnedAddressFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs",
            "thc.cabal", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
            "scripts/audit-core.py", "scripts/core-capabilities.json", "scripts/generate-scalar-signatures.py",
            "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        val commands = listOf("native-build", "native-oracle") + listOf("pre", "post").flatMap { stage ->
            listOf("$stage-export") + (entries.keys + frontiers.keys + negatives.map { "negative-$it" }).map { "$stage-$it-audit" } }
        val artifacts = listOf("requests.tsv", "expected.tsv", "oracle.tsv", "structure-controls.json").map { "$directory/$it" } +
            listOf("pinned-address-oracle", "Main.hi", "Main.o", "PinnedAddressAudit.hi", "PinnedAddressAudit.o").map { "$directory/native/$it" } +
            listOf("pre", "post").flatMap { stage ->
                listOf("$directory/$stage/core/PinnedAddressAudit.json", "$directory/$stage/core/THC.InterfaceClosure.json", "$directory/$stage/negative-proofs.json") +
                (entries.keys + frontiers.keys + negatives.map { "negative-$it" }).map { "$directory/$stage/$it.audit.json" } +
                negatives.flatMap { name -> (0..1).map { "$directory/$stage/negative/$name-$it.json" } } } +
            commands.flatMap { name -> listOf("stdout", "stderr", "command.json").map { "$directory/commands/$name.$it" } }
        return mapOf("inputHashes" to sources.toSet(), "artifactHashes" to artifacts.toSet())
    }
    private fun verifyEvidence(manifest: Map<String, Any?>) {
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(true, manifest["strictAccepted"]); assertEquals("full", manifest["mode"])
        assertEquals(7269L, manifest["nativeRows"]); assertEquals(7269L, manifest["modelRows"])
        assertEquals(entries, manifest["entries"]); assertEquals(frontiers, manifest["publicFrontiers"])
        assertEquals(guestCalls, manifest["expectedGuestCallsByEntry"])
        assertEquals("big", manifest["fingerprintByteOrder"])
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["nativeByteOrder"])
        val stages = listOf("pre", "post").associateWith { stage -> listOf("$directory/$stage/core/PinnedAddressAudit.json", "$directory/$stage/core/THC.InterfaceClosure.json") }
        assertEquals(stages, manifest["stages"])
        for ((kind, required) in requiredHashes()) {
            val hashes = manifest[kind] as Map<String, String>
            assertEquals(required, hashes.keys, "$kind exact inventory")
        }
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale pinned-address input $path")
        }
        val cases = domain()
        assertEquals(mapOf("pinnedBytes" to 1859L, "alignedBytes" to 3718L, "keepAliveWord8" to 13L, "keepAliveLazy" to 13L,
            "fingerprintByte" to 784L, "publicFingerprintByte" to 784L, "publicFingerprintRoundtrip" to 98L), manifest["rowCounts"])
        assertEquals(cases.map { mapOf("entry" to it.name, "arguments" to it.arguments, "expected" to expected(it.name, it.arguments)) }, manifest["rows"])
        val controls = report("$directory/structure-controls.json")
        assertEquals(3L, controls["acceptedBaselineGuestCalls"])
        assertEquals(listOf("host-arity", "state-rep", "state-flag", "continuation-rep", "result", "hidden-lambda", "global", "rejected-proof-baseline"), controls["rejected"])
        val checkedStructures = manifest["checkedGuestStructureByStage"] as Map<String, Map<String, Any?>>
        assertEquals(stages.keys.flatMap { stage -> entries.keys.map { "$stage/$it" } }.toSet(), checkedStructures.keys)
        val recordedAudits = manifest["audits"] as Map<String, Map<String, Map<String, Any?>>>
        val negativeProofs = manifest["negativeProofs"] as Map<String, Map<String, Map<String, Any?>>>
        val keepSites = manifest["keepAliveSites"] as Map<String, List<Map<String, Any?>>>
        assertEquals(stages.keys, recordedAudits.keys); assertEquals(stages.keys, negativeProofs.keys); assertEquals(stages.keys, keepSites.keys)
        for (stage in stages.keys) {
            val core = report(stages.getValue(stage).first())
            assertEquals("9.14.1", core["ghc"])
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep", core["boundary"])
            assertEquals(entries.keys + frontiers.keys, recordedAudits.getValue(stage).keys)
            assertTrue(keepSites.getValue(stage).isNotEmpty())
            for (name in entries.keys + frontiers.keys) {
                val audit = report("$directory/$stage/$name.audit.json")
                assertEquals(audit, recordedAudits.getValue(stage)[name])
                verifyAudit(name, audit)
                if (name in entries) {
                    val structure = checkedStructures.getValue("$stage/$name")
                    assertEquals(guestCalls[name], structure["guestCalls"])
                    assertEquals(guestCalls[name], (structure["lambdaFormals"] as List<*>).size.toLong())
                    assertEquals(if (name == "keepAliveLazy") "keptBottom" else null, structure["lazyUncalledGlobal"])
                }
            }
            assertEquals(negatives.toSet(), negativeProofs.getValue(stage).keys)
            assertEquals(negativeProofs.getValue(stage), report("$directory/$stage/negative-proofs.json"))
            for (label in negatives) {
                val audit = report("$directory/$stage/negative-$label.audit.json")
                assertEquals(false, audit["accepted"]); assertTrue((audit["issues"] as List<*>).isNotEmpty())
                assertEquals(audit["issues"], negativeProofs.getValue(stage).getValue(label)["issues"])
                assertEquals(1L, report("$directory/commands/$stage-negative-$label-audit.command.json")["exit"])
            }
        }
    }
    private fun verifyAudit(name: String, audit: Map<String, Any?>) {
        assertEquals(name in entries, audit["accepted"])
        val missing = (audit["missingGlobals"] as List<Map<String, Any?>>).map { (it["id"] as String).substringAfter(':') }.toSet()
        if (name in entries) { assertTrue(missing.isEmpty()); assertEquals(emptyList<Any>(), audit["issues"]) }
        else {
            val required = setOf("GHC.Internal.Foreign.Storable.\$fStorableFingerprint_\$s\$wpokeW64") +
                if (name == "publicFingerprintRoundtrip") setOf("GHC.Internal.Foreign.Storable.\$fStorableFingerprint_\$s\$wpeekW64") else emptySet()
            assertTrue(missing.containsAll(required), "Original public Storable frontier")
        }
    }
    @Test fun independentModelChecksEveryNativeByteAndStrictDomains() {
        verifyEvidence(manifest())
        val cases = domain(); val oracle = File(root, "$directory/oracle.tsv").readText()
        checkedRows(oracle)
        assertEquals(oracle, File(root, "$directory/expected.tsv").readText())
        assertEquals(cases.joinToString("") { (listOf(it.name) + it.arguments).joinToString("\t") + "\n" }, File(root, "$directory/requests.tsv").readText())
        for (high in words) for (low in words) for (index in 0L..15L) for (name in listOf("fingerprintByte", "publicFingerprintByte"))
            assertEquals(((if (index < 8) high else low) ushr (8 * (7 - index.toInt() % 8))) and 255, expected(name, listOf(high, low, index)))
        for ((name, delta) in listOf("keepAliveWord8" to 7L, "keepAliveLazy" to 11L)) for (x in 0L..255L) {
            val result = expected(name, listOf(x))
            assertEquals(x * 257 + (x + delta) % 256, result)
            assertNotEquals(x * 258, result); assertNotEquals(((x + delta) % 256) * 257 + (x + 2 * delta) % 256, result)
        }
        for ((name, args) in listOf("pinnedBytes" to listOf(-1L, 0L, 0L), "pinnedBytes" to listOf(0L, 1L, 0L),
            "pinnedBytes" to listOf(1L, 1L, 0L), "alignedBytes" to listOf(1L, 3L, 0L, 0L), "alignedBytes" to listOf(1L, 0L, 0L, 0L),
            "fingerprintByte" to listOf(0L, 0L, 16L), "fingerprintByte" to listOf(0L, 0L, -1L),
            "publicFingerprintRoundtrip" to listOf(0L, 0L, 2L), "keepAliveWord8" to listOf(BigInteger.ONE.shiftLeft(63)),
            "keepAliveLazy" to listOf(true), "unknown" to listOf(0L), "keepAliveWord8" to emptyList<Any>()))
            assertThrows(AssertionError::class.java) { expected(name, args) }
    }
    @Test fun corpusRejectsMissingDuplicateUnknownReorderedAndMismatchedRows() {
        val text = File(root, "$directory/oracle.tsv").readText(); val lines = text.lines().filter { it.isNotEmpty() }
        checkedRows(text)
        for (bad in listOf(text + lines.first() + "\n", lines.drop(1).joinToString("\n"), "unknown\t0\t0\n",
            (listOf(lines.last()) + lines.drop(1)).joinToString("\n"),
            "unknown\t" + lines.first().substringAfter('\t') + "\n" + lines.drop(1).joinToString("\n"),
            (listOf(lines[1], lines[0]) + lines.drop(2)).joinToString("\n"),
            lines.first().substringBeforeLast('\t') + "\t99999\n" + lines.drop(1).joinToString("\n")))
            assertThrows(AssertionError::class.java) { checkedRows(bad) }
        assertThrows(AssertionError::class.java) { checkedRows(text, domain().drop(1) + domain().first()) }
    }
    @Test fun evidenceRejectsMissingHashesAndChangedCoverage() {
        val good = manifest(); verifyEvidence(good)
        for ((key, value) in listOf("nativeRows" to 7268L, "modelRows" to 0L, "strictAccepted" to false, "mode" to "native-only",
            "entries" to emptyMap<String, Long>(), "publicFrontiers" to emptyMap<String, Long>(), "expectedGuestCallsByEntry" to emptyMap<String, Long>(),
            "stages" to emptyMap<String, Any>(), "checkedGuestStructureByStage" to emptyMap<String, Any>(), "negativeProofs" to emptyMap<String, Any>(),
            "audits" to emptyMap<String, Any>(), "keepAliveSites" to emptyMap<String, Any>(), "rows" to emptyList<Any>(), "rowCounts" to emptyMap<String, Long>()))
            assertThrows(AssertionError::class.java, { verifyEvidence(good + (key to value)) }, key)
        for (kind in listOf("inputHashes", "artifactHashes")) {
            val records = good[kind] as Map<String, String>
            for (path in records.keys) assertThrows(AssertionError::class.java, { verifyEvidence(good + (kind to (records - path))) }, path)
            assertThrows(AssertionError::class.java) { verifyEvidence(good + (kind to (records + (records.keys.first() to "0".repeat(64))))) }
        }
        for (stage in listOf("pre", "post")) for (name in frontiers.keys) {
            val audit = report("$directory/$stage/$name.audit.json")
            assertThrows(AssertionError::class.java) { verifyAudit(name, audit + ("accepted" to true)) }
            assertThrows(AssertionError::class.java) { verifyAudit(name, audit + ("missingGlobals" to emptyList<Any>())) }
        }
    }
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build()
    private fun manifest() = Json.parse(File(root, "build/pinned-addresses/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) { target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target, "installed") }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val roots = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in roots.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    @Test fun nativePinnedAddressesAndLazyKeepAliveWithInlining() = native(true)
    @Test fun nativePinnedAddressesAndLazyKeepAliveAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = manifest()
        verifyEvidence(manifest)
        val rows = checkedRows(File(root, "build/pinned-addresses/oracle.tsv").readText()).groupBy { it[0] }
        assertEquals((entries.keys + (manifest["publicFrontiers"] as Map<*, *>).keys).toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) for ((name, arity) in entries) {
            val cases = rows.getValue(name)
            val audit = Json.parse(File(root, "build/pinned-addresses/$stage/$name.audit.json").readText()) as Map<*, *>
            assertEquals(true, audit["accepted"])
            for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = program(language, CoreModules.reachable(merged(paths), name) + ("instrument" to true), backend)
                    val function = context.asValue(EntryValue(program, name, arity.toInt()))
                    val host = program.hostEntryTarget(arity.toInt()); val original = program.entryTarget(name)
                    val label = "$stage/$backend/$name/inlining=$inlining"
                    fun check(row: List<String>) {
                        val arguments = row.subList(1, row.lastIndex).map { it.toLong() }.toTypedArray()
                        assertEquals(row.last().toLong(), function.execute(*arguments).asLong(), "$label/$row")
                        released(language)
                    }
                    cases.forEach(::check)
                    val targets = activeTargets(host)
                    assertEquals(guestCalls.getValue(name).toInt() + 1, targets.size, "$label concrete guest roots plus host")
                    targets.filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    for (row in cases.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertEquals(before + guestCalls.getValue(name).toLong(), (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$label exact compiled guest entries")
                        assertEquals(targets, activeTargets(host), "$label active identities")
                        valid(original, label); targets.forEach { valid(it, label) }
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), label)
                    println("PinnedAddress PASS $label rows=${cases.size}")
                } finally { context.leave() }
            }
        }
    }
    @Test fun allocationChecksFullWidthSizeAndPowerOfTwoAlignment() {
        for (size in listOf(0L, 1L, 16L, 129L)) for (alignment in listOf(1L, 2L, 8L, 64L, 4096L)) {
            val allocation = PinnedMemory.allocate(size, alignment)
            assertEquals(size, allocation.size)
            assertTrue(allocation.isPinned)
            assertEquals(0L, allocation.nativeSegment()!!.address() and (alignment - 1))
        }
        for (size in listOf(Long.MIN_VALUE, -1L, Int.MAX_VALUE.toLong() + 1, 1L shl 32, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { PinnedMemory.allocate(size, 8) }
        for (alignment in listOf(Long.MIN_VALUE, -1L, 0L, 3L, 7L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { PinnedMemory.allocate(0, alignment) }
    }
    @Test fun statePrecedesMemoryEffectsAndFailedReadsDoNotPublish() {
        val descriptor = FrameDescriptor.newBuilder().apply { addSlot(FrameSlotKind.Object, null, null) }.build()
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        for (operation in listOf(PinnedMemoryOp.NEW, PinnedMemoryOp.NEW_ALIGNED, PinnedMemoryOp.READ, PinnedMemoryOp.WRITE)) for (fail in listOf(false, true)) {
            val array = byteArrayOf(11, 22); val address = ManagedAddress.fromByteArray(array)
            val sentinel = Any(); FrameAccess.write(frame, 0, sentinel)
            val events = mutableListOf<String>()
            fun operand(name: String, value: Any?) = object : Expr() { override fun execute(frame: VirtualFrame): Any? { events.add(name); return value } }
            val state = object : Expr() { override fun execute(frame: VirtualFrame): Any {
                events.add("state"); assertSame(sentinel, FrameAccess.read(frame, 0)); assertArrayEquals(byteArrayOf(11, 22), array)
                if (fail) throw RuntimeFault("State failed"); return Unit
            } }
            val operands = when (operation) {
                PinnedMemoryOp.NEW -> arrayOf(operand("size", 2L), state)
                PinnedMemoryOp.NEW_ALIGNED -> arrayOf(operand("size", 2L), operand("alignment", 8L), state)
                PinnedMemoryOp.READ -> arrayOf(operand("address", address), operand("offset", 1L), state)
                else -> arrayOf(operand("address", address), operand("offset", 1L), operand("value", 511L), state)
            }
            val expression = PinnedMemoryExpression(operation, CoreRepresentation.UNKNOWN, operands)
            fun run() { if (operation.tuple) expression.executeTuple(frame, intArrayOf(0), 0) else expression.execute(frame) }
            if (fail) { assertThrows(RuntimeFault::class.java) { run() }; assertSame(sentinel, FrameAccess.read(frame, 0)); assertArrayEquals(byteArrayOf(11, 22), array) }
            else {
                run()
                when (operation) {
                    PinnedMemoryOp.NEW, PinnedMemoryOp.NEW_ALIGNED -> assertEquals(2L, (FrameAccess.read(frame, 0) as ManagedAllocation).size)
                    PinnedMemoryOp.READ -> assertEquals(22L, FrameAccess.read(frame, 0))
                    else -> assertArrayEquals(byteArrayOf(11, -1), array)
                }
            }
            assertEquals(when (operation) {
                PinnedMemoryOp.NEW -> listOf("size", "state")
                PinnedMemoryOp.NEW_ALIGNED -> listOf("size", "alignment", "state")
                PinnedMemoryOp.READ -> listOf("address", "offset", "state")
                else -> listOf("address", "offset", "value", "state")
            }, events)
        }
        for (index in listOf(-1L, Long.MIN_VALUE, 2L, 1L shl 32, Long.MAX_VALUE)) {
            val sentinel = Any(); FrameAccess.write(frame, 0, sentinel)
            val expression = PinnedMemoryExpression(PinnedMemoryOp.READ, CoreRepresentation.UNKNOWN,
                arrayOf(literal(ManagedAddress.fromByteArray(byteArrayOf(3, 4))), literal(index), literal(Unit)))
            assertThrows(RuntimeFault::class.java) { expression.executeTuple(frame, intArrayOf(0), 0) }
            assertSame(sentinel, FrameAccess.read(frame, 0))
        }
    }
    @Test fun keepAliveEvaluatesKeptReferenceAndStateThenRunsActionExactlyOnce() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        for (failure in listOf("none", "state", "action")) {
            val events = mutableListOf<String>(); val kept = Any()
            fun expression(name: String, value: Any?) = object : Expr() { override fun execute(frame: VirtualFrame): Any? {
                events.add(name); if (failure == name) throw RuntimeFault(name); return value
            } }
            val node = KeepAliveExpression(expression("kept", kept), expression("state", Unit), expression("action", 77L), CoreRepresentation.UNKNOWN)
            if (failure == "none") assertEquals(77L, node.executeLong(frame)) else assertThrows(RuntimeFault::class.java) { node.executeLong(frame) }
            assertEquals(if (failure == "state") listOf("kept", "state") else listOf("kept", "state", "action"), events)
        }
    }
}
