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
import thc.*
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class WideCharAddressTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val inputs = listOf(0L,1L,127L,128L,255L,256L,32767L,32768L,55295L,55296L,
        57343L,57344L,65535L,65536L,1114111L)
    private fun source(stage: String) = CoreModules.merge(listOf(
        "WideCharAddressAudit.json", "THC.InterfaceClosure.json").map {
        Json.parse(File(root, "build/wide-char-address/$stage/core/$it").readText()) as Map<String, Any?>
    })
    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::calls)
        is List<*> -> {
            val head = value.getOrNull(1) as? List<*>
            (if (value.firstOrNull() == "app" && head?.firstOrNull() == "prim") listOf(value) else emptyList()) +
                value.flatMap(::calls)
        }
        else -> emptyList()
    }
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val found = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val rootNode = target.rootNode
            val nodes = if (rootNode is BytecodeRoot) listOf(rootNode) +
                rootNode.bytecodeNode.instructions.flatMap { it.arguments }
                    .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(rootNode)
            nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }
                .filter { it.rootNode is GuestRoot }.forEach(::visit)
            found += target
        }
        visit(entry)
        return found
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)
    private fun rows(): List<Pair<Long,Long>> {
        val manifest = Json.parse(File(root, "build/wide-char-address/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, digest) in manifest[kind] as Map<String,String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(digest, actual, "$kind $path")
            }
        val pairs = File(root, manifest["oracle"] as String).readLines().map {
            val fields = it.split('\t'); assertEquals(2, fields.size)
            fields[0].toLong() to fields[1].toLong()
        }
        assertEquals(inputs, pairs.map { it.first })
        pairs.forEach { (input, result) -> assertEquals(input * 4294967296L + input, result) }
        return pairs
    }

    @Test fun originalWideCharCoreHasThreeExactPrimitiveContracts() {
        rows()
        val names = setOf("indexWideCharOffAddr#", "readWideCharOffAddr#", "writeWideCharOffAddr#")
        for (stage in listOf("pre", "post")) {
            val module = source(stage)
            val uses = calls(CoreModules.reachable(module, "wideCharRoundtrip"))
                .filter { (it[1] as List<*>).getOrNull(1) in names }
            assertEquals(names, uses.map { (it[1] as List<*>)[1] }.toSet())
            assertEquals(3, uses.size)
            for (app in uses) {
                val op = PinnedMemoryOp.named((app[1] as List<*>)[1] as String)!!
                val arguments = (app[2] as List<List<Any?>>).map(CoreRepresentations::expression)
                val result = CoreRepresentations.expression(app)
                op.validate(arguments, app[3] as List<*>, result)
                assertThrows(RuntimeFault::class.java) {
                    op.validate(arguments.toMutableList().also { it[0] = it[0].copy(primReps = listOf("IntRep")) },
                        app[3] as List<*>, result)
                }
                assertThrows(RuntimeFault::class.java) {
                    op.validate(arguments, List(arguments.size) { true }, result)
                }
                assertThrows(RuntimeFault::class.java) {
                    op.validate(arguments, app[3] as List<*>, result.copy(kind = CoreKind.DOUBLE, primReps = listOf("DoubleRep")))
                }
            }
            val audit = Json.parse(File(root, "build/wide-char-address/$stage/audit.json").readText()) as Map<String,Any?>
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
        }
    }

    @Test fun nativeWideCharRowsMatchInstalledCompiledAstAndBytecode() {
        val oracle = rows()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (inlining in listOf(false,true)) Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", inlining.toString())
                .option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val module = CoreModules.reachable(source(stage), "wideCharRoundtrip") + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, module)
                            else BytecodeProgram(language, module)
                        val entry = program.entryTarget("wideCharRoundtrip")
                        fun check(pair: Pair<Long,Long>) {
                            assertEquals(pair.second, Calls.target(entry, arrayOf(0L, pair.first)),
                                "$stage/$backend/$inlining/${pair.first}")
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                        repeat(3) { oracle.forEach(::check) }
                        val active = targets(entry)
                        assertTrue(active.size >= 2, "Retain the real runRW action")
                        active.forEach {
                            it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                            valid(it)
                        }
                        for (row in oracle.asReversed() + oracle) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= active.size)
                            active.forEach(::valid)
                        }
                        assertEquals(0L, (program.diagnostics()["unsupportedTraps"] as Number).toLong())
                    } finally { context.leave() }
                }
    }

    @Test fun wideCharUsesFourBytesAndRejectsInvalidStorageWithoutMutation() {
        val base = ManagedAddress.fromAllocation(PinnedMemory.allocate(32, 1))
        val interior = base.plus(12)
        for (value in inputs) {
            interior.writeNativeScalar(-1, 4, value)
            assertEquals(value, ManagedAddressRead.WIDE_CHAR.read(interior, -1))
            assertEquals(value, ManagedAddressRead.WIDE_CHAR.read(base, 2))
            for (byte in 0..3) {
                val shift = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte * 8 else (3 - byte) * 8
                assertEquals((value ushr shift) and 255L, base.readWord8(8L + byte))
            }
        }
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WIDE_CHAR.read(base, -1) }
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WIDE_CHAR.read(base, Long.MAX_VALUE) }
        assertThrows(RuntimeFault::class.java) { base.writeNativeScalar(-1, 4, 7) }
        assertThrows(RuntimeFault::class.java) { base.writeNativeScalar(8, 4, 7) }
        assertEquals(inputs.last(), ManagedAddressRead.WIDE_CHAR.read(base, 2))
        val target = base.plus(24)
        base.writeAddressElementIndex(2, target) // pointer cell at bytes 16..23
        assertThrows(RuntimeFault::class.java) { base.writeNativeScalar(4, 4, 7) }
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WIDE_CHAR.read(base, 4) }
        assertSame(target, base.readAddressElementIndex(2))
    }
}
