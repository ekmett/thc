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
import org.junit.jupiter.api.Timeout
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@Timeout(180)
class STMFullCoreTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("basic", "rollback", "alternative", "lazyPayload", "nestedAtomic", "unliftedPayload")
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc", "llvm")
        .allowNativeAccess(true).allowIO(IOAccess.ALL).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString())
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun fixture(): Map<String, Any?> {
        val manifest = Json.parse(File(root, "build/stm/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"])
        assertEquals(57, (manifest["nativeRows"] as Number).toInt())
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale STM fixture: $path")
            }
        for (stage in listOf("pre", "post")) {
            val selection = Json.parse(File(root, "build/stm/$stage/original-selection.json").readText()) as Map<String, String>
            assertTrue(selection.isNotEmpty())
            for ((path, origin) in selection) {
                val (archive, member) = origin.split("!/", limit = 2)
                ZipFile(File(root, archive)).use { zip ->
                    assertArrayEquals(zip.getInputStream(zip.getEntry(member)).use { it.readBytes() },
                        File(root, path).readBytes(), "Original installed module was rewritten: $path")
                }
            }
        }
        return manifest
    }
    private val targetLayout by lazy {
        checkNotNull(CorePackageManifest.visitModules(File(root, "build/stm/installed/packages.json").path)
            { _, _ -> }.targetLayout)
    }
    private fun module(paths: List<String>) = CoreModules.merge(paths.map {
        Json.parse(File(root, it).readText()) as Map<String, Any?>
    }) + ("targetLayout" to targetLayout)
    private fun model(name: String, x: Long) = when (name) {
        "basic" -> (18 * x + 3) * 31 + x + 3
        "rollback" -> (18 * x + 19) * 31 + 2 * x + 3
        "alternative" -> 32 * x + 7
        "lazyPayload" -> x + 42
        "nestedAtomic" -> x + 31
        "unliftedPayload" -> x + 9
        else -> error(name)
    }
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
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
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
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.arguments.retainedReferences())
        assertEquals(0, handoff.results.depth); assertEquals(0, handoff.results.retainedReferences())
        assertNull(handoff.pending)
        assertFalse(Language.currentState().stm.hasTransaction())
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)

    @Test fun originalCoreMatchesNativeWithInlining() = native(true, "ast")
    @Test fun originalCoreMatchesNativeAcrossResidualCalls() = native(false, "ast")
    @Test fun originalBytecodeCoreMatchesNativeWithInlining() = native(true, "bytecode")
    @Test fun originalBytecodeCoreMatchesNativeAcrossResidualCalls() = native(false, "bytecode")
    private fun native(inlining: Boolean, backend: String) {
        val manifest = fixture()
        val rows = File(root, "build/stm/oracle.tsv").readLines().map { it.split('\t') }
        assertEquals(57, rows.size)
        assertEquals(57, rows.map { it.take(2) }.toSet().size)
        val originals = rows.filter { it[0] in names }.groupBy { it[0] }
        assertEquals(names.toSet(), originals.keys)
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val module = module(paths)
            for (name in names) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, name, strictLink = true) + ("instrument" to true)
                    val program = program(language, linked, backend)
                    val entry = program.entryTarget(name)
                    val host = program.hostEntryTarget(1)
                    val function = context.asValue(EntryValue(program, name, 1))
                    val cases = originals.getValue(name).map { it[1].toLong() to it[2].toLong() }
                    assertEquals(listOf(-31L,-1L,0L,1L,17L,63L,4097L), cases.map { it.first })
                    val label = "$stage/$backend/$name/inlining=$inlining"
                    fun check(input: Long, expected: Long) {
                        assertEquals(model(name, input), expected, "Native model $label/$input")
                        assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                        released(language)
                    }
                    cases.forEach { (input, expected) -> check(input, expected) }
                    val active = activeTargets(host)
                    (active + entry).distinct().filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    // No settling call: every first installed entry is checked immediately.
                    for ((input, expected) in cases) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(input, expected)
                        // Original source has entry + runRW lambda + atomic action;
                        // catch/orElse cases additionally enter action and handler.
                        // Nested atomically rejects before entering its inner action.
                        val entered = if (name in listOf("rollback", "alternative", "nestedAtomic")) 5L else 3L
                        assertEquals(before + entered,
                            (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                            "$label exact original guest-root count on first installed call")
                        valid(entry, "$label original entry")
                        assertEquals(active, activeTargets(host), "$label active identities")
                        active.forEach { valid(it, "$label/$input active target ${it.rootNode.name}") }
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }

    private fun call(program: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(program.hostEntryTarget(args.size), arrayOf(program.entryValue(name), arrayOf(*args)))

    @Test fun genuineConcurrentUpdatesAndEitherReadSetWakeMatchNative() {
        val manifest = fixture()
        val native = File(root, "build/stm/oracle.tsv").readLines().map { it.split('\t') }
            .associate { (it[0] to it[1].toLong()) to it[2].toLong() }
        assertEquals(128L, native["concurrent" to 128L])
        assertEquals(119L, native["either" to 0L]); assertEquals(7L, native["either" to 1L])
        for ((_, paths) in manifest["stages"] as Map<String, List<String>>) for (backend in listOf("ast", "bytecode")) {
            val workers = Executors.newFixedThreadPool(2)
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val names = listOf("newCell", "readCell", "bumpCell", "awaitCell", "awaitEither")
                    val program = program(language, CoreModules.reachable(module(paths), names, strictLink = true), backend)
                    val stm = Language.currentState().stm
                    val cell = call(program, "newCell", 0L)
                    val start = CountDownLatch(1)
                    val jobs = (0..1).map {
                        workers.submit {
                            context.enter()
                            try {
                                assertTrue(start.await(5, TimeUnit.SECONDS))
                                repeat(64) { call(program, "bumpCell", cell, 1L) }
                                released(language)
                            } finally { context.leave() }
                        }
                    }
                    start.countDown()
                    jobs.forEach { it.get(15, TimeUnit.SECONDS) }
                    assertEquals(native["concurrent" to 128L], call(program, "readCell", cell))
                    for (side in 0..1) {
                        val a = call(program, "newCell", 0L)
                        val b = call(program, "newCell", 0L)
                        val waiting = workers.submit<Any?> {
                            context.enter()
                            try { call(program, "awaitEither", a, b).also { released(language) } }
                            finally { context.leave() }
                        }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (stm.pendingWaiters() != 1 && System.nanoTime() < deadline) Thread.yield()
                        assertEquals(1, stm.pendingWaiters(), "genuine Core did not register retry")
                        call(program, "bumpCell", if (side == 0) a else b, 7L)
                        assertEquals(native["either" to side.toLong()], waiting.get(5, TimeUnit.SECONDS))
                        assertEquals(0, stm.pendingWaiters())
                    }
                    released(language)
                } finally { context.leave(); workers.shutdownNow() }
            }
        }
    }

    @Test fun allEightPinnedContractsAndAsyncAdmission() {
        val manifest = fixture()
        val paths = (manifest["stages"] as Map<String, List<String>>).getValue("pre")
        val full = module(paths)
        fun nodes(value: Any?): List<List<Any?>> = when (value) {
            is Map<*, *> -> value.values.flatMap(::nodes)
            is List<*> -> listOf(value) + value.flatMap(::nodes)
            else -> emptyList()
        }
        val consumer = Json.parse(File(root, paths.first()).readText())
        val calls = nodes(consumer).filter { (it.firstOrNull() == "app") &&
            ((it.getOrNull(1) as? List<*>)?.getOrNull(1) as? String)?.let { name -> STMOp.named(name) } != null }
        assertEquals(STMOp.entries.map { it.primitive }.toSet(), calls.map { (it[1] as List<*>)[1] }.toSet())
        for (call in calls) {
            val op = STMOp.named((call[1] as List<*>)[1] as String)!!
            val args = (call[2] as List<List<Any?>>).map(CoreRepresentations::expression)
            val flags = call[3] as List<*>
            val result = CoreRepresentations.expression(call)
            op.validate(args, flags, result)
            assertThrows(RuntimeFault::class.java) { op.validate(args.dropLast(1), flags, result) }
            assertThrows(RuntimeFault::class.java) { op.validate(args, flags.map { !(it as Boolean) }, result) }
            assertThrows(RuntimeFault::class.java) { op.validate(args, flags, CoreRepresentation(CoreKind.LONG)) }
        }
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(full, "basic")
                assertNotNull(BytecodeProgram(language, linked, true).entryTarget("basic"))
                assertNotNull(Program(language, linked, true).entryTarget("basic"))
                assertThrows(UnsupportedCore::class.java) {
                    BytecodeProgram(language, linked, BytecodeCheckpoint()).entryTarget("basic")
                }
            } finally { context.leave() }
        }
    }

    /** Every call uses the public parser and the same original entry/CAF set. */
    @Test fun publicAsyncRetryAbortsAndSharedThunksRestartLikeGhc() {
        val manifest = fixture()
        val native = File(root, "build/stm/oracle.tsv").readLines().map { it.split('\t') }
            .associate { it[0] to it[2].toLong() }
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                val request = Json.parse(CoreModules.request(paths.map { File(root, it).path },
                    "asyncEntry", backend = backend)) as Map<String, Any?>
                val entry = context.eval("thc", Json.stringify(request + mapOf("asyncExceptions" to true,
                    "strictLink" to true, "targetLayout" to targetLayout.document())))
                fun call(operation: Long, token: Long = 0) = entry.execute(operation, token).asLong()
                fun compiledEntries(): Long = ((Json.parse(entry.getMember("diagnostics").asString())
                    as Map<String, Any?>).getValue("compiledEntries") as Number).toLong()
                context.enter()
                val state: Language.State
                val language: Language
                try {
                    state = Language.currentState()
                    language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                } finally { context.leave() }
                assertEquals(0L, call(5))
                assertEquals(0L, call(6))
                assertTrue(entry.invokeMember("compile").asBoolean())
                // No settling call between compilation and this real observation.
                val firstCompiled = compiledEntries()
                assertEquals(0L, call(5))
                assertTrue(compiledEntries() > firstCompiled, "The first installed call must enter compiled code")
                val workers = mutableListOf<Thread>()
                fun start(operation: Long): CompletableFuture<Long> {
                    val result = CompletableFuture<Long>()
                    Thread({
                        try {
                            val answer = call(operation)
                            assertFalse(state.stm.hasTransaction(), "Transaction log leaked on its owner carrier")
                            assertEquals(MaskingState.UNMASKED, state.maskingState.get(), "Mask leaked after IO catch")
                            result.complete(answer)
                        }
                        catch (failure: Throwable) { result.completeExceptionally(failure) }
                    }, "stm-$backend-$operation").apply {
                        isDaemon = true; workers.add(this); start()
                    }
                    return result
                }
                try {
                    for ((name, operation) in listOf("retry" to 0L, "inner" to 1L)) {
                        val answer = start(operation)
                        val target = workers.last()
                        assertEquals(if (name == "retry") 0L else 4L,
                            CompletableFuture.supplyAsync { call(2) }.get(10, TimeUnit.SECONDS))
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (!answer.isDone && System.nanoTime() < deadline &&
                            !(target.state == Thread.State.WAITING && target.stackTrace.any { it.methodName == "await" }))
                            Thread.sleep(1)
                        assertFalse(answer.isDone, "$stage/$backend/$name must still be running")
                        assertEquals(Thread.State.WAITING, target.state)
                        if (name == "retry") assertEquals(1, state.stm.pendingWaiters())
                        val sender = start(7)
                        assertEquals(native.getValue("$name-caught"), answer.get(15, TimeUnit.SECONDS))
                        assertEquals(0L, sender.get(15, TimeUnit.SECONDS), "killThread# returned after acknowledgement")
                        assertEquals(0, state.stm.pendingWaiters(), "Abandoned retry registrations must be cancelled")
                        assertEquals(native.getValue("$name-aborted"), call(5), "Tentative writes escaped")
                        assertEquals(native.getValue("$name-prefix"), call(6))
                        if (name == "retry") {
                            val interruptedAgain = start(operation)
                            assertEquals(0L, CompletableFuture.supplyAsync { call(2) }.get(10, TimeUnit.SECONDS))
                            assertEquals(0L, start(7).get(15, TimeUnit.SECONDS))
                            assertEquals(-1L, interruptedAgain.get(15, TimeUnit.SECONDS))
                            assertEquals(0, state.stm.pendingWaiters())
                            assertEquals(0L, call(5))
                            assertEquals(1L, call(6), "Second interruption replayed enclosing prefix")
                        }
                        assertEquals(if (name == "retry") 4L else 9L, call(4, if (name == "retry") 4 else 9))
                        if (name == "inner") assertEquals(0L, call(3))
                        // The forcing carrier differs from the abandoned owner.
                        assertEquals(native.getValue("$name-resumed"), call(operation))
                        assertEquals(native.getValue("$name-committed"), call(5))
                        assertEquals(native.getValue("$name-prefix-after"), call(6), "Enclosing prefix replayed")
                        if (name == "retry") assertEquals(4L, call(2))
                        context.enter()
                        try { released(language) } finally { context.leave() }
                    }
                } finally {
                    workers.forEach { it.join(1000) }
                    if (workers.any { it.isAlive }) context.close(true)
                }
            }
        }
    }

    @Test fun publicSynchronousAndAsynchronousTransactionsMatchNative() {
        val manifest = fixture()
        for ((_, paths) in manifest["stages"] as Map<String, List<String>>) {
            for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) context().use { context ->
                val request = Json.parse(CoreModules.request(paths.map { File(root, it).path },
                    "basic", backend = backend)) as Map<String, Any?>
                val entry = context.eval("thc", Json.stringify(request + mapOf("asyncExceptions" to async,
                    "strictLink" to true, "targetLayout" to targetLayout.document())))
                assertEquals(model("basic", 17), entry.execute(17).asLong())
                assertTrue(entry.invokeMember("compile").asBoolean())
                val before = ((Json.parse(entry.getMember("diagnostics").asString()) as Map<String, Any?>)
                    .getValue("compiledEntries") as Number).toLong()
                assertEquals(model("basic", 63), entry.execute(63).asLong())
                val after = ((Json.parse(entry.getMember("diagnostics").asString()) as Map<String, Any?>)
                    .getValue("compiledEntries") as Number).toLong()
                assertTrue(after > before)
            }
        }
    }
}
