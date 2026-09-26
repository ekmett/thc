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

/** Original vector Core compared with native GHC scalar lanes and a separate byte model. */
class SimdWideArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/simd-wide-arrays"
    private val shapes = listOf("int8X32","word8X32","int8X64","word8X64","int16X32","word16X32",
        "int16X16","word16X16","int32X8","word32X8","int32X16","word32X16","int64X4","word64X4","int64X8","word64X8","floatX8","floatX16","doubleX4","doubleX8")
    private val entries = shapes.flatMap { shape -> listOf("Index","Read","Write").flatMap { op ->
        listOf("Packed","Scalar").map { shape+op+it }
    } }
    private val seeds = listOf(Long.MIN_VALUE,-129L,-1L,0L,1L,127L,65535L,Long.MAX_VALUE)
    private val pattern = Regex("(int8|word8|int16|word16|int32|word32|int64|word64|float|double)X(\\d+)(Index|Read|Write)(Packed|Scalar)")
    private data class Input(val entry: String, val seed: Long, val offset: Long)
    private fun fields(entry: String) = pattern.matchEntire(entry)!!.groupValues
    private fun width(entry: String) = when (val scalar = fields(entry)[1]) {
        "float" -> 4; "double" -> 8; else -> scalar.dropWhile { !it.isDigit() }.toInt()/8
    }
    private fun size(entry: String) = width(entry)*fields(entry)[2].toInt()*3
    private val requests = entries.flatMap { entry ->
        val lanes = fields(entry)[2].toLong()
        seeds.flatMap { seed -> (if (entry.endsWith("Scalar")) listOf(0L,1L,2*lanes) else listOf(0L,1L,2L))
            .map { offset -> Input(entry,seed,offset) }
    } }
    private fun initial(input: Input) = ByteArray(size(input.entry)) { (input.seed+it*37).toByte() }
    // Scalar byte model, independent from both the Vector API and native GHC scalar primops.
    private fun expected(input: Input): Pair<Long,ByteArray> {
        val shape = fields(input.entry)
        val width = width(input.entry)
        val lanes = shape[2].toInt()
        val offset = (input.offset * if (shape[4] == "Scalar") width else width*lanes).toInt()
        val bytes = initial(input)
        if (shape[3] == "Write") {
            for (lane in 0 until lanes) for (byte in 0 until width)
                bytes[offset+lane*width+byte] = ((input.seed*23+lane*97) ushr (byte*8)).toByte()
            return 0L to bytes
        }
        val sum = (0 until lanes).sumOf { lane ->
            val raw = (0 until width).fold(0L) { bits, byte ->
                bits or ((bytes[offset+lane*width+byte].toLong() and 255) shl (byte*8))
            }
            val value = if (shape[1].startsWith("int") && width < 8) raw shl (64-width*8) shr (64-width*8) else raw
            value*(2L*lane+1)
        }
        return sum to bytes
    }
    private fun checksum(bytes: ByteArray) = bytes.indices.sumOf { (bytes[it].toLong() and 255)*(2L*it+1) }
    private fun oracle(text: String) {
        val rows = text.lineSequence().filter(String::isNotEmpty).map { line ->
            val fields = line.split('\t'); require(fields.size == 5)
            val input = Input(fields[0],fields[1].toLong(),fields[2].toLong())
            val (result,bytes) = expected(input)
            require(result == fields[3].toLong() && checksum(bytes) == fields[4].toLong()) { "Native/model mismatch: $input" }
            input
        }.toList()
        require(rows == requests) { "Changed wide SIMD scalar-native input domain/order" }
    }
    private fun read(path: String) = Json.parse(File(root,path).readText()) as Map<String,Any?>
    private fun digest(path: String): String {
        val file = root.toPath().resolve(path).normalize()
        require(!File(path).isAbsolute && file.startsWith(root.toPath()))
        return MessageDigest.getInstance("SHA-256").digest(file.toFile().readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun evidence(): Map<String,Any?> {
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "Pinned SIMD wide oracle platform")
        val proof = read("$directory/manifest.json")
        assertEquals(1L,proof["schema"]); assertEquals("9.14.1",proof["ghc"])
        assertEquals(entries,proof["entries"]); assertEquals(2880L,proof["requests"])
        assertEquals(2880L,proof["nativeRows"])
        assertEquals("scalar-lane",proof["nativeMode"])
        for (kind in listOf("inputHashes","artifactHashes")) {
            val files = proof[kind] as Map<String,String>; assertTrue(files.isNotEmpty())
            for ((path,hash) in files) assertEquals(hash,digest(path),"Stale $kind: $path")
        }
        val inputs = proof["inputHashes"] as Map<String,String>
        for (path in listOf("compiler/test-fixtures/SimdWideArrayAudit.hs", "compiler/test-fixtures/SimdWideArrayNative.hs","compiler/test-fixtures/SimdWideArrayScalar.hs",
            "test/haskell-fixtures/SimdWideArrayFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/Main.hs","thc.cabal","compiler/export.sh","scripts/core_vector_memory.py",
            "scripts/core_vectors.py","scripts/simd-families.json","scripts/core-capabilities.json"))
            assertTrue(path in inputs, "Unfingerprinted source: $path")
        val artifacts = proof["artifactHashes"] as Map<String,String>
        assertTrue("$directory/inputs.tsv" in artifacts)
        val recordedInputs = File(root,"$directory/inputs.tsv").readLines().map { line ->
            val fields = line.split('\t'); Input(fields[0],fields[1].toLong(),fields[2].toLong())
        }
        assertEquals(requests,recordedInputs)
        assertTrue("$directory/oracle.tsv" in artifacts); assertTrue("$directory/native/oracle" in artifacts)
        oracle(File(root,"$directory/oracle.tsv").readText())
        val stages = proof["stages"] as Map<String,Map<String,Any?>>
        assertEquals(setOf("pre"),stages.keys)
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
                assertEquals(listOf("main:SimdWideArrayAudit.$name"),audit["roots"])
                assertEquals(true,audit["accepted"])
                assertEquals(emptyList<Any>(),audit["issues"]); assertEquals(emptyList<Any>(),audit["missingGlobals"])
                val shape = record["structure"] as Map<String,Any?>
                assertEquals(2L,shape["guestCalls"])
                assertEquals(1L,shape["immediateLambdas"])
                assertEquals(listOf("main:SimdWideArrayAudit.$name"), shape["globalFunctions"])
            }
        }
        return proof
    }
    @Test fun nativeScalarCorpusMatchesIndependentByteModel() { evidence() }
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
    @Test fun nativeOriginalCoreHasExactFirstInstalledEntries() = execute(false)
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
                            val arity = if ("Write" in entry) 3 else 2
                            val host = p.hostEntryTarget(arity); val closure = p.entryValue(entry); val original = p.entryTarget(entry)
                            val cases = requests.filter { it.entry == entry }
                            fun selected() = NodeUtil.findAllNodeInstances(host.rootNode,DirectCallNode::class.java)
                                .single { it.callTarget === original }.currentCallTarget as RootCallTarget
                            fun count() = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                            fun valid(target: RootCallTarget, label: String) =
                                assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target),label)
                            fun call(input: Input) {
                                val bytes = initial(input)
                                val (result,model) = expected(input)
                                assertEquals(result,Calls.target(host,arrayOf(closure,if (arity == 3) arrayOf(bytes,input.offset,input.seed) else arrayOf(bytes,input.offset))),
                                    "$stage/$backend/$input/inlining=$inlining")
                                assertArrayEquals(model,bytes,"complete buffer: $input")
                                val pools = language.handoffState.get()
                                assertEquals(0,pools.arguments.depth); assertEquals(0,pools.results.depth)
                                assertEquals(0,pools.arguments.retainedReferences()); assertEquals(0,pools.results.retainedReferences())
                            }
                            cases.forEach(::call)
                            val target = selected()
                            val targets = activeTargets(target)
                            val expectedCalls = 2
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
