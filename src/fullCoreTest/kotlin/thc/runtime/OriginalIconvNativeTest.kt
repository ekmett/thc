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
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

class OriginalIconvNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun document(name: String) = Json.parse(File(root, "build/original-iconv/$name").readText()) as Map<String, Any?>
    private fun context(native: Boolean = true, inlining: Boolean = true) = Context.newBuilder("thc", "llvm")
        .allowIO(IOAccess.ALL).allowNativeAccess(native).allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun string(value: String) = ManagedAddress.fromByteArray(value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
    private fun pointer(value: ManagedAddress) = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8)).also {
        it.writeAddressElementIndex(0, value)
    }
    private fun count(value: Long) = ManagedAddress.fromByteArray(ByteArray(8)).also { it.writeNativeScalar(0, 8, value) }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val next = call.currentCallTarget as? RootCallTarget ?: continue
                if (next.rootNode is GuestRoot) visit(next)
            }
            result += target
        }
        visit(entry); return result
    }

    @Test fun actualOriginalImportsMatchNativeIncludingFirstCompiledCalls() {
        val oracle = document("oracle.json")
        val manifest = document("manifest.json")
        assertEquals(10L, manifest["nativeRows"])
        for (field in listOf("inputHashes", "artifactHashes"))
            OriginalStdioChecks.hashes(root, manifest[field], if (field == "inputHashes")
                setOf("compiler/test-fixtures/OriginalIconvAudit.hs", "compiler/test-fixtures/OriginalIconvAuditNative.hs")
                else setOf("build/original-iconv/OriginalIconvAudit.json", "build/original-iconv/oracle.json"))
        val declarations = document("declarations.json")
        assertEquals(false, declarations["completeModule"])
        assertEquals(false, declarations["installedArtifactsHashed"])
        assertEquals(true, declarations["typeEqualityChecked"])
        assertEquals(true, declarations["originalIdentityChecked"])
        val originals = OriginalStdioChecks.foreignCalls(declarations)
        for (stage in listOf("pre", "post")) {
            val module = document(if (stage == "pre") "PreIconvAudit.json" else "OriginalIconvAudit.json")
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep", module["boundary"])
            val actual = (manifest["entries"] as List<String>).flatMap {
                OriginalStdioChecks.foreignCalls(CoreModules.reachable(module, it))
            }
            assertEquals(4, actual.size)
            for (call in actual) {
                val descriptor = (call.last() as Map<*, *>)["foreignCall"]
                val candidates = originals.filter { (it.last() as Map<*, *>)["foreignCall"] == descriptor }
                assertTrue(candidates.isNotEmpty(), "exact original foreign descriptor")
                val originalPrefix = "ghc-internal:GHC.Internal.IO.Encoding.Iconv."
                val adaptedPrefix = "main:OriginalIconvAudit."
                val head = call[1] as List<*>
                assertTrue((head[1] as String).startsWith(adaptedPrefix))
                // Private Ids retain their exact Unique/type, scoped by serialization
                // to the consumer module. Their C target's unit remains original.
                assertTrue(candidates.any {
                    val original = it[1] as List<*>
                    (original[1] as String).startsWith(originalPrefix) && original[0] == head[0] && original[2] == head[2] &&
                        (original[1] as String).removePrefix(originalPrefix) == (head[1] as String).removePrefix(adaptedPrefix)
                }, "original private FCallId Unique/type")
            }
            for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) {
                context(inlining = inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val state = Language.currentState()
                        val names = manifest["entries"] as List<String>
                        val programs = names.associateWith { name ->
                            val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                            if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        }
                        val entries = programs.mapValues { (name, program) -> program.entryTarget(name) }
                        var compiled = false
                        val active = HashMap<String, List<RootCallTarget>>()
                        fun call(name: String, vararg args: Any): Any? {
                            val program = programs.getValue(name)
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val result = Calls.target(entries.getValue(name), arrayOf(0L, *args))
                            if (compiled) {
                                assertEquals(before + active.getValue(name).count { it.rootNode is GuestRoot },
                                    (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "first compiled $stage/$backend/$inlining/$name")
                                active.getValue(name).forEach(::valid)
                            }
                            return result
                        }
                        fun exercise() {
                            val locale = call("originalLocale", 0L) as ManagedAddress
                            assertEquals(oracle["locale"], locale.utf8())
                            assertTrue(locale.sameLocation(call("originalLocale", 0L) as ManagedAddress))
                            assertThrows(RuntimeFault::class.java) { locale.writeWord8(0, 0) }
                            for (row in oracle["rows"] as List<Map<String, Any?>>) {
                                val id = call("originalIconvOpen", string(row["to"] as String), string(row["from"] as String)) as Long
                                assertTrue(id > 0)
                                for ((stage, reset) in listOf("first" to false, "continued" to false, "smallReset" to true, "reset" to true)) {
                                    val inputBytes = (row[if (stage == "first") "input" else "continuationInput"] as List<Number>)
                                        .map { it.toByte() }.toByteArray()
                                    val input = ManagedAddress.fromByteArray(inputBytes)
                                    val inputCell = pointer(input)
                                    val inputCount = count(inputBytes.size.toLong())
                                    val expected = row[stage] as Map<String, Any?>
                                    val capacity = if (stage == "first") (row["capacity"] as Number).toInt()
                                        else if (stage == "smallReset") 2 else 16
                                    val bytes = ByteArray(capacity + 2) { 0xa5.toByte() }
                                    val output = ManagedAddress.fromByteArray(bytes).plus(1)
                                    val outputCell = pointer(output)
                                    val outputCount = count(capacity.toLong())
                                    state.stdio.nativeError(123)
                                    assertEquals(expected["result"], call("originalIconv", id,
                                        if (reset) ManagedAddress.nullAddress() else inputCell, inputCount, outputCell, outputCount), row["label"].toString())
                                    val error = expected["errno"] as Long
                                    assertEquals(if (error == 0L) 123L else error, state.stdio.errno(), "$stage sticky errno")
                                    if (!reset) {
                                        val consumed = expected["consumed"] as Long
                                        assertTrue(inputCell.readAddressElementIndex(0).sameLocation(input.plus(consumed)))
                                        assertEquals(inputBytes.size - consumed, ManagedAddressRead.WORD64.read(inputCount, 0))
                                    }
                                    val produced = expected["produced"] as Long
                                    assertTrue(outputCell.readAddressElementIndex(0).sameLocation(output.plus(produced)))
                                    assertEquals(capacity - produced, ManagedAddressRead.WORD64.read(outputCount, 0))
                                    assertEquals(0xa5.toByte(), bytes.first()); assertEquals(0xa5.toByte(), bytes.last())
                                    assertEquals(expected["bytes"], bytes.drop(1).take(capacity).map { (it.toInt() and 255).toLong() })
                                }
                                assertEquals(row["closed"], call("originalIconvClose", id))
                                assertEquals(0, state.iconv.liveHandles())
                            }
                            assertEquals(-1L, call("originalIconvOpen", string("THC-invalid-encoding"), string("UTF-8")))
                            assertEquals(oracle["missingErrno"], state.stdio.errno())
                        }
                        exercise()
                        for ((name, target) in entries) {
                            active[name] = targets(target)
                            active.getValue(name).forEach {
                                it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it)
                            }
                        }
                        compiled = true
                        exercise() // Assertions begin with the very first installed execution.
                        programs.values.forEach { assertEquals(0L, it.diagnostics()["unsupportedTraps"]) }
                        val pools = language.handoffState.get()
                        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
                        assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
                    } finally { context.leave() }
                }
            }
        }
    }

    @Test fun malformedOriginalDescriptorsAreRejectedBeforeEffects() {
        val manifest = document("manifest.json")
        assertEquals(44L, manifest["negativeAudits"])
        val badModules = File(root, "build/original-iconv/negative").listFiles()!!
            .filter { it.extension == "json" && !it.name.endsWith(".audit.json") }
        assertEquals(11, badModules.size)
        context(native = false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (file in badModules) for (name in manifest["entries"] as List<String>) {
                    val module = Json.parse(file.readText()) as Map<String, Any?>
                    val linked = CoreModules.reachable(module, name)
                    assertThrows(RuntimeFault::class.java, { Program(language, linked) }, "$file/$name AST")
                    assertThrows(RuntimeFault::class.java, { BytecodeProgram(language, linked) }, "$file/$name BC")
                }
                assertEquals(0, Language.currentState().iconv.liveHandles())
            } finally { context.leave() }
        }
    }

    @Test fun ownershipBoundsResetAndNativeAccessAreChecked() {
        context(native = false).use { context ->
            context.initialize("thc"); context.enter()
            try { assertThrows(RuntimeFault::class.java) { Language.currentState().iconv.open(string("UTF-8"), string("UTF-8")) } }
            finally { context.leave() }
        }
        var escaped = 0L
        lateinit var disposed: ManagedIconv
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val iconv = Language.currentState().iconv
                disposed = iconv
                escaped = iconv.open(string("UTF-8"), string("UTF-8"))
                val input = ManagedAddress.fromByteArray(byteArrayOf(65, 66))
                val inputCell = pointer(input)
                val output = ManagedAddress.fromByteArray(ByteArray(4) { 0x55 })
                val outputCell = pointer(output)
                val inputCount = count(2)
                val outputCount = count(4)
                fun bad(action: () -> Unit) {
                    assertThrows(RuntimeFault::class.java, action)
                    assertTrue(inputCell.readAddressElementIndex(0).sameLocation(input))
                    assertTrue(outputCell.readAddressElementIndex(0).sameLocation(output))
                    assertEquals(2L, ManagedAddressRead.WORD64.read(inputCount, 0))
                    assertEquals(4L, ManagedAddressRead.WORD64.read(outputCount, 0))
                    assertEquals(listOf(85L,85L,85L,85L), (0L..3L).map(output::readWord8))
                }
                bad { iconv.convert(escaped, inputCell, count(-1), outputCell, outputCount) }
                bad { iconv.convert(escaped, inputCell, count(3), outputCell, outputCount) }
                bad { iconv.convert(escaped, inputCell, inputCount, outputCell, count(5)) }
                bad { iconv.convert(escaped, inputCell, inputCount, outputCell, inputCount) }
                bad { iconv.convert(escaped, inputCell, inputCount, pointer(input), count(2)) }
                bad { iconv.convert(escaped, inputCell, inputCount, pointer(ManagedAddress.fromHex("00000000")), outputCount) }
                bad { iconv.convert(escaped, inputCell, inputCount, pointer(inputCell), outputCount) }
                bad { iconv.convert(escaped, inputCell, inputCount,
                    outputCell, ManagedAddress.fromByteArray(ByteArray(9)).plus(1)) }
                val unalignedCell = ManagedAddress.fromAllocation(ManagedAllocation.mutable(9, 8)).plus(1)
                unalignedCell.writeAddressElementIndex(0, output)
                bad { iconv.convert(escaped, inputCell, inputCount, unalignedCell, outputCount) }
                bad { iconv.convert(escaped, inputCell, inputCount, ManagedAddress.nullAddress(), outputCount) }
                assertEquals(0L, iconv.convert(escaped, ManagedAddress.nullAddress(), ManagedAddress.nullAddress(),
                    ManagedAddress.nullAddress(), ManagedAddress.nullAddress()))
                val closed = iconv.open(string("UTF-8"), string("UTF-8"))
                assertEquals(0L, iconv.close(closed))
                assertThrows(RuntimeFault::class.java) { iconv.close(closed) }
                assertThrows(RuntimeFault::class.java) { iconv.convert(closed, inputCell, inputCount, outputCell, outputCount) }
                context().use { other ->
                    other.initialize("thc"); other.enter()
                    try {
                        val otherIconv = Language.currentState().iconv
                        val ownHandle = otherIconv.open(string("UTF-8"), string("UTF-8"))
                        assertNotEquals(escaped, ownHandle)
                        assertThrows(RuntimeFault::class.java) { otherIconv.close(escaped) }
                        assertThrows(RuntimeFault::class.java) {
                            otherIconv.convert(escaped, inputCell, inputCount, outputCell, outputCount)
                        }
                        assertEquals(1, otherIconv.liveHandles())
                        assertEquals(0L, otherIconv.close(ownHandle))
                    }
                    finally { other.leave() }
                }
                assertEquals(1, iconv.liveHandles()) // Deliberately left for context finalization.
            } finally { context.leave() }
        }
        assertEquals(0, disposed.liveHandles())
        assertThrows(RuntimeFault::class.java) { disposed.close(escaped) }
    }
}
