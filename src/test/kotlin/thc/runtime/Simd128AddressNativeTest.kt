// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
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
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Original GHC LLVM/Core bytes compared with a separate scalar-byte model. */
class Simd128AddressNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/simd128-addresses"
    private val shapes = listOf("int8X16", "word8X16", "int16X8", "word16X8", "int64X2", "word64X2")
    private val entries = shapes.flatMap { shape -> listOf("Index", "Read", "Write").flatMap { op ->
        listOf("Packed", "Scalar").map { shape + op + it }
    } }
    private val seeds = listOf(Long.MIN_VALUE, -4294967297L, -65537L, -32769L, -129L, -1L, 0L, 1L,
        127L,128L,255L,256L,32767L,65535L,4294967297L,Long.MAX_VALUE)
    private val pattern = Regex("(int|word)(8|16|64)X(16|8|2)(Index|Read|Write)(Packed|Scalar)")
    private data class Input(val entry: String, val seed: Long, val offset: Long)
    private fun width(entry: String) = pattern.matchEntire(entry)!!.groupValues[2].toInt() / 8
    private val requests = entries.flatMap { entry ->
        val end = 32L / width(entry)
        seeds.flatMap { seed -> (if (entry.endsWith("Scalar")) listOf(0L,1L,2L,end-1,end) else listOf(0L,1L,2L))
            .map { offset -> Input(entry, seed, offset) }
    } }
    // No Vector API or THC primitive implementation participates in this model.
    private fun expected(input: Input): Long {
        val match = pattern.matchEntire(input.entry)!!.groupValues
        val width = match[2].toInt()/8
        val lanes = match[3].toInt()
        val offset = (input.offset * if (match[5] == "Scalar") width else 16).toInt()
        val bytes = ByteArray(48) { (input.seed + it * 37).toByte() }
        if (match[4] == "Write") {
            for (lane in 0 until lanes) {
                val value = input.seed * 23 + lane * 97
                for (byte in 0 until width) bytes[offset + lane * width + byte] = (value ushr (byte * 8)).toByte()
            }
            return bytes.indices.sumOf { (bytes[it].toLong() and 255) * (2L*it+1) }
        }
        return (0 until lanes).sumOf { lane ->
            val raw = (0 until width).fold(0L) { bits, byte ->
                bits or ((bytes[offset+lane*width+byte].toLong() and 255) shl (byte*8))
            }
            val value = if (match[1] == "word" || width == 8) raw else raw shl (64-width*8) shr (64-width*8)
            value * (2L*lane+1)
        }
    }
    private fun oracle(text: String): List<Pair<Input,Long>> {
        val rows = text.lineSequence().filter(String::isNotEmpty).map { line ->
            val fields = line.split('\t'); require(fields.size == 4)
            Input(fields[0], fields[1].toLong(), fields[2].toLong()) to fields[3].toLong()
        }.toList()
        require(rows.map { it.first } == requests) { "Changed SIMD128 native row domain/order" }
        rows.forEach { (input,value) -> require(expected(input) == value) { "Native/model mismatch: $input: $value" } }
        return rows
    }
    private fun read(path: String) = Json.parse(File(root,path).readText()) as Map<String,Any?>
    private fun digest(path: String): String {
        val file = root.toPath().resolve(path).normalize()
        require(!File(path).isAbsolute && file.startsWith(root.toPath()))
        return MessageDigest.getInstance("SHA-256").digest(file.toFile().readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun evidence(): Map<String,Any?> {
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "Pinned SIMD128 oracle platform")
        val proof = read("$directory/manifest.json")
        assertEquals(1L,proof["schema"]); assertEquals("9.14.1",proof["ghc"])
        assertEquals(entries,proof["entries"]); assertEquals(2304L,proof["requests"])
        val arm = System.getProperty("os.arch").lowercase() in listOf("aarch64","arm64")
        assertEquals(if (arm) null else 2304L, proof["nativeRows"], "Native evidence cannot be downgraded by a manifest flag")
        for (kind in listOf("inputHashes","artifactHashes")) {
            val files = proof[kind] as Map<String,String>; assertTrue(files.isNotEmpty())
            for ((path,hash) in files) assertEquals(hash,digest(path),"Stale $kind: $path")
        }
        val inputs = proof["inputHashes"] as Map<String,String>
        for (path in listOf("compiler/test-fixtures/Simd128AddressAudit.hs", "compiler/test-fixtures/Simd128AddressNative.hs",
            "test/haskell-fixtures/Simd128AddressFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/Main.hs","thc.cabal","compiler/export.sh","scripts/core_vector_memory.py",
            "scripts/core_vectors.py","scripts/simd-families.json","scripts/core-capabilities.json"))
            assertTrue(path in inputs, "Unfingerprinted source: $path")
        val artifacts = proof["artifactHashes"] as Map<String,String>
        assertTrue("$directory/inputs.tsv" in artifacts)
        val recordedInputs = File(root,"$directory/inputs.tsv").readLines().map { line ->
            val fields = line.split('\t'); Input(fields[0],fields[1].toLong(),fields[2].toLong())
        }
        assertEquals(requests,recordedInputs)
        if (!arm) {
            assertTrue("$directory/oracle.tsv" in artifacts); assertTrue("$directory/native/oracle" in artifacts)
            oracle(File(root,"$directory/oracle.tsv").readText())
        }
        val stages = proof["stages"] as Map<String,Map<String,Any?>>
        assertEquals(if (arm) setOf("pre") else setOf("pre","post"),stages.keys)
        for ((stage,data) in stages) {
            val core = data["core"] as String
            assertTrue(core in artifacts)
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep",
                read(core)["boundary"])
            val records = data["entries"] as Map<String,Map<String,Any?>>
            assertEquals(entries.toSet(),records.keys)
            for ((name,record) in records) {
                val path = record["audit"] as String; assertTrue(path in artifacts)
                val audit = read(path)
                assertEquals(listOf("main:Simd128AddressAudit.$name"),audit["roots"])
                assertEquals(true,audit["accepted"])
                assertEquals(emptyList<Any>(),audit["issues"]); assertEquals(emptyList<Any>(),audit["missingGlobals"])
                val shape = record["structure"] as Map<String,Any?>
                assertEquals(if ("Write" in name) 5L else 4L,shape["guestCalls"])
                assertEquals(1L,shape["immediateLambdas"])
                assertEquals(1L,shape["keepAliveContinuations"])
                assertEquals(listOf("main:Simd128AddressAudit.$name","main:Simd128AddressAudit.initialize") +
                    if ("Write" in name) listOf("main:Simd128AddressAudit.checksum") else emptyList<String>(), shape["globalFunctions"])
            }
        }
        return proof
    }
    @Test fun nativeCorpusMatchesIndependentModelAndRejectsMissingReorderedAndAlteredRows() {
        evidence()
        val model = requests.joinToString("\n") { "${it.entry}\t${it.seed}\t${it.offset}\t${expected(it)}" }
        assertEquals(requests.size,oracle(model).size)
        val lines = model.lines()
        for (bad in listOf(lines.drop(1),lines.reversed(),lines + lines.first(),
            listOf(lines.first().substringBeforeLast('\t') + "\t1234567") + lines.drop(1)))
            assertThrows(IllegalArgumentException::class.java) { oracle(bad.joinToString("\n")) }
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget,Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it,DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry); return targets
    }
    @Test fun nativeOriginalCoreHasExactFirstInstalledEntriesWithInlining() = execute(true)
    private fun execute(inlining: Boolean) {
        val proof = evidence()
        for ((stage,data) in proof["stages"] as Map<String,Map<String,Any?>>) for (backend in listOf("ast","bytecode"))
            Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining",inlining.toString())
                .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
                .option("engine.SingleTierCompilationThreshold","10000000").option("engine.CompilationFailureAction","Throw")
                .build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val core = read(data["core"] as String)
                        for (entry in entries) {
                            val linked = CoreModules.reachable(core,entry) + ("instrument" to true)
                            val p: ExecutableProgram = if (backend == "ast") Program(language,linked) else BytecodeProgram(language,linked)
                            val host = p.hostEntryTarget(2); val closure = p.entryValue(entry); val original = p.entryTarget(entry)
                            val cases = requests.filter { it.entry == entry }
                            fun selected() = NodeUtil.findAllNodeInstances(host.rootNode,DirectCallNode::class.java)
                                .single { it.callTarget === original }.currentCallTarget as RootCallTarget
                            fun count() = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                            fun valid(target: RootCallTarget, label: String) =
                                assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target),label)
                            fun call(input: Input) {
                                assertEquals(expected(input),Calls.target(host,arrayOf(closure,arrayOf(input.seed,input.offset))),
                                    "$stage/$backend/$input/inlining=$inlining")
                                val pools = language.handoffState.get()
                                assertEquals(0,pools.arguments.depth); assertEquals(0,pools.results.depth)
                                assertEquals(0,pools.arguments.retainedReferences()); assertEquals(0,pools.results.retainedReferences())
                            }
                            cases.forEach(::call)
                            val target = selected()
                            val targets = activeTargets(target)
                            val expectedCalls = if ("Write" in entry) 5 else 4
                            assertEquals(expectedCalls,targets.size,"$stage/$backend/$entry root shape")
                            for (installed in targets + host) {
                                installed.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(installed,true)
                                valid(installed,"initial $entry")
                            }
                            val runtime = Truffle.getRuntime()
                            runtime.javaClass.getMethod("bypassedInstalledCode",Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime,host)
                            for (input in cases.asReversed()) {
                                val label = "$stage/$backend/$input/inlining=$inlining"
                                val before = count()
                                call(input)
                                assertEquals(expectedCalls.toLong(),count()-before,"$label exact compiled entries")
                                assertSame(target,selected(),label)
                                val active = activeTargets(target)
                                assertEquals(targets.size,active.size,label)
                                assertTrue(active.all { candidate -> targets.any { it === candidate } },label)
                                (targets + host).forEach { valid(it,label) }
                            }
                            for (counter in listOf("unsupportedTraps","blackholes"))
                                assertEquals(0L,(p.diagnostics().getValue(counter) as Number).toLong())
                        }
                    } finally { context.leave() }
                }
    }
}
