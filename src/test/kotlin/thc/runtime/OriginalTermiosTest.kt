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
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalTermiosTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-termios"
    private val names = listOf("originalTermiosSize", "originalEcho", "originalIcanon", "originalVmin", "originalVtime",
        "originalTcsanow", "originalSigsetSize", "originalSigttou", "originalSigBlock", "originalSigSetmask",
        "originalLflag", "originalPokeLflag", "originalCC")
    private val constantCount = 10
    private val symbols = listOf("__hscore_sizeof_termios", "__hscore_echo", "__hscore_icanon", "__hscore_vmin",
        "__hscore_vtime", "__hscore_tcsanow", "__hscore_sizeof_sigset_t", "__hscore_sigttou", "__hscore_sig_block",
        "__hscore_sig_setmask", "__hscore_lflag", "__hscore_poke_lflag", "__hscore_ptr_c_cc")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalTermiosAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") })
    private fun copy(value: Any?): Any? = Json.parse(Json.stringify(value))
    private fun context() = Context.newBuilder("thc").allowIO(IOAccess.NONE).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun calls() = OriginalStdioChecks.foreignCalls(module("pre")).distinctBy {
        ((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"]
    }

    @Test fun genuineNativeImagesAndPointersMatchBothBackendsAtFirstInstalledEntry() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(1L, manifest["schema"]); assertEquals("linux", manifest["platform"])
        assertEquals(true, manifest["supported"]); assertEquals(names, manifest["entries"])
        assertEquals(true, manifest["strictAccepted"]); assertEquals(false, manifest["runtimeVerified"])
        assertEquals(false, manifest["installedArtifactsHashed"]); assertEquals(6L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalTermiosAudit.hs",
            "compiler/test-fixtures/OriginalTermiosNative.hs", "test/haskell-fixtures/OriginalTermiosFixtures.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
            "scripts/audit-core.py", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/native/oracle") +
            listOf("pre", "post").flatMap { stage -> names.map { "$prefix/$stage/$it.audit.json" } +
                listOf("$prefix/$stage/core/OriginalTermiosAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val constants = oracle["constants"] as List<Long>
        val rows = oracle["rows"] as List<List<Any?>>
        val probe = TermiosImage::class.java.getResourceAsStream("/thc/native/termios-abi.json")!!.use {
            Json.parse(it.reader().readText()) as Map<*, *>
        }["termios"] as Map<*, *>
        assertEquals(listOf("size", "echo", "icanon", "vmin", "vtime", "tcsanow",
            "sigsetSize", "sigttou", "sigBlock", "sigSetmask").map { probe[it] }, constants)
        assertEquals(listOf(0L, 1L, 255L, 0x80000000L, 0xffffffffL, 0xdeadbeefL), rows.map { it[0] })
        for (row in rows) {
            assertEquals(0L, row[1]); assertEquals(row[0], row[2]); assertEquals(probe["ccOffset"], row[3])
            val image = row[4] as List<Long>
            assertEquals(constants[0].toInt() + 16, image.size)
            assertTrue((image.take(8) + image.takeLast(8)).all { it == 90L })
            assertEquals(171L, image[8 + (row[3] as Long).toInt() + constants[3].toInt()])
            assertEquals(205L, image[8 + (row[3] as Long).toInt() + constants[4].toInt()])
        }
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            val allCalls = OriginalStdioChecks.foreignCalls(source)
            assertEquals(symbols.toSet(), allCalls.map { validate(it)!!.symbol }.toSet())
            for (name in names) {
                val audit = json("$prefix/$stage/$name.audit.json")
                assertEquals(true, audit["accepted"], "$stage/$name")
                assertEquals(emptyList<Any?>(), audit["issues"]); assertEquals(emptyList<Any?>(), audit["missingGlobals"])
                assertEquals(listOf(symbols[names.indexOf(name)]), (audit["foreignCalls"] as List<Map<*, *>>).map { it["symbol"] })
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for ((index, name) in names.withIndex()) {
                        val linked = CoreModules.reachable(source, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val entry = program.entryTarget(name)
                        var active = emptyList<RootCallTarget>()
                        val installed = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
                        fun install(target: RootCallTarget) {
                            assertTrue(installed.add(target), "compile each target only once")
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            valid(target)
                        }
                        fun releasedHandoff() {
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.arguments.retainedReferences())
                            assertEquals(0, handoff.results.retainedReferences())
                        }
                        fun invoke(arguments: Array<Any?>, compiled: Boolean): Any? {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val result = Calls.target(entry, arrayOf(0L, *arguments))
                            if (compiled) {
                                // CAF constants are already memoized; the direct size helper
                                // also executes one entry. Memory consumers execute their entry
                                // and the immediate runRW State lambda.
                                assertEquals(before + if (index < constantCount) 1 else 2,
                                    (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$stage/$backend/$name")
                                assertEquals(active, targets(entry)); active.forEach(::valid)
                            }
                            releasedHandoff()
                            return result
                        }
                        fun exercise(compiled: Boolean) {
                            if (index < constantCount) for (extra in listOf(-7L, 0L, 13L))
                                assertEquals(constants[index] + extra, invoke(arrayOf(extra), compiled))
                            else for (row in rows) {
                                val bytes = ByteArray(constants[0].toInt() + 16) { 90 }
                                val address = ManagedAddress.fromByteArray(bytes).plus(8)
                                val expected = (row[4] as List<Long>).map { it.toByte() }.toByteArray()
                                if (name == "originalPokeLflag") {
                                    assertEquals(0L, invoke(arrayOf(address, row[0]), compiled))
                                    val ccOffset = (row[3] as Long).toInt()
                                    bytes[8 + ccOffset + constants[3].toInt()] = 171.toByte()
                                    bytes[8 + ccOffset + constants[4].toInt()] = 205.toByte()
                                    assertArrayEquals(expected, bytes)
                                } else {
                                    expected.copyInto(bytes)
                                    if (name == "originalLflag") assertEquals(row[2], invoke(arrayOf(address), compiled))
                                    else {
                                        val cc = invoke(arrayOf(address), compiled) as ManagedAddress
                                        cc.writeWord8(constants[3], 37L)
                                        assertEquals(37.toByte(), bytes[8 + (row[3] as Long).toInt() + constants[3].toInt()])
                                    }
                                }
                            }
                        }
                        val bindings = linked["bindings"] as List<Map<String, Any?>>
                        val binding = bindings.single { it["name"] == name }
                        val directConstant = name == "originalSigsetSize"
                        if (directConstant) {
                            // This installed Int declaration is inlined by GHC directly
                            // into the consumer, unlike the original CInt CAFs below.
                            // Require that exact source shape, never an optional CAF.
                            assertEquals(1, bindings.size)
                            assertEquals(1L, binding["arity"])
                            assertSame(entry, program.entryTarget(binding["id"] as String))
                            assertEquals(CoreFunctionIdentity.from(linked, binding), (entry.rootNode as GuestRoot).coreIdentity)
                            val call = OriginalStdioChecks.foreignCalls(binding["expr"]).single()
                            assertEquals(OriginalStdioOp.SIZEOF_SIGSET, validate(call))
                            assertEquals(listOf(entry), targets(entry))
                        } else if (index < constantCount) {
                            // Exercise the unchanged original CAF body before forcing its shared
                            // Thunk. Calling its real target does not reset or update that Thunk.
                            assertEquals(2, bindings.size)
                            val caf = bindings.single { it["id"] != binding["id"] }
                            assertEquals(0L, caf["arity"]); assertEquals(true, caf["lifted"])
                            assertEquals("Int", caf["type"])
                            assertEquals(mapOf("primReps" to listOf("BoxedRep (Just Lifted)"),
                                "kind" to "data", "evaluated" to false), caf["rep"])
                            assertEquals(listOf(symbols[index]), OriginalStdioChecks.foreignCalls(caf["expr"])
                                .map { validate(it)!!.symbol })
                            val cafId = caf["id"] as String
                            val thunk = program.entryValue(cafId) as Thunk
                            val target = program.entryTarget(cafId)
                            assertSame(target, thunk.target)
                            // InterfaceClosure keeps the original Id but uses a synthetic
                            // serialization owner, so optional stack identity may be absent.
                            assertEquals(CoreFunctionIdentity.from(linked, caf),
                                (target.rootNode as GuestRoot).coreIdentity)
                            assertNull(thunk.environment)
                            fun invokeCaf(compiled: Boolean) {
                                assertEquals(0, thunk.state); assertSame(target, thunk.target)
                                assertSame(thunk, program.entryValue(cafId))
                                assertSame(target, program.entryTarget(cafId))
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                val value = Calls.target(target, arrayOf(0L)) as DataValue
                                assertEquals(BOXED_INT_CONSTRUCTOR_ID, value.layout.id)
                                assertEquals(1, value.layout.arity)
                                assertEquals(constants[index], value.layout.readLong(value, 0))
                                assertEquals(0, thunk.state); assertSame(target, thunk.target)
                                assertEquals(listOf(target), targets(target))
                                if (compiled) {
                                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                    valid(target)
                                }
                                releasedHandoff()
                            }
                            repeat(3) { invokeCaf(false) }
                            install(target)
                            repeat(3) { invokeCaf(true) }
                        }
                        exercise(false)
                        active = targets(entry)
                        assertEquals(if (index < constantCount) 1 else 2, OriginalStdioChecks.nodes(binding["expr"]).count { it.firstOrNull() == "lam" })
                        assertEquals(if (directConstant) 1 else 2, active.size, "$stage/$backend/$name")
                        active.forEach { if (it !in installed) install(it) else valid(it) }
                        exercise(true)
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun stateStoredOperandsAndAllDestinationPreflightsFailBeforeMutation() {
        val source = module("pre")
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun load(raw: Map<String, Any?>): ExecutableProgram = if (backend == "ast") Program(language, raw) else BytecodeProgram(language, raw)
                for (call in calls()) {
                    val operation = validate(call)!!
                    val raw = OriginalStdioChecks.rawModule(call, source)
                    val target = load(raw).entryTarget("entry")
                    val bytes = ByteArray(4096) { 90 }; val address = ManagedAddress.fromByteArray(bytes)
                    val args = operation.arguments.dropLast(1).map { if (it == "AddrRep") address else 7L }
                    val error = assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *args.toTypedArray(), 9L)) }
                    assertTrue(error.message.orEmpty().contains("zero-width scalar carrier"), error.message)
                    assertTrue(bytes.all { it == 90.toByte() })
                    for (index in operation.arguments.indices)
                        assertThrows(RuntimeFault::class.java) { load(OriginalStdioChecks.rawModule(call, source, index)) }
                    if (operation.termiosAddress) for (bad in listOf(ManagedAddress.nullAddress(), address.plus(4095))) {
                        val badArgs = args.toMutableList(); badArgs[0] = bad
                        assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *badArgs.toTypedArray(), Unit)) }
                    }
                    if (operation == OriginalStdioOp.POKE_LFLAG) for (bad in listOf(-1L, 1L shl 32)) {
                        assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, address, bad, Unit)) }
                        assertTrue(bytes.all { it == 90.toByte() })
                    }
                    val malformed = copy(raw) as Map<String, Any?>
                    (OriginalStdioChecks.foreignCalls(malformed).single()[1] as MutableList<Any?>)[1] = 17L
                    assertThrows(RuntimeFault::class.java) { load(malformed) }
                    val handoff = language.handoffState.get()
                    assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                }
            } finally { context.leave() }
        }
    }

    @Test fun exactOriginalDescriptorsRejectMalformedStateOnlyAndOtherRawProofs() {
        val calls = calls(); assertEquals(13, calls.size)
        assertEquals(symbols.toSet(), calls.map { validate(it)!!.symbol }.toSet())
        for (original in calls) {
            val operation = validate(original)!!
            fun mutate(action: (MutableList<Any?>, MutableMap<String, Any?>, MutableMap<String, Any?>) -> Unit) {
                val call = copy(original) as MutableList<Any?>
                val metadata = call[6] as MutableMap<String, Any?>
                val descriptor = metadata["foreignCall"] as MutableMap<String, Any?>
                action(call, metadata, descriptor)
                assertThrows(RuntimeFault::class.java) { validate(call) }
            }
            if (operation.result != null) for (wrong in listOf("IntRep", "Int32Rep", "Word32Rep")) {
                if (wrong == operation.result) continue
                mutate { _, metadata, descriptor ->
                    for (result in listOf(metadata["rep"], descriptor["resultRep"])) {
                        val tuple = result as MutableMap<String, Any?>
                        tuple["primReps"] = listOf(wrong)
                        val scalar = (tuple["components"] as List<MutableMap<String, Any?>>)[1]
                        scalar["kind"] = "long"; scalar["primReps"] = listOf(wrong)
                    }
                }
            }
            for (key in listOf("schema", "arity", "suppliedArity")) for (bad in listOf(null, true, 1.0, "1", -1L, 1L shl 32))
                mutate { _, _, descriptor -> descriptor[key] = bad }
            for ((key, bad) in listOf("safety" to "safe", "safety" to "interruptible", "convention" to "capi", "extra" to 0L))
                mutate { _, _, descriptor -> descriptor[key] = bad }
            for ((key, bad) in listOf("unit" to "base", "kind" to "dynamic", "isFunction" to false, "extra" to 1L))
                mutate { _, _, descriptor -> (descriptor["target"] as MutableMap<String, Any?>)[key] = bad }
            for (index in (original[2] as List<*>).indices) {
                mutate { call, _, _ -> (call[3] as MutableList<Any?>)[index] = true }
                mutate { _, _, descriptor -> (descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[index]["primReps"] = listOf("WordRep") }
                mutate { _, _, descriptor -> (descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[index]["aggregate"] = "unboxed-tuple" }
            }
            for (site in listOf("resultRep", "rep")) {
                mutate { _, metadata, descriptor -> (if (site == "rep") metadata else descriptor)[site] = OriginalStdioFixtures.scalar(null) }
                mutate { _, metadata, descriptor ->
                    ((if (site == "rep") metadata else descriptor)[site] as MutableMap<String, Any?>)["components"] = emptyList<Any?>()
                }
            }
            val alias = copy(original) as MutableList<Any?>
            val target = ((alias[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>
            target["symbol"] = "prefix" + target["symbol"]
            assertNull(validate(alias))
        }
    }
}
