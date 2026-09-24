// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class BoxedArrayExtensionsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val operations = listOf(ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE, ArrayOp.CLONE_MUTABLE,
        ArrayOp.COPY, ArrayOp.COPY_MUTABLE, ArrayOp.UNSAFE_THAW)
    private val names = listOf("boxedExtSizes", "boxedExtClone", "boxedExtCopy", "boxedExtMove", "boxedExtThaw", "boxedExtLazy")
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    private fun scalar(kind: String, vararg reps: String): Map<String, Any?> =
        mapOf("kind" to kind, "primReps" to reps.toList(), "evaluated" to true)
    private val array = scalar("object", "BoxedRep (Just Unlifted)")
    private val int = scalar("long", "IntRep")
    private val state = scalar("void")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private val tuple = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "components" to listOf(state, array), "primReps" to array["primReps"], "evaluated" to true)
    private fun synthetic(operation: ArrayOp): Map<String, Any?> {
        val proofs = when (operation) {
            ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE -> listOf(array)
            ArrayOp.CLONE_MUTABLE -> listOf(array, int, int, state)
            ArrayOp.UNSAFE_THAW -> listOf(array, state)
            else -> listOf(array, int, array, int, int, state)
        }
        val result = if (operation.tuple) tuple else if (operation in listOf(ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE)) int else state
        val returned = if (operation.tuple) array else result
        val params = proofs.mapIndexed { i, p -> mapOf("id" to "p$i", "lifted" to false, "rep" to p) }
        val app = listOf("app", listOf("prim", operation.primitive),
            params.map { listOf("var", it["id"], mapOf("rep" to it["rep"])) }, List(params.size) { false }, false, false, mapOf("rep" to result))
        val body = if (!operation.tuple) app else listOf("case", app, "pair", listOf(listOf("data", "Tuple2", listOf("s", "copy"),
            listOf("var", "copy", mapOf("rep" to array)), mapOf("binders" to listOf(
                mapOf("id" to "s", "lifted" to false, "rep" to state), mapOf("id" to "copy", "lifted" to false, "rep" to array))))),
            mapOf("rep" to array, "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to tuple)))
        return mapOf("instrument" to true, "constructors" to listOf(mapOf("id" to "Tuple2", "kind" to "unboxed-tuple", "arity" to 2,
            "fieldReps" to listOf(emptyList<String>(), array["primReps"]), "fieldLifted" to listOf(false, false), "strictFields" to listOf(false, false))),
            "bindings" to listOf(mapOf("id" to "operation", "name" to "operation", "arity" to params.size, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", params, body, mapOf("rep" to closure, "resultRep" to returned)))))
    }
    private fun invalidRanges(size: Long) = listOf(Long.MIN_VALUE to 0L, -1L to 0L, size + 1 to 0L,
        0L to -1L, 0L to Long.MIN_VALUE, 0L to Long.MAX_VALUE, Long.MAX_VALUE to 1L,
        (1L shl 32) to 0L, 1L to Long.MAX_VALUE, size to 1L)

    @Test fun fullWidthRangesOverlapIndependentClonesAndImmutableAliasing() {
        val elements = arrayOf<Any?>(Any(), null, byteArrayOf(7), Any(), Any())
        for (size in 0..5) for (count in 0..size) for (from in 0..size-count) for (to in 0..size-count) {
            val source = elements.copyOf(size)
            val snapshot = source.copyOfRange(from, from + count)
            val expected = source.copyOf().also { snapshot.forEachIndexed { i, value -> it[to+i] = value } }
            ManagedArray.copy(source, from.toLong(), source, to.toLong(), count.toLong(), true)
            assertArrayEquals(expected, source)
            val destination = Array<Any?>(size) { Any() }
            ManagedArray.copy(elements.copyOf(size), from.toLong(), destination, to.toLong(), count.toLong(), false)
            for (i in snapshot.indices) assertSame(snapshot[i], destination[to+i])
        }
        for (mutable in listOf(false, true)) for ((offset, count) in invalidRanges(5)) for (badSource in listOf(false, true)) {
            val source = elements.copyOf(); val destination = elements.copyOf()
            assertThrows(RuntimeFault::class.java) {
                ManagedArray.copy(source, if (badSource) offset else 0, destination,
                    if (badSource) 0 else offset, count, mutable)
            }
            assertArrayEquals(elements, source); assertArrayEquals(elements, destination)
        }
        for (count in listOf(0L, 1L, 5L)) {
            val source = elements.copyOf()
            assertThrows(RuntimeFault::class.java) { ManagedArray.copy(source, 0, source, 0, count, false) }
            assertArrayEquals(elements, source)
        }
    }

    @Test fun bothBackendsKeepLazyReferencesIdentityAndCompiledMutationVisibility() {
        for (backend in listOf("ast", "bytecode")) for (operation in operations) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = program(language, synthetic(operation), backend)
                val target = program.entryTarget("operation")
                var entered = 0
                val bottom = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? { entered++; throw RuntimeFault("boxed bottom entered") }
                }.callTarget, null)
                fun invoke(source: Any?, from: Long = 0, destination: Any? = source, to: Long = 0, count: Long = 0, token: Any? = Unit): Any? {
                    val values: Array<Any?> = when (operation) {
                        ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE -> arrayOf(source)
                        ArrayOp.CLONE_MUTABLE -> arrayOf(source, from, count, token)
                        ArrayOp.UNSAFE_THAW -> arrayOf(source, token)
                        else -> arrayOf(source, from, destination, to, count, token)
                    }
                    return Calls.target(target, arrayOf(0L, *values))
                }
                fun cases(compiled: Boolean) {
                    for (n in listOf(0, 1, 5)) for (count in 0..n) for (from in 0..n-count) for (to in 0..n-count) {
                        val source = Array<Any?>(n) { if (it % 2 == 0) bottom else Any() }
                        val old = source.copyOf()
                        val destination = if (operation == ArrayOp.COPY_MUTABLE) source else Array<Any?>(n) { Any() }
                        val expected = destination.copyOf().also { copy -> for (i in 0 until count) copy[to+i] = old[from+i] }
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val result = invoke(source, from.toLong(), destination, to.toLong(), count.toLong())
                        when (operation) {
                            ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE -> assertEquals(n.toLong(), result)
                            ArrayOp.UNSAFE_THAW -> { assertSame(source, result); if (n > 0) { val replacement = Any(); ManagedArray.write(result as Array<Any?>, 0, replacement); assertSame(replacement, source[0]) } }
                            ArrayOp.CLONE_MUTABLE -> {
                                val copy = ManagedArray.require(result); assertNotSame(source, copy)
                                assertArrayEquals(old.copyOfRange(from, from+count), copy)
                                if (count > 0) { copy[0] = Any(); assertSame(old[from], source[from]) }
                            }
                            else -> { assertSame(Unit, result); assertArrayEquals(expected, destination) }
                        }
                        if (compiled) { assertEquals(before+1, (program.diagnostics().getValue("compiledEntries") as Number).toLong()); valid(target) }
                        released(language)
                    }
                }
                cases(false); compile(target); cases(true)
                val source = arrayOf<Any?>(bottom, Any(), bottom, Any(), null)
                if (operation !in listOf(ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE)) {
                    val before = source.copyOf()
                    assertThrows(RuntimeFault::class.java) { invoke(source, destination = arrayOfNulls<Any?>(5), token = 9L) }
                    assertArrayEquals(before, source)
                }
                if (operation in listOf(ArrayOp.CLONE_MUTABLE, ArrayOp.COPY, ArrayOp.COPY_MUTABLE)) {
                    for ((offset, count) in invalidRanges(5)) {
                        val before = source.copyOf(); val destination = source.copyOf()
                        assertThrows(RuntimeFault::class.java) { invoke(source, offset, destination, 0, count) }
                        assertArrayEquals(before, source); assertArrayEquals(before, destination)
                        if (operation != ArrayOp.CLONE_MUTABLE) {
                            assertThrows(RuntimeFault::class.java) { invoke(source, 0, destination, offset, count) }
                            assertArrayEquals(before, destination)
                        }
                    }
                }
                if (operation == ArrayOp.COPY) assertThrows(RuntimeFault::class.java) { invoke(source, count = 0) }
                for (bad in listOf(Any(), byteArrayOf(1), arrayOf("wrong component")))
                    assertThrows(RuntimeFault::class.java) { invoke(bad) }
                assertEquals(0, entered); released(language)
            } finally { context.leave() }
        }
    }

    @Test fun stateIsCheckedBeforeCopyAndNoResultIsPublishedOnFailure() {
        val builder = FrameDescriptor.newBuilder(); val slot = builder.addSlot(FrameSlotKind.Object, "result", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), builder.build())
        val sentinel = Any(); val source = arrayOf<Any?>(sentinel); val destination = arrayOf<Any?>(Any())
        val original = destination.copyOf(); val events = mutableListOf<String>()
        fun operand(name: String, value: Any?): Expr = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return value }
        }
        for (operation in listOf(ArrayOp.COPY, ArrayOp.COPY_MUTABLE)) {
            events.clear()
            val expr = arrayExpression(operation, CoreRepresentation.UNKNOWN, arrayOf(operand("source", source),
                operand("from", Long.MAX_VALUE), operand("destination", destination), operand("to", Long.MAX_VALUE),
                operand("count", Long.MAX_VALUE), operand("state", 9L)))
            val failure = assertThrows(RuntimeFault::class.java) { expr.execute(frame) }
            assertTrue(failure.message.orEmpty().contains("zero-width scalar carrier"))
            assertEquals(listOf("source", "from", "destination", "to", "count", "state"), events)
            assertArrayEquals(original, destination)
        }
        for (operation in listOf(ArrayOp.CLONE_MUTABLE, ArrayOp.UNSAFE_THAW)) {
            frame.setObject(slot, sentinel)
            val args = if (operation == ArrayOp.CLONE_MUTABLE) arrayOf(operand("array", source), operand("offset", 0L), operand("count", 1L), operand("state", 9L))
                else arrayOf(operand("array", source), operand("state", 9L))
            assertThrows(RuntimeFault::class.java) { arrayExpression(operation, CoreRepresentation.UNKNOWN, args).executeTuple(frame, intArrayOf(slot), 0) }
            assertSame(sentinel, frame.getObject(slot))
        }
    }

    private fun mutable(value: Any?): Any? = when (value) {
        is List<*> -> value.map(::mutable).toMutableList()
        is Map<*, *> -> value.entries.associate { it.key to mutable(it.value) }.toMutableMap()
        else -> value
    }
    private fun nodes(value: Any?): List<List<Any?>> = when (value) {
        is List<*> -> listOf(value) + value.flatMap(::nodes)
        is Map<*, *> -> value.values.flatMap(::nodes)
        else -> emptyList()
    }
    @Test fun rawFlagsWidthsLevityStateAndAggregateProofsFailClosed() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (diagnostic in listOf(false, true)) for (mutation in 0..12) {
                    val module = mutable(synthetic(operation)) as MutableMap<String, Any?>
                    val app = nodes(module).single { it.firstOrNull() == "app" } as MutableList<Any?>
                    val args = app[2] as MutableList<List<Any?>>; val flags = app[3] as MutableList<Any?>
                    val proof = (app[6] as MutableMap<String, Any?>)["rep"] as MutableMap<String, Any?>
                    val first = CoreRepresentations.metadata(args[0])!!.getValue("rep") as MutableMap<String, Any?>
                    when (mutation) {
                        0 -> flags[0] = true
                        1 -> flags[0] = 0L
                        2 -> first["primReps"] = listOf("BoxedRep (Just Lifted)")
                        3 -> first["primReps"] = listOf("BoxedRep Nothing")
                        4 -> first["components"] = emptyList<Any?>()
                        5 -> first["aggregate"] = null
                        6 -> first["vector"] = null
                        7 -> first["evaluated"] = 1L
                        8 -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex) }
                        9 -> { args.add(args[0]); flags.add(false) }
                        10 -> proof["primReps"] = listOf("WordRep")
                        11 -> { proof.clear(); proof.putAll(mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>(), "evaluated" to true)) }
                        12 -> {
                            val integral = args.map { CoreRepresentations.metadata(it)!!.getValue("rep") as MutableMap<String, Any?> }.firstOrNull { it["kind"] == "long" }
                            if (integral != null) integral["primReps"] = listOf("Int64Rep")
                            else proof["components"] = emptyList<Any?>()
                        }
                    }
                    assertThrows(RuntimeFault::class.java, { program(language, module + ("diagnosticUnsupported" to diagnostic), backend) }, "$backend/$operation/$diagnostic/$mutation")
                }
            } finally { context.leave() }
        }
    }

    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>()); val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val roots = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            roots.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }.forEach { call ->
                (call.currentCallTarget as? RootCallTarget)?.takeIf { it.rootNode is GuestRoot }?.let(::visit)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun read(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun checked(manifest: Map<String, Any?>): Map<String, Any?> {
        require(manifest["schema"] == 1L && manifest["ghc"] == "9.14.1" && manifest["wordBits"] == 64L)
        require(manifest["entries"] == names && manifest["installedArtifactsHashed"] == false && manifest["runtimeVerified"] == false)
        val oracle = manifest["oracle"] as String
        require(Regex("build/boxed-array-extensions/run-[1-9][0-9]*/logs/native-oracle.stdout").matches(oracle))
        val attempt = oracle.removeSuffix("/logs/native-oracle.stdout")
        val stages = manifest["stages"] as Map<String, List<String>>
        val audits = manifest["audits"] as Map<String, List<String>>
        require(stages.keys == setOf("pre", "post") && audits.keys == stages.keys)
        for (stage in stages.keys) {
            require(stages.getValue(stage).toSet() == setOf("$attempt/$stage-core/BoxedArrayExtensionsAudit.json", "$attempt/$stage-core/THC.InterfaceClosure.json"))
            require(stages.getValue(stage).size == 2)
            require(audits.getValue(stage) == names.map { "$attempt/$stage-$it.audit.json" })
        }
        val labels = listOf("ghc-version", "ghc-info", "pre-export", "post-export", "native-compile", "native-oracle") +
            listOf("pre", "post").flatMap { stage -> names.map { "$stage-audit-$it" } }
        val requiredArtifacts = (stages.values.flatten() + audits.values.flatten() + listOf("$attempt/native/boxed-array-extensions-oracle") +
            labels.flatMap { name -> listOf("stdout", "stderr", "command.json").map { "$attempt/logs/$name.$it" } }).toSet()
        val requiredSources = (listOf("compiler/test-fixtures/BoxedArrayExtensionsAudit.hs", "compiler/test-fixtures/BoxedArrayExtensionsNative.hs",
            "test/haskell-fixtures/BoxedArrayExtensionsFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs",
            "thc.cabal", "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py", "scripts/audit-core.py",
            "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }).toSet()
        for ((key, expectedPaths) in listOf("inputHashes" to requiredSources, "artifactHashes" to requiredArtifacts)) {
            val hashes = manifest[key] as Map<String, String>
            require(hashes.keys == expectedPaths)
            for ((path, expected) in hashes) {
                require(Regex("[0-9a-f]{64}").matches(expected))
                val file = File(root, path)
                require(file.canonicalFile == file.absoluteFile)
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                require(expected == actual) { "Stale boxed-array extension $path" }
            }
        }
        val commands = manifest["commands"] as List<Map<String, Any?>>
        require(commands.size == labels.size && commands.all { it["exit"] == 0L && it["timedOut"] != true })
        val records = labels.map { read("$attempt/logs/$it.command.json") }
        require(commands.toSet() == records.toSet() && records.toSet().size == commands.size)
        require(manifest["nativeRows"] == 1830L)
        return manifest
    }
    @Test fun originalProofMutationsAndStaleOrIncompleteReceiptsAreRejected() {
        val manifest = checked(read("build/boxed-array-extensions/manifest.json"))
        for (mutation in 0..6) {
            val bad = mutable(manifest) as MutableMap<String, Any?>
            when (mutation) {
                0 -> (bad["inputHashes"] as MutableMap<String, Any?>).remove("thc.cabal")
                1 -> (bad["artifactHashes"] as MutableMap<String, Any?>).remove(bad["oracle"])
                2 -> (bad["inputHashes"] as MutableMap<String, Any?>)["thc.cabal"] = "0".repeat(64)
                3 -> bad["schema"] = 1.0
                4 -> bad["nativeRows"] = 1L
                5 -> bad["oracle"] = "../outside"
                6 -> (bad["commands"] as MutableList<Any?>).removeAt(0)
            }
            assertThrows(IllegalArgumentException::class.java) { checked(bad) }
        }
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (paths in (manifest["stages"] as Map<String, List<String>>).values) for (operation in operations) {
                    val module = CoreModules.merge(paths.map(::read))
                    val calls = nodes(module).filter { it.firstOrNull() == "app" && (it.getOrNull(1) as? List<*>)?.take(2) == listOf("prim", operation.primitive) }
                    assertTrue(calls.isNotEmpty(), operation.primitive)
                    for (diagnostic in listOf(false, true)) {
                        val changed = mutable(module) as Map<String, Any?>
                        val app = nodes(changed).first { it.firstOrNull() == "app" && (it.getOrNull(1) as? List<*>)?.take(2) == listOf("prim", operation.primitive) }
                        (app[3] as MutableList<Any?>)[0] = 0L
                        assertThrows(RuntimeFault::class.java) { program(language, changed + ("diagnosticUnsupported" to diagnostic), backend) }
                    }
                }
            } finally { context.leave() }
        }
    }
    private fun model(name: String, args: List<Long>): Long {
        val (seed, size, from, to, count) = args
        val source = MutableList(size.toInt()) { seed + it }
        fun weighted(values: List<Long>) = values.withIndex().fold(0L) { sum, (i, value) -> sum + (i+1) * value }
        return when (name) {
            "boxedExtSizes" -> 2 * size
            "boxedExtLazy" -> seed
            "boxedExtClone" -> {
                val copy = source.subList(from.toInt(), (from+count).toInt()).toMutableList()
                if (copy.isNotEmpty()) copy[0] = seed + 77
                weighted(source) * 3 + weighted(copy) * 5
            }
            "boxedExtThaw" -> { if (count > 0) source[to.toInt()] = seed + 77; weighted(source) }
            else -> {
                val copy = source.subList(from.toInt(), (from+count).toInt()).toList()
                val destination = if (name == "boxedExtMove") source else MutableList(size.toInt()) { seed + 100 + it }
                copy.forEachIndexed { i, value -> destination[to.toInt()+i] = value }
                weighted(destination)
            }
        }
    }
    @Test fun nativePreAndPostWithInlining() = native(true)
    @Test fun nativePreAndPostAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = checked(read("build/boxed-array-extensions/manifest.json"))
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"]); assertEquals(64L, manifest["wordBits"])
        assertEquals(names, manifest["entries"]); assertEquals(false, manifest["installedArtifactsHashed"]); assertEquals(false, manifest["runtimeVerified"])
        val rows = File(root, manifest["oracle"] as String).readLines().map { it.split('\t') }
        val requests = names.flatMap { name -> listOf(Long.MIN_VALUE, -7L, 0L, 23L, Long.MAX_VALUE).flatMap { seed -> listOf(0L, 1L, 4L).flatMap { n ->
            (0..n).flatMap { count -> (0..n-count).flatMap { from -> (0..n-count).map { to -> listOf(name, seed.toString(), n.toString(), from.toString(), to.toString(), count.toString()) } } }
        } } }
        assertEquals(requests, rows.map { it.take(6) }); assertEquals(rows.size.toLong(), manifest["nativeRows"])
        rows.forEach { row -> assertEquals(model(row[0], row.drop(1).take(5).map(String::toLong)), row[6].toLong(), "native/model $row") }
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val modules = paths.map(::read); val merged = CoreModules.merge(modules)
            val boundary = if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep"
            assertEquals(boundary, modules.single { it["module"] == "BoxedArrayExtensionsAudit" }["boundary"])
            val found = names.flatMap { name -> ArrayCoreEvidence(merged, name).primitiveCounts.keys }.toSet()
            assertTrue(operations.all { it.primitive in found }, "$stage missing original primops")
            for (path in (manifest["audits"] as Map<String, List<String>>).getValue(stage)) assertEquals(true, read(path)["accepted"])
            for (name in names) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = program(language, CoreModules.reachable(merged, name, strictLink = true) + ("instrument" to true), backend)
                    val entry = context.asValue(EntryValue(program, name, 5)); val host = program.hostEntryTarget(5)
                    val cases = rows.filter { it[0] == name }
                    fun check(row: List<String>) {
                        assertEquals(row[6].toLong(), entry.execute(*row.drop(1).take(5).map { it.toLong() as Any }.toTypedArray()).asLong(), "$stage/$backend/$name/$row")
                        released(language)
                    }
                    cases.forEach(::check)
                    val targets = activeTargets(host); assertTrue(targets.size > 1)
                    targets.filter { it !== host }.forEach(::compile)
                    assertTrue(entry.invokeMember("compile").asBoolean())
                    for (row in cases.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                        assertEquals(targets, activeTargets(host)); targets.forEach(::valid)
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong())
                } finally { context.leave() }
            }
        }
    }
}
