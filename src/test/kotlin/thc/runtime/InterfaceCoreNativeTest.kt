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
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class InterfaceCoreNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/interface-core")
    private val entries = listOf("opaqueEntry", "inlineEntry", "recursiveEntry", "coercionEntry", "wrapperEntry")
    private fun source(entry: String = "opaqueEntry"): Map<String, Any?> {
        val modules = StringBuilder("[")
        val layout = CorePackageManifest.appendModules(modules, File(directory, "packages.json").absolutePath)
        assertNotNull(layout, "selected installed Core carries the target-derived layout")
        assertEquals("fixture", layout!!.compilerAbi)
        modules.append(']')
        val name = if (entry == "coercionEntry") "CBVCoercionAudit" else "InterfaceLibrary"
        return (Json.parse(modules.toString()) as List<Map<String, Any?>>).single { it["module"] == name }
    }

    private fun oracle(): List<List<Long>> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(entries, manifest["entries"])
        assertEquals("thc-interface-fixture-0.1", manifest["unit"])
        val controls = Json.parse(File(directory, "driver-controls.json").readText()) as Map<String, Any?>
        assertEquals(false, controls["installedArtifactsHashed"])
        for (name in listOf("sourceDeleted", "unchangedReuse", "thinMissing", "identityFailure",
            "wrongWayFailure", "foreignArtifactsArchived", "failedRefreshPreservedBundle")) assertEquals(true, controls[name], name)
        assertEquals(listOf("opaque-body", "private-worker", "recursive-groups", "thin-unavailable",
            "no-source-target", "wrong-module", "wrong-unit", "wrong-way", "foreign-archived",
            "private-flags", "repeat-load", "helper-protocol", "installed-cbv-worker", "installed-wired-unit",
            "foreign-association-absence", "foreign-linked-clock", "typed-foreign-export-associations",
            "retained-export-registration", "managed-export-original"), manifest["controls"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "$kind/$path")
            }
        val rows = File(directory, "logs/native-oracle.stdout").readLines().map { line ->
            line.split(' ').map(String::toLong)
        }
        assertEquals((-10L..10L).toList(), rows.map { it[0] })
        rows.forEach { row -> assertEquals(listOf(row[0], row[0] * 7 + 11, row[0] + 3,
            maxOf(0L, row[0]) * 7 + 11, row[0] + 7, row[0]), row) }
        return rows
    }

    @Test fun realForeignArtifactsSurviveCheckedArchiveButCannotExecute() {
        oracle()
        val direct = Json.parse(File(directory, "InterfaceForeign.json").readText()) as Map<String, Any?>
        val modules = StringBuilder("[")
        val layout = CorePackageManifest.appendModules(modules, File(directory, "foreign-packages.json").absolutePath)
        assertNotNull(layout, "archiving foreign code preserves the selected target layout")
        assertEquals("fixture", layout!!.compilerAbi)
        modules.append(']')
        val archived = (Json.parse(modules.toString()) as List<Map<String, Any?>>).single()
        assertEquals(2L, archived["schema"])
        assertEquals(direct["foreign"], archived["foreign"])
        val artifacts = archived["foreign"] as Map<String, Any?>
        assertEquals("not-linked", artifacts["execution"])
        val stubs = artifacts["stubs"] as Map<String, Any?>
        assertTrue((stubs["header"] as String).contains("thc_interface_fixture"))
        assertTrue((stubs["source"] as String).contains("rts_lock"))
        assertTrue((stubs["source"] as String).contains("registerForeignExports"))
        assertEquals(emptyList<Any?>(), stubs["finalizers"])
        val initializer = (stubs["initializers"] as List<Map<String, Any?>>).single()
        assertEquals(mapOf("isInitializer" to true, "unit" to "thc-interface-fixture-0.1",
            "module" to "InterfaceForeign", "name" to "fexports"), initializer)
        val file = (artifacts["files"] as List<Map<String, Any?>>).single()
        assertEquals("int thc_interface_c_control(void) { return 29; }\n", file["source"])
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            Context.newBuilder("thc").allowExperimentalOptions(true).build().use { context ->
                val error = assertThrows(org.graalvm.polyglot.PolyglotException::class.java) {
                    context.eval("thc", Json.stringify(mapOf("entry" to "exported", "backend" to backend,
                        "diagnosticUnsupported" to diagnostic, "modules" to listOf(archived))))
                }
                assertTrue(error.message!!.contains("Unsupported foreign code/registration"), error.message)
                assertTrue(error.message!!.contains("thc-interface-fixture-0.1:InterfaceForeign"))
            }
        }
    }

    @Test fun originalCapiBitcodeCallsThroughManagedOffsetWithoutExposingHostPointers() {
        oracle()
        val archived = Json.parse(File(directory, "clock-capi.json").readText()) as Map<String, Any?>
        val link = CoreForeignArtifacts.linked(archived)!!
        val symbols = link.symbols
        assertEquals(setOf("fixture_clock_id", "fixture_clock_time", "fixture_clock_resolution"), symbols)
        Context.newBuilder("thc").allowNativeAccess(true).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val cbits = Language.currentState().cbits()
                cbits.link(link)
                val clock = cbits.capiZero(link.unit, "fixture_clock_id")
                val bytes = ByteArray(32) { 0x5a }
                val address = ManagedAddress.fromByteArray(bytes).plus(5)
                assertEquals(0L, cbits.capiWordAddress(link.unit, "fixture_clock_time", clock, address).value)
                val view = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
                assertTrue(view.getLong(5) >= 0)
                assertTrue(view.getLong(13) in 0 until 1_000_000_000L)
                assertEquals(0x5a.toByte(), bytes[4])
                assertEquals(0x5a.toByte(), bytes[21])
                assertEquals(0L, cbits.capiWordAddress(link.unit, "fixture_clock_resolution", clock, address).value)
                assertTrue(view.getLong(13) in 0 until 1_000_000_000L)
            } finally { context.leave() }
        }
    }

    @Test fun linkedCapiStateAndAddressCallsRetainTypedCompiledAstAndBytecodeEntries() {
        oracle()
        val archived = Json.parse(File(directory, "clock-capi.json").readText()) as Map<String, Any?>
        val link = CoreForeignArtifacts.linked(archived)!!
        fun scalar(rep: String?, evaluated: Boolean = true) = mapOf("kind" to when (rep) {
            null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
        }, "primReps" to (rep?.let { listOf(it) } ?: emptyList<String>()), "evaluated" to evaluated)
        fun tuple(evaluated: Boolean = true) = mapOf("kind" to "unknown", "primReps" to listOf("Int32Rep"),
            "aggregate" to "unboxed-tuple", "components" to listOf(scalar(null), scalar("Int32Rep")),
            "evaluated" to evaluated)
        val reps = listOf("Word64Rep", "AddrRep", null)
        val formals = reps.mapIndexed { index, rep -> mapOf("id" to "p$index", "lifted" to false,
            "rep" to scalar(rep)) }
        val symbol = link.symbols.single { it.endsWith("fixture_clock_time") }
        val descriptor = mapOf("schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to symbol,
            "unit" to link.unit, "isFunction" to true), "convention" to "capi", "safety" to "unsafe",
            "arity" to 3L, "suppliedArity" to 3L, "argumentReps" to reps.map { scalar(it, false) },
            "resultRep" to tuple(false))
        val call = listOf("app", listOf("var", "foreign-clock-time", mapOf("rep" to scalar("BoxedRep (Just Lifted)"))),
            reps.indices.map { index -> listOf("var", "p$index", mapOf("rep" to scalar(reps[index]))) },
            listOf(false, false, false), false, false, mapOf("rep" to tuple(), "foreignCall" to descriptor))
        val binders = listOf(
            mapOf("id" to "s", "lifted" to false, "rep" to scalar(null)),
            mapOf("id" to "value", "lifted" to false, "rep" to scalar("Int32Rep")))
        val alternative = listOf("data", "T2", listOf("s", "value"),
            listOf("var", "value", mapOf("rep" to scalar("Int32Rep"))),
            mapOf("binders" to binders))
        val body = listOf("case", call, "pair", listOf(alternative),
            mapOf("rep" to scalar("Int32Rep"),
                "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to tuple())))
        val module = mapOf("instrument" to true, "foreignLinks" to listOf(link),
            "constructors" to listOf(mapOf("id" to "T2", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)),
            "bindings" to listOf(mapOf("id" to "clock", "name" to "clock", "arity" to 3, "lifted" to true,
                "rep" to scalar("BoxedRep (Just Lifted)"), "expr" to listOf("lam", formals, body,
                    mapOf("rep" to scalar("BoxedRep (Just Lifted)"), "resultRep" to scalar("Int32Rep"))))))
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowNativeAccess(true)
            .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    Language.currentState().cbits().link(link)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module)
                        else BytecodeProgram(language, module)
                    val target = program.entryTarget("clock")
                    val cbits = Language.currentState().cbits()
                    val clock = cbits.capiZero(link.unit, "fixture_clock_id")
                    fun check(compiled: Boolean) {
                        val bytes = ByteArray(32) { 0x5a }
                        val address = ManagedAddress.fromByteArray(bytes).plus(5)
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(0L, Calls.target(target, arrayOf<Any?>(0L, clock, address, Unit)))
                        if (compiled) {
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                            valid(target)
                        }
                        val view = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
                        assertTrue(view.getLong(5) >= 0)
                        assertTrue(view.getLong(13) in 0 until 1_000_000_000L)
                        assertEquals(0x5a.toByte(), bytes[4]); assertEquals(0x5a.toByte(), bytes[21])
                    }
                    check(false)
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target)
                    check(true)
                    val unchanged = ByteArray(32) { 0x5a }
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(target, arrayOf<Any?>(0L, clock, ManagedAddress.fromByteArray(unchanged).plus(20), Unit))
                    }
                    assertArrayEquals(ByteArray(32) { 0x5a }, unchanged)
                } finally { context.leave() }
            }
    }

    @Test fun helperPreservesGenuineInstalledWorkerCbvMarks() {
        oracle()
        val direct = Json.parse(File(directory, "direct/CBVCoercionAudit.json").readText()) as Map<String, Any?>
        val loaded = source("coercionEntry")
        fun worker(module: Map<String, Any?>) = (module["bindings"] as List<Map<String, Any?>>)
            .single { it["name"] == "\$wwitnessed" }
        val original = worker(direct)
        val hydrated = worker(loaded)
        assertEquals(listOf(false, false, true), original["entryStrict"])
        assertEquals(original["entryStrict"], hydrated["entryStrict"])
        assertEquals("ghc-id", original["entryStrictSource"])
        assertEquals("ghc-id", hydrated["entryStrictSource"])
        assertEquals((original["info"] as Map<*, *>)["cbvMarks"], (hydrated["info"] as Map<*, *>)["cbvMarks"])
        val parameters = (hydrated["expr"] as List<*>)[1] as List<Map<String, Any?>>
        assertEquals(true, parameters[0]["coercion"])
        assertEquals(emptyList<String>(), CoreRepresentations.binder(parameters[0]).primReps)
        assertEquals(true, parameters[2]["lifted"])
        val missing = Json.parse(File(directory, "logs/helper-thin.stdout").readText()) as Map<String, Any?>
        assertEquals("unavailable", missing["status"])
        assertEquals("complete-interface-core", missing["capability"])
        assertFalse(missing.containsKey("core"))
        val command = Json.parse(File(directory, "logs/helper-thin.command.json").readText()) as Map<String, Any?>
        assertEquals(3L, (command["exit"] as Number).toLong())
        val wired = Json.parse(File(directory, "wired-unit.json").readText()) as Map<String, Any?>
        val wiredResponse = Json.parse(File(directory, "logs/helper-wired-unit.stdout").readText()) as Map<String, Any?>
        val wiredCommand = Json.parse(File(directory, "logs/helper-wired-unit.command.json").readText()) as Map<String, Any?>
        assertEquals("ghc-internal", wired["interfaceUnit"])
        assertNotEquals(wired["registeredUnit"], wired["interfaceUnit"])
        assertEquals(wired["expectedExit"], wiredCommand["exit"])
        if (wired["completeCore"] == true) {
            assertEquals("loaded", wiredResponse["status"])
            assertEquals(wired["interfaceUnit"], (wiredResponse["core"] as Map<*, *>)["unit"])
        } else {
            assertEquals("unavailable", wiredResponse["status"])
            assertEquals("complete-interface-core", wiredResponse["capability"])
            assertEquals(wired["registeredUnit"], wiredResponse["unit"])
            assertFalse(wiredResponse.containsKey("core"))
        }
    }

    @Test fun completeInterfacePreservesMetadataAndPassesStrictAdmission() {
        oracle()
        val source = source()
        assertEquals("optimized-Core-after-Tidy-before-CorePrep", source["boundary"])
        assertEquals("thc-interface-fixture-0.1", source["unit"])
        assertFalse(File(directory, "source/InterfaceLibrary.hs").exists())
        val bindings = source["bindings"] as List<Map<String, Any?>>
        val worker = bindings.single { it["name"] == "privateWorker" }
        assertTrue((worker["id"] as String).startsWith("thc-interface-fixture-0.1:InterfaceLibrary.privateWorker_"))
        assertTrue((source["groups"] as List<Map<String, Any?>>).any { it["recursive"] == true })
        assertTrue((source["constructors"] as List<Map<String, Any?>>).any {
            (it["id"] as String).endsWith("InterfaceLibrary.Token") })
        val files = source["sourceFiles"] as List<Map<String, Any?>>
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it["content"] == null }, "Missing source text must not be fabricated")
        assertTrue((source["sourceSpans"] as List<*>).isNotEmpty())
        for (entry in entries) {
            val audit = Json.parse(File(directory, "$entry-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], entry)
            assertEquals(emptyList<Any>(), audit["issues"], entry)
            assertEquals(emptyList<Any>(), audit["missingGlobals"], entry)
        }
    }

    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val found = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val roots = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(node)
            roots.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }
                .filter { it.rootNode is GuestRoot }.forEach(::visit)
            found += target
        }
        visit(entry)
        return found
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)

    @Test fun recoveredBodiesMatchNativeInFirstInstalledAstAndBytecodeCode() {
        val rows = oracle()
        for (backend in listOf("ast", "bytecode")) for ((index, entry) in entries.withIndex())
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false")
                .option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(source(entry), entry, strictLink = true) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                            else BytecodeProgram(language, linked)
                        val target = program.entryTarget(entry)
                        // Save the target before a CAF update clears it. A
                        // memoized constant is not a per-invocation function.
                        val constants = (linked["bindings"] as List<Map<String, Any?>>).mapNotNull { binding ->
                            (program.entryValue(binding["id"] as String) as? Thunk)?.let {
                                it to checkNotNull(it.target)
                            }
                        }
                        fun check(row: List<Long>) {
                            assertEquals(row[index + 1], Calls.target(target, arrayOf(0L, row[0])), "$backend/$entry/${row[0]}")
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                        repeat(3) { rows.forEach(::check) }
                        val active = targets(target)
                        val memoized = constants.filter { it.first.state == 2 }
                        val perCall = active.filterNot { candidate -> memoized.any { it.second === candidate } }
                        val thunkCounts = program.diagnostics().getValue("thunkEvaluationsByLabel")
                        if (entry == "coercionEntry") {
                            assertEquals(1, memoized.size, "The original lifted Spine CAF was evaluated once")
                            assertNull(memoized.single().first.target)
                            assertTrue(active.any { it === memoized.single().second })
                            assertEquals(2, perCall.size)
                            assertEquals(setOf("coercionEntry", "\$wwitnessed"), perCall.map {
                                (it.rootNode as GuestRoot).coreIdentity?.occurrence
                            }.toSet())
                        } else assertEquals(active, perCall)
                        if (entry == "opaqueEntry") assertTrue(active.size >= 2, "Keep the opaque private call")
                        if (entry == "wrapperEntry") assertTrue(active.any {
                            (it.rootNode as GuestRoot).coreIdentity?.occurrence == "\$WToken"
                        }, "Compile and enter the recovered original constructor wrapper")
                        active.forEach {
                            it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                            valid(it)
                        }
                        for (row in rows.asReversed() + rows) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            val entered = (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before
                            assertTrue(entered >= perCall.size,
                                "$backend/$entry first installed invocation entered $entered of ${perCall.map { it.rootNode.name }}")
                            if (entry == "coercionEntry") {
                                assertEquals(2L, entered, "Entry and real CBV worker must both execute compiled")
                                assertEquals(thunkCounts, program.diagnostics().getValue("thunkEvaluationsByLabel"),
                                    "The memoized Spine CAF must not execute again")
                            }
                            active.forEach(::valid)
                        }
                        assertEquals("reject-at-load", program.diagnostics()["unsupportedPolicy"])
                        assertEquals(0L, (program.diagnostics()["unsupportedTraps"] as Number).toLong())
                        assertEquals(0L, (program.diagnostics()["blackholes"] as Number).toLong())
                    } finally { context.leave() }
                }
    }
}
