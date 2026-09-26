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
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.VectorShape
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

/** One original-Core corpus for the 18 already supported vector shapes. */
class SimdAddressFamiliesTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/simd-address-families"
    private val names = listOf("int32X4","word32X4","floatX4","doubleX2",
        "int16X16","word16X16","int32X8","word32X8","int32X16","word32X16",
        "int64X4","word64X4","int64X8","word64X8","floatX8","floatX16","doubleX4","doubleX8")
    private val entries = names.flatMap { shape -> listOf("Index","Read","Write").flatMap { operation ->
        listOf("Packed","Scalar").map { shape + operation + it }
    } }
    private val seeds = listOf(Long.MIN_VALUE,-129L,-1L,0L,1L,127L,65535L,Long.MAX_VALUE)
    // Every shape and every typed carrier/operation is compiled; the entire
    // 108-operation corpus is interpreted against both independent models.
    private val compiled = setOf("int32X4IndexPacked","word32X4ReadScalar","floatX4WritePacked","doubleX2IndexScalar",
        "int16X16IndexPacked","word16X16ReadScalar","word16X16WritePacked","int32X8WritePacked",
        "word32X8IndexScalar","int32X16ReadPacked","word32X16WriteScalar","int64X4IndexPacked",
        "word64X4ReadScalar","int64X8WritePacked","word64X8IndexScalar","floatX8ReadPacked",
        "floatX16IndexScalar","doubleX4WritePacked","doubleX8ReadScalar")
    private val pattern = Regex("(int16|word16|int32|word32|int64|word64|float|double)X(2|4|8|16)(Index|Read|Write)(Packed|Scalar)")
    private class Shape(name: String, pattern: Regex) {
        val groups = pattern.matchEntire(name)!!.groupValues
        val scalar = groups[1]
        val lanes = groups[2].toInt()
        val width = when (scalar) { "float" -> 4; "double" -> 8; else -> scalar.takeLast(2).toInt()/8 }
        val bytes = lanes * width
        val stride = if (groups[4] == "Scalar") width else bytes
        val write = groups[3] == "Write"
        val signed = scalar.startsWith("int")
    }
    private data class Input(val entry: String, val seed: Long, val offset: Long)
    private val requests = entries.flatMap { entry ->
        val shape = Shape(entry,pattern)
        seeds.flatMap { seed -> (if (entry.endsWith("Scalar")) listOf(0L,1L,2L*shape.lanes) else listOf(0L,1L,2L))
            .map { Input(entry,seed,it) }
    } }
    private fun initial(input: Input) = ByteArray(Shape(input.entry,pattern).bytes * 3) { (input.seed + it*37L).toByte() }
    // Scalar byte arithmetic only: no Vector API or runtime helper is used here.
    private fun expected(input: Input): Pair<Long,ByteArray> {
        val shape = Shape(input.entry,pattern)
        val bytes = initial(input)
        val offset = input.offset.toInt() * shape.stride
        if (shape.write) for (lane in 0 until shape.lanes) {
            val value = input.seed*23 + lane*97
            for (byte in 0 until shape.width) bytes[offset+lane*shape.width+byte] = (value ushr (byte*8)).toByte()
        }
        val answer = if (shape.write) 0L else (0 until shape.lanes).sumOf { lane ->
            val bits = (0 until shape.width).fold(0L) { value, byte ->
                value or ((bytes[offset+lane*shape.width+byte].toLong() and 255) shl (8*byte))
            }
            val value = if (shape.signed && shape.width < 8) bits shl (64-shape.width*8) shr (64-shape.width*8) else bits
            value * (2L*lane+1)
        }
        return answer to bytes
    }
    private fun checksum(bytes: ByteArray) = bytes.indices.sumOf { (bytes[it].toLong() and 255) * (2L*it+1) }
    private fun read(path: String) = Json.parse(File(root,path).readText()) as Map<String,Any?>
    private fun manifest(): Map<String,Any?> {
        assertEquals(ByteOrder.LITTLE_ENDIAN,ByteOrder.nativeOrder(),"Native corpus target byte order")
        val data = read("$directory/manifest.json")
        assertEquals(1L,data["schema"]); assertEquals("9.14.1",data["ghc"])
        assertEquals(entries,data["entries"]); assertEquals(2592L,data["scalarRows"])
        val primitives = entries.map { name ->
            val shape = Shape(name,pattern)
            val scalar = shape.scalar.replaceFirstChar { it.uppercaseChar() }
            val vector = scalar + "X" + shape.lanes
            shape.groups[3].lowercase() + (if (shape.groups[4] == "Scalar")
                scalar + "OffAddrAs" + vector else vector + "OffAddr") + "#"
        }
        assertEquals(primitives,data["primitives"])
        for (kind in listOf("inputHashes","artifactHashes")) {
            val hashes = data[kind] as Map<String,String>
            assertTrue(hashes.isNotEmpty())
            for ((path,hash) in hashes) {
                val file = root.toPath().resolve(path).normalize()
                require(!File(path).isAbsolute && file.startsWith(root.toPath()))
                val actual = MessageDigest.getInstance("SHA-256").digest(file.toFile().readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hash,actual,"Stale SIMD address $kind: $path")
            }
        }
        val native128 = System.getProperty("os.arch").lowercase() !in setOf("arm64","aarch64")
        assertEquals(if (native128) 576L else 0L,data["nativeVector128Rows"])
        for (mode in if (native128) listOf("scalar","vector128") else listOf("scalar")) {
            val selected = if (mode == "scalar") requests else requests.filter { Shape(it.entry,pattern).bytes == 16 }
            val lines = File(root,"$directory/$mode-oracle.tsv").readLines()
            assertEquals(selected.size,lines.size)
            for ((input,line) in selected.zip(lines)) {
                val (answer,bytes) = expected(input)
                assertEquals(listOf(input.entry,input.seed.toString(),input.offset.toString(),answer.toString(),checksum(bytes).toString()),
                    line.split('\t'),"$mode native/model: $input")
            }
        }
        return data
    }
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget,Boolean>())
        val answer = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it,DirectCallNode::class.java) }) {
                val child = call.currentCallTarget as? RootCallTarget ?: continue
                if (child.rootNode is GuestRoot) visit(child)
            }
            answer.add(target)
        }
        visit(entry); return answer
    }
    private fun nodes(value: Any?): List<List<*>> = when (value) {
        is List<*> -> listOf(value) + value.flatMap(::nodes)
        is Map<*,*> -> value.values.flatMap(::nodes)
        else -> emptyList()
    }
    @Test fun genuineCoreMatchesNativeModelsOnTheFirstInstalledCallInBothBackends() {
        val evidence = manifest()
        val stages = evidence["stages"] as Map<String,Map<String,Any?>>
        assertTrue("pre" in stages)
        for ((stage,data) in stages) {
            val audit = read(data["audit"] as String)
            assertEquals(true,audit["accepted"]); assertEquals(emptyList<Any>(),audit["issues"])
            assertEquals(emptyList<Any>(),audit["missingGlobals"])
            val core = read(data["core"] as String)
            val selected = data["entries"] as List<String>
            assertEquals(if (stage == "pre") entries else entries.filter { Shape(it,pattern).bytes == 16 },selected)
            for (backend in listOf("ast","bytecode")) Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining","false").option("engine.BackgroundCompilation","false")
                .option("engine.MultiTier","false").option("engine.SingleTierCompilationThreshold","10000000")
                .option("engine.CompilationFailureAction","Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        for (name in selected) {
                            val linked = CoreModules.reachable(core,name) + ("instrument" to true)
                            val bindings = linked["bindings"] as List<Map<String,Any?>>
                            assertEquals(1,bindings.size)
                            val expectedEntries = if ("Index" in name) 1L else 2L
                            assertEquals(expectedEntries,nodes(bindings).count { it.firstOrNull() == "lam" }.toLong())
                            val p: ExecutableProgram = if (backend == "ast") Program(language,linked) else BytecodeProgram(language,linked)
                            val entry = p.entryTarget(name)
                            val corpus = requests.filter { it.entry == name }
                            val pools = language.handoffState.get()
                            fun count() = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                            fun call(input: Input) {
                                val bytes = initial(input)
                                val (answer,model) = expected(input)
                                try {
                                    assertEquals(answer,Calls.target(entry,arrayOf(0L,ManagedAddress.fromByteArray(bytes),input.offset,input.seed)),
                                        "$stage/$backend/$input")
                                    assertArrayEquals(model,bytes,"$stage/$backend/$input full storage")
                                } finally {
                                    assertEquals(0,pools.arguments.depth); assertEquals(0,pools.results.depth)
                                    assertEquals(0,pools.arguments.retainedReferences()); assertEquals(0,pools.results.retainedReferences())
                                    assertNull(pools.pending)
                                }
                            }
                            corpus.forEach(::call)
                            assertEquals(0L,(p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                            if (name !in compiled) continue
                            val active = targets(entry)
                            assertEquals(expectedEntries,active.size.toLong())
                            val callCounts = active.map { it.javaClass.getMethod("getCallCount").invoke(it) }
                            val arguments = pools.arguments.allocations; val results = pools.results.allocations
                            val runtime = Truffle.getRuntime()
                            val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                            for (target in active) {
                                target.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(target,true)
                                assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target))
                                runtime.javaClass.getMethod("bypassedInstalledCode",targetClass).invoke(runtime,target)
                            }
                            assertEquals(0L,count()); assertEquals(callCounts,active.map { it.javaClass.getMethod("getCallCount").invoke(it) })
                            for (input in corpus.asReversed()) {
                                val before = count(); call(input)
                                assertEquals(expectedEntries,count()-before,"$stage/$backend/$name exact first installed entries")
                                assertEquals(active,targets(entry))
                                active.forEach { assertEquals(true,it.javaClass.getMethod("isValidLastTier").invoke(it)) }
                                assertEquals(arguments,pools.arguments.allocations); assertEquals(results,pools.results.allocations)
                            }
                            assertEquals(callCounts,active.map { it.javaClass.getMethod("getCallCount").invoke(it) })
                            assertEquals(0L,(p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        }
                    } finally { context.leave() }
                }
        }
    }

    /** Shared SIMD128 tests cover storage kinds and native lifetime. These check
     * the only new ownership dimension: whole 32-/64-byte spans and species. */
    @Test fun wideRegionsPreservePointerCellsAndRejectWrongSpeciesBeforeWriting() {
        for (size in listOf(32,64)) {
            val owner = ManagedAllocation.mutable((size*3).toLong(),8)
            val address = ManagedAddress.fromAllocation(owner)
            val species = ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(size*8))
            val vector = ByteVector.fromArray(species,ByteArray(size) { (it*17).toByte() },0)
            val pointer = address.plus(size*2L)
            owner.writeAddressByteOffset(size*2L,pointer)
            address.writeVectorBytes(0,1,vector,size)
            assertEquals(vector,address.readVectorBytes(0,1,size))
            assertSame(pointer,owner.readAddressByteOffset(size*2L))
            for (offset in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-1L,size*2L+1)) {
                assertThrows(RuntimeFault::class.java) { address.writeVectorBytes(offset,1,vector,size) }
                assertEquals(vector,address.readVectorBytes(0,1,size))
            }
            assertThrows(RuntimeFault::class.java) { address.readVectorBytes(size+1L,1,size) }
            assertThrows(RuntimeFault::class.java) { address.writeVectorBytes(0,1,ByteVector.zero(ByteVector.SPECIES_128),size) }
            owner.shrink(size.toLong())
            assertThrows(RuntimeFault::class.java) { address.readVectorBytes(1,1,size) }
            assertEquals(vector,address.readVectorBytes(0,1,size))
        }
    }

    @Test fun wideNativeRegionsKeepTheExistingLifetimeAndWholeSpanChecks() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64","x86_64"))
        Context.newBuilder("thc").allowNativeAccess(true).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val registry = Language.currentState().nativeAllocations
                for (size in listOf(32,64)) {
                    val address = registry.malloc(size*3L)
                    val species = ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(size*8))
                    val vector = ByteVector.fromArray(species,ByteArray(size) { (it*47+129).toByte() },0)
                    try {
                        address.writeVectorBytes(1,size,vector,size)
                        assertEquals(vector,address.plus(size*2L).readVectorBytes(-1,size,size))
                        assertArrayEquals(vector.toArray(),ByteArray(size) { address.readWord8(size+it.toLong()).toByte() })
                        assertThrows(RuntimeFault::class.java) { address.writeVectorBytes(2L*size+1,1,vector,size) }
                        assertThrows(RuntimeFault::class.java) { address.readVectorBytes(Long.MIN_VALUE,size,size) }
                        assertThrows(RuntimeFault::class.java) { address.readVectorBytes(Long.MAX_VALUE,size,size) }
                        assertEquals(vector,address.readVectorBytes(1,size,size))
                    } finally { registry.free(address) }
                    assertThrows(RuntimeFault::class.java) { address.readVectorBytes(0,1,size) }
                    assertThrows(RuntimeFault::class.java) { address.writeVectorBytes(0,1,vector,size) }
                }
                assertEquals(0,registry.liveCount())
            } finally { context.leave() }
        }
    }
}
