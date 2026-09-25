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
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/** Adapted scalar Haskell consumers retain actual installed IO.FD FCallIds.
 * This proves the foreign boundary, not whole unchanged Handle/FD execution. */
class OriginalFdReadyNativeTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixture = File(root, "build/original-fd-ready")
    private val entries = listOf("originalReadySafe", "originalReadyUnsafe")
    private fun document(name: String) = Json.parse(File(fixture, name).readText()) as Map<String, Any?>
    private fun module() = document("OriginalFdReadyAudit.json")
    private fun context(inlining: Boolean = true, output: ByteArrayOutputStream = ByteArrayOutputStream()) =
        Context.newBuilder("thc").allowIO(IOAccess.ALL).out(output).err(output).allowExperimentalOptions(true)
            .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
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
        visit(entry)
        return result
    }
    private fun released(language: Language) {
        val pools = language.handoffState.get()
        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
        assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
    }

    @Test fun originalReadyMatchesNativeForRegularFilesEofClosedAndIgnoredDescriptors() {
        val manifest = document("manifest.json")
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries, manifest["entries"]); assertEquals(168L, manifest["nativeRows"])
        assertEquals(24L, manifest["negativeAudits"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalFdReadyAudit.hs", "compiler/test-fixtures/OriginalFdReadyAuditNative.hs",
            "test/haskell-fixtures/OriginalFdReadyFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/original-fd-ready/oracle.json", "build/original-fd-ready/OriginalFDDeclarations.json",
            "build/original-fd-ready/Template.json", "build/original-fd-ready/OriginalFdReadyAudit.json"),
            "build/original-fd-ready/")
        val facts = document("facts.json")
        assertEquals(5L, facts["originalCalls"]); assertEquals(2L, facts["adaptedCalls"])
        assertEquals(true, facts["typeEqualityChecked"]); assertEquals(false, facts["installedArtifactsHashed"])
        assertEquals(true, facts["originalIdentityChecked"])
        assertEquals(listOf(false, false), facts["originalNamesExternal"])
        assertEquals("original-interface-foreign-declarations-only", facts["originalProjection"])
        val oracle = Json.parse(File(fixture, "oracle.json").readText()) as List<Map<String, Any?>>
        val expectedRequests = entries.flatMap { entry ->
            listOf("read", "write", "readwrite", "eof", "closed", "negative").flatMap { scenario ->
                listOf(0L, 1L).flatMap { writing -> listOf(0L, 1L).flatMap { socket ->
                    (if (scenario == "negative") listOf(0L) else listOf(-1L, 0L, 1L, 2147483649L)).map { milliseconds ->
                        listOf(entry, scenario, writing, milliseconds, socket)
                    }
                } }
            }
        }
        assertEquals(expectedRequests, oracle.map { row ->
            listOf(row["entry"], row["scenario"], row["writing"], row["milliseconds"], row["socket"])
        })
        val badFd = StdioHostAbi.load().error(4)
        for (row in oracle) {
            assertEquals(if (row["scenario"] == "negative") 0L else 1L, row["result"])
            assertEquals(badFd, row["errnoBefore"]); assertEquals(badFd, row["errnoAfter"])
        }
        val original = document("OriginalFDDeclarations.json")
        assertEquals(false, original["completeModule"])
        assertEquals("original-interface-foreign-declarations-only", original["projection"])
        val originalCalls = OriginalStdioChecks.foreignCalls(original).filter {
            (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] == "fdReady"
        }
        assertEquals(5, originalCalls.size)
        for (name in entries) {
            val linked = CoreModules.reachable(module(), name)
            val call = OriginalStdioChecks.foreignCalls(linked).single()
            val descriptor = (call[6] as Map<*, *>)["foreignCall"] as Map<*, *>
            val matched = originalCalls.filter {
                ((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["safety"] == descriptor["safety"]
            }
            assertFalse(matched.isEmpty())
            val originalHead = matched.first()[1] as List<*>
            val adaptedHead = call[1] as List<*>
            assertEquals(3, originalHead.size); assertEquals(3, adaptedHead.size)
            assertEquals(originalHead[0], adaptedHead[0]); assertEquals(originalHead[2], adaptedHead[2])
            // Private GHC names retain their Unique/type but serialization scopes
            // them to the consuming module; the C symbol's unit stays original.
            val originalPrefix = "ghc-internal:GHC.Internal.IO.FD."
            val adaptedPrefix = "main:OriginalFdReadyAudit."
            assertTrue((originalHead[1] as String).startsWith(originalPrefix))
            assertTrue((adaptedHead[1] as String).startsWith(adaptedPrefix))
            assertEquals((originalHead[1] as String).removePrefix(originalPrefix),
                (adaptedHead[1] as String).removePrefix(adaptedPrefix), "exact private FCallId occurrence and Unique")
            assertEquals((matched.first()[6] as Map<*, *>)["foreignCall"], descriptor)
            OriginalStdioChecks.audit(document("$name.audit.json"), "main:OriginalFdReadyAudit.$name", listOf("fdReady"))
        }
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val files = Language.currentState().files
                val stdio = Language.currentState().stdio
                val privateFile = directory.resolve("$backend-$inlining")
                Files.writeString(privateFile, "keep")
                val path = ManagedAddress.fromByteArray(privateFile.toString().toByteArray() + byteArrayOf(0))
                for (name in entries) {
                    val linked = CoreModules.reachable(module(), name) + ("instrument" to true)
                    val program = program(language, linked, backend)
                    val function = context.asValue(EntryValue(program, name, 4))
                    val host = program.hostEntryTarget(4)
                    fun exercise(rows: List<Map<String, Any?>>, compiledRoots: Int?) {
                        for (row in rows) {
                            val scenario = row["scenario"] as String
                            val fd = if (scenario == "negative") -1L else files.open(path,
                                when (scenario) { "write" -> 2L; "readwrite" -> 3L; else -> 0L }).also { assertTrue(it >= 3L) }
                            if (scenario == "closed") assertEquals(0L, files.close(fd))
                            if (scenario == "eof") assertEquals(4L, files.seek(fd, 0L, 2L))
                            val initialPosition = if (scenario in listOf("closed", "negative")) null
                                else files.seek(fd, 0L, 1L).also { assertTrue(it >= 0L) }
                            assertEquals(-1L, stdio.close(-1L)); assertEquals(badFd, stdio.errno())
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(row["result"], function.execute(fd, row["writing"], row["milliseconds"], row["socket"]).asLong(),
                                "$backend/$inlining/$name/$row")
                            if (compiledRoots != null) assertEquals(before + compiledRoots,
                                (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "first installed execution $row")
                            assertEquals(badFd, stdio.errno(), "successful readiness preserves errno")
                            if (scenario !in listOf("closed", "negative")) {
                                assertEquals(initialPosition, files.seek(fd, 0L, 1L), "readiness must not move the file position")
                                assertEquals(0L, files.close(fd))
                            }
                        }
                    }
                    val rows = oracle.filter { it["entry"] == name }
                    exercise(rows, null)
                    val active = targets(host)
                    val guestRoots = active.count { it.rootNode is GuestRoot }
                    assertTrue(guestRoots in 1..2, "bounded scalar consumer roots: $guestRoots")
                    active.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                    assertTrue(function.invokeMember("compile").asBoolean())
                    active.forEach(::valid)
                    exercise(rows.asReversed(), guestRoots)
                    assertEquals(active, targets(host)); active.forEach(::valid)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals("keep", Files.readString(privateFile))
                    released(language)
                }
            } finally { context.leave() }
        }
    }

    @Test fun opaqueStreamsAndOutOfDomainWaitsFailExplicitlyWithoutExternalEffects() {
        val output = ByteArrayOutputStream()
        context(output = output).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val stdio = Language.currentState().stdio
                val unsupported = StdioHostAbi.load().error(7)
                for (fd in listOf(0L, 1L, 2L)) for (direction in listOf(0L, 1L)) {
                    assertEquals(-1L, stdio.ready(fd, direction, 0L, 0L))
                    assertEquals(unsupported, stdio.errno())
                }
                assertEquals(0L, stdio.ready(Int.MIN_VALUE.toLong(), 0L, 0L, 0L))
                assertEquals(1L, stdio.ready(Int.MAX_VALUE.toLong(), 1L, -1L, 1L))
                assertEquals(unsupported, stdio.errno())
                for (fd in listOf(Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1))
                    assertThrows(RuntimeFault::class.java) { stdio.ready(fd, 0L, 0L, 0L) }
                for (bad in listOf(-1L, 2L, 255L, 256L, Long.MAX_VALUE)) {
                    assertThrows(RuntimeFault::class.java) { stdio.ready(-1L, bad, 0L, 0L) }
                    assertThrows(RuntimeFault::class.java) { stdio.ready(-1L, 0L, 0L, bad) }
                }
                for (wait in listOf(-1L, 1L, Long.MAX_VALUE))
                    assertThrows(RuntimeFault::class.java) { stdio.ready(-1L, 0L, wait, 0L) }
                assertEquals(unsupported, stdio.errno()); assertEquals(0, output.size())
            } finally { context.leave() }
        }
        context().use { second ->
            second.initialize("thc"); second.enter()
            try { assertEquals(0L, Language.currentState().stdio.errno()) }
            finally { second.leave() }
        }
    }

    private fun replace(value: Any?, target: Any, replacement: Any): Any? = if (value === target) replacement else when (value) {
        is Map<*, *> -> value.mapValues { replace(it.value, target, replacement) }
        is List<*> -> value.map { replace(it, target, replacement) }
        else -> value
    }

    @Test fun originalReadinessRequiresExactHeadSafetyWidthsStateAndStoredCarriers() {
        for (name in entries) {
            val source = CoreModules.reachable(module(), name)
            val call = OriginalStdioChecks.foreignCalls(source).single()
            val metadata = call[6] as Map<String, Any?>
            val descriptor = metadata["foreignCall"] as Map<String, Any?>
            val target = descriptor["target"] as Map<String, Any?>
            val arguments = call[2] as List<List<Any?>>
            val invalid = mutableListOf<List<Any?>>()
            fun badDescriptor(change: Map<String, Any?>) {
                invalid += call.toMutableList().also { it[6] = metadata + ("foreignCall" to change) }
            }
            for ((key, value) in listOf("convention" to "capi", "safety" to "interruptible", "arity" to 4L,
                "suppliedArity" to 6L, "schema" to true)) badDescriptor(descriptor + (key to value))
            for ((key, value) in listOf("unit" to "main", "kind" to "dynamic", "isFunction" to false))
                badDescriptor(descriptor + ("target" to (target + (key to value))))
            val declared = descriptor["argumentReps"] as List<Map<String, Any?>>
            for (index in declared.indices) badDescriptor(descriptor + ("argumentReps" to declared.mapIndexed { i, rep ->
                if (i == index) rep + ("primReps" to listOf("IntRep")) else rep
            }))
            invalid += call.toMutableList().also { it[3] = listOf(true, false, false, false, false) }
            invalid += call.toMutableList().also { it[1] = listOf("lit", "int", "0", (call[1] as List<*>)[2]) }
            // A literal cannot become a zero-width State# merely by relabeling its occurrence.
            invalid += call.toMutableList().also {
                val stateMetadata = arguments.last().last() as Map<*, *>
                it[2] = arguments.dropLast(1) + listOf(listOf("lit", "int", "0", stateMetadata))
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (changed in invalid) assertThrows(RuntimeFault::class.java) {
                        program(language, replace(source, call, changed) as Map<String, Any?>, backend)
                    }
                    val operation = if (name.endsWith("Unsafe")) OriginalStdioOp.READY_UNSAFE else OriginalStdioOp.READY_SAFE
                    val valid = arguments.map(CoreRepresentations::expression)
                    valid.forEachIndexed { index, rep ->
                        assertDoesNotThrow { CoreOriginalStdio.validateScalarOperand(operation, index, rep, rep) }
                        assertThrows(RuntimeFault::class.java) { CoreOriginalStdio.validateScalarOperand(operation, index, rep,
                            rep.copy(kind = CoreKind.ADDRESS, primReps = listOf("AddrRep"))) }
                    }
                    released(language)
                } finally { context.leave() }
            }
        }
    }
}
