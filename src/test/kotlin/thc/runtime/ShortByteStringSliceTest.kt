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
import org.junit.jupiter.api.Tag
import thc.*
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class ShortByteStringSliceTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/short-bytes-slices")
    private val entries = listOf("takeCase", "dropCase", "splitCase")
    private val lengthWorker = "ghc-internal:GHC.Internal.List.\$wlenAcc"
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String, row: Row? = null, host: RootCallTarget? = null) {
        val installed = target.javaClass.getMethod("isValidLastTier").invoke(target)
        val detail = if (installed == true) label else
            "$label row=$row target=${target.javaClass.name}@${System.identityHashCode(target).toString(16)} " +
                "root=${target.rootNode.javaClass.name}:${target.rootNode.name} host=${target === host}"
        assertEquals(true, installed, detail)
    }
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial installation")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry); return targets
    }
    private data class BoundaryProbe(val method: Any, val hasCompiledCode: Method)
    private data class SnapshotProbes(val validity: Method, val callCount: Result<Method>, val boundary: Result<BoundaryProbe>)
    private fun snapshotProbes(entry: RootCallTarget) = SnapshotProbes(
        entry.javaClass.getMethod("isValidLastTier"),
        runCatching { entry.javaClass.getMethod("getCallCount") },
        runCatching {
            val jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null)
            val backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime")
                .getMethod("getHostJVMCIBackend").invoke(jvmci)
            val metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend")
                .getMethod("getMetaAccess").invoke(backend)
            val method = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                .getDeclaredMethod("callBoundary", Array<Any?>::class.java)
            val boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
                .getMethod("lookupJavaMethod", java.lang.reflect.Executable::class.java).invoke(metaAccess, method)
            BoundaryProbe(boundary, Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod")
                .getMethod("hasCompiledCode"))
        })
    private data class TargetState(val target: RootCallTarget, val valid: Result<Boolean>, val calls: Result<Int>)
    private data class CallSnapshot(val targets: List<TargetState>, val boundary: Result<Boolean>)
    private fun snapshot(targets: List<RootCallTarget>, probes: SnapshotProbes) = CallSnapshot(
        targets.map { target -> TargetState(target,
            runCatching { probes.validity.invoke(target) as Boolean },
            probes.callCount.mapCatching { it.invoke(target) as Int }) },
        probes.boundary.mapCatching { it.hasCompiledCode.invoke(it.method) as Boolean })
    private fun unavailable(error: Throwable) = "unavailable(${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)})"
    private fun describe(snapshot: CallSnapshot, host: RootCallTarget, original: RootCallTarget, worker: RootCallTarget) =
        snapshot.targets.mapIndexed { index, state ->
            val target = state.target
            "$index:${target.javaClass.simpleName}@${System.identityHashCode(target).toString(16)}" +
                " root=${target.rootNode.javaClass.simpleName}:${target.rootNode.name.take(120)}" +
                " host=${target === host} original=${target === original} worker=${target === worker}" +
                " valid=${state.valid.fold({ it.toString() }, ::unavailable)}" +
                " calls=${state.calls.fold({ it.toString() }, ::unavailable)}"
        }.joinToString(prefix = "[", postfix = "]") +
            " boundaryHasCompiledCode=${snapshot.boundary.fold({ it.toString() }, ::unavailable)}"
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    private data class Row(val name: String, val seed: Long, val count: Long,
                           val side: Long, val selector: Long, val expected: Long)
    private fun payload(seed: Long): List<Long> {
        val length = kotlin.math.abs(seed % 17).toInt()
        return List(length) { (seed + it * 73L) and 255L }
    }
    private fun value(name: String, seed: Long, count: Long, side: Long): List<Long> {
        val data = payload(seed)
        val cut = count.coerceIn(0, data.size.toLong()).toInt()
        return if (name == "takeCase" || name == "splitCase" && side == 0L) data.take(cut) else data.drop(cut)
    }
    private fun observe(data: List<Long>, selector: Long): Long = when (selector) {
        -2L -> data.fold(0L) { a,b -> a*33+b }
        -1L -> data.size.toLong()
        else -> if (selector >= 0 && selector < data.size) data[selector.toInt()] else -1L
    }
    private fun manifest(): Map<String, Any?> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("sources", "artifacts")) for (item in manifest[kind] as List<Map<String, String>>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, item.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(item["sha256"], hash, "Stale byte-slice preparation: ${item["path"]}")
        }
        assertEquals(entries, manifest["entries"])
        return manifest
    }
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map {
        Json.parse(File(root, it).readText()) as Map<String, Any?>
    })
    @Test fun publicSlicesWithInlining() = native(true)
    @Test fun publicSlicesAcrossResidualCalls() = native(false)
    @Test @Tag("jit-stability")
    fun publicSlicesRetainCodeWithInlining() = native(true, stability = true)
    @Test @Tag("jit-stability")
    fun publicSlicesRetainCodeAcrossResidualCalls() = native(false, stability = true)
    private fun native(inlining: Boolean, stability: Boolean = false) {
        val manifest = manifest()
        val rows = File(directory, "oracle.tsv").readLines().map { line ->
            val p = line.split('\t')
            Row(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toLong())
        }
        val seeds = (manifest["seeds"] as List<Number>).map { it.toLong() }
        assertEquals(265, seeds.size)
        assertTrue(seeds.containsAll(listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 255L)))
        val expected = buildList {
            for (name in entries) for (seed in seeds) {
                val n = payload(seed).size.toLong()
                for (count in sortedSetOf(Long.MIN_VALUE, -1L, 0L, 1L, n/2, n-1, n, n+1, Long.MAX_VALUE)) {
                    for (side in if (name == "splitCase") listOf(0L, 1L) else listOf(0L)) {
                        val data = value(name, seed, count, side)
                        for (selector in listOf(Long.MIN_VALUE, -2L, -1L) + (0..data.size).map { it.toLong() } + Long.MAX_VALUE)
                            add(Row(name, seed, count, side, selector, observe(data, selector)))
                    }
                }
            }
        }
        assertEquals(81312, rows.size)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.size)
        assertEquals(expected, rows, "Native oracle includes every result byte, length, checksum and boundary sentinel")
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in entries) {
                val audit = Json.parse(File(directory, "$stage-$name.audit.json").readText()) as Map<String, Any?>
                assertEquals(true, audit["accepted"])
                assertTrue((audit["reachableBindings"] as List<Map<String, Any?>>).any { it["id"] == lengthWorker })
                val selected = rows.filter { it.name == name }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val instrumented = CoreModules.reachable(module, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, instrumented) else BytecodeProgram(language, instrumented)
                        val function = context.asValue(EntryValue(program, name, 4))
                        val host = program.hostEntryTarget(4)
                        val original = program.entryTarget(name)
                        val worker = program.entryTarget(lengthWorker)
                        val label = "$stage/$backend/$name/inlining=$inlining"
                        fun check(row: Row) {
                            assertEquals(row.expected, function.execute(row.seed, row.count, row.side, row.selector).asLong(),
                                "$label/${row.seed}/${row.count}/${row.side}/${row.selector}")
                            released(language)
                        }
                        // Warm the complete native corpus once; no settling calls,
                        // retries, automatic threshold changes or compiler budget overrides.
                        selected.forEach(::check)
                        if (stability) {
                            val targets = activeTargets(host)
                            assertTrue(targets.size > 1, "$label adopted guest call path")
                            targets.filter { it !== host }.forEach(::compile)
                            compile(original); compile(worker)
                            assertTrue(function.invokeMember("compile").asBoolean())
                            val state = language.handoffState.get()
                            val resultAllocations = state.results.allocations
                            val probes = snapshotProbes(host)
                            val observed = targets + listOf(original, worker).filter { candidate -> targets.none { it === candidate } }
                            for (row in selected.asReversed()) {
                                // Read-only snapshots add no guest invocation, repair, or compilation.
                                // Failed optional observations cannot replace the unchanged assertions.
                                val beforeTargets = snapshot(observed, probes)
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                check(row)
                                val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                val afterTargets = snapshot(observed, probes)
                                try {
                                    assertTrue(after > before, "$label: actual compiled guest entry")
                                    assertEquals(targets, activeTargets(host), "$label active target identity")
                                    valid(original, "$label original", row, host); valid(worker, "$label List worker", row, host)
                                    targets.forEachIndexed { index, target -> valid(target, "$label active[$index]", row, host) }
                                } catch (error: AssertionError) {
                                    System.err.println("ShortByteStringSlice FIRST FAILURE $label row=$row " +
                                        "context@${System.identityHashCode(context).toString(16)} " +
                                        "handoff=${System.getProperty("thc.handoffSlabs", "false")} countBefore=$before countAfter=$after " +
                                        "before={${describe(beforeTargets, host, original, worker)}} " +
                                        "after={${describe(afterTargets, host, original, worker)}}")
                                    throw error
                                }
                            }
                            assertEquals(resultAllocations, state.results.allocations, "$label result slabs reused")
                        } else {
                            selected.asReversed().forEach(::check)
                        }
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        println("ShortByteStringSlice PASS $label rows=${selected.size} stability=$stability")
                    } finally { context.leave() }
                }
            }
        }
    }
    @Test fun completeAppendAndConcatOverflowPathsRemainStrictFrontiers() {
        val manifest = manifest()
        val unit = manifest["installedBytestringUnitId"] as String
        assertTrue(unit.matches(Regex("bytestring-0\\.12\\.2\\.0(?:-[A-Za-z0-9]+)?")))
        val stages = manifest["stages"] as Map<String, List<String>>
        for ((stage, paths) in stages) for (name in listOf("appendFrontier", "concatFrontier")) {
            val audit = Json.parse(File(directory, "$stage-$name.audit.json").readText()) as Map<String, Any?>
            assertEquals(false, audit["accepted"])
            assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(listOf("$unit:Data.ByteString.Internal.Type.overflowError"),
                (audit["missingGlobals"] as List<Map<String, Any?>>).map { it["id"] })
            for (backend in listOf("ast", "bytecode")) context(true).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(merged(paths), name)
                    assertThrows(UnsupportedCore::class.java) {
                        if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    }
                    released(language)
                } finally { context.leave() }
            }
        }
    }
}
