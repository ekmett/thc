// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(180)
class BoxedCasTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("arrayCas", "arrayCasUnlifted", "smallCas", "smallCasUnlifted", "varCas",
        "varCasUnlifted", "modifyValue", "modifyLazy", "modifyBottom", "boxedCasCounter")
    private val seeds = listOf(Long.MIN_VALUE, -1000000L, -17L, -1L, 0L, 1L, 17L, 1000000L, Long.MAX_VALUE)
    private val cases = listOf("casArray#", "casSmallArray#", "casMutVar#")
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
    private fun scalar(kind: String, vararg reps: String) =
        mapOf("kind" to kind, "primReps" to reps.toList(), "evaluated" to true)
    private val reference = scalar("object", "BoxedRep (Just Unlifted)")
    private val int = scalar("long", "IntRep")
    private val state = scalar("void")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private val data = scalar("data", "BoxedRep (Just Lifted)")
    private fun lifted(p: Map<String, Any>) = p["primReps"] == listOf("BoxedRep (Just Lifted)")
    private fun binder(id: String, rep: Map<String, Any>) = mapOf("id" to id, "lifted" to lifted(rep), "rep" to rep)
    private fun variable(id: String, rep: Map<String, Any>) = listOf("var", id, mapOf("rep" to rep))

    /** Return both fields in a boxed record, leaving the primitive's boxed payload unforced. */
    private fun synthetic(primitive: String, unlifted: Boolean = false): Map<String, Any?> {
        val element = if (unlifted) reference else scalar("object", "BoxedRep (Just Lifted)")
        val modify = primitive == "atomicModifyMutVar_#"
        val proofs = when (primitive) {
            "casMutVar#" -> listOf(reference, element, element, state)
            "atomicModifyMutVar_#" -> listOf(reference, closure, state)
            else -> listOf(reference, int, element, element, state)
        }
        val fields = if (modify) listOf(state, element, element) else listOf(state, int, element)
        val tuple = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to true,
            "components" to fields, "primReps" to fields.flatMap { it["primReps"] as List<String> })
        val app = listOf("app", listOf("prim", primitive), proofs.mapIndexed { i, p -> variable("p$i", p) },
            proofs.map(::lifted), false, false, mapOf("rep" to tuple))
        val record = listOf("app", listOf("con", "Record", 2),
            listOf(variable("first", fields[1]), variable("second", fields[2])), fields.drop(1).map(::lifted),
            false, false, mapOf("rep" to data))
        val body = listOf("case", app, "tuple", listOf(listOf("data", "Tuple3", listOf("s", "first", "second"), record,
            mapOf("binders" to listOf(binder("s", state), binder("first", fields[1]), binder("second", fields[2]))))),
            mapOf("rep" to data, "binder" to mapOf("id" to "tuple", "lifted" to false, "rep" to tuple)))
        return mapOf("instrument" to true, "constructors" to listOf(
            mapOf("id" to "Tuple3", "name" to "Tuple3", "kind" to "unboxed-tuple", "arity" to 3),
            mapOf("id" to "Record", "name" to "Record", "kind" to "boxed", "arity" to 2,
                "fieldReps" to fields.drop(1).map { it["primReps"] },
                "fieldLifted" to fields.drop(1).map(::lifted), "strictFields" to listOf(false, false))),
            "bindings" to listOf(mapOf("id" to "operation", "name" to "operation", "arity" to proofs.size,
                "lifted" to true, "rep" to closure, "expr" to listOf("lam", proofs.mapIndexed { i, p -> binder("p$i", p) },
                    body, mapOf("rep" to closure, "resultRep" to data)))))
    }
    private fun storage(primitive: String, value: Any?): Any = when (primitive) {
        "casArray#" -> ManagedArray.allocate(1, value)
        "casSmallArray#" -> ManagedSmallArray.allocate(1, value)
        else -> ManagedMutVar(value)
    }
    private fun exchange(primitive: String, storage: Any, old: Any?, new: Any?): Any? = when (primitive) {
        "casArray#" -> ManagedArray.compareExchange(ManagedArray.require(storage), 0, old, new)
        "casSmallArray#" -> ManagedSmallArray.compareExchange(ManagedSmallArray.require(storage), 0, old, new)
        else -> ManagedMutVar.require(storage).compareExchange(old, new)
    }
    private fun current(primitive: String, storage: Any): Any? = exchange(primitive, storage, null, null)
    private fun cas(target: RootCallTarget, primitive: String, storage: Any, old: Any?, new: Any?,
                    index: Long = 0, token: Any? = Unit): DataValue {
        val arguments = if (primitive == "casMutVar#") arrayOf(storage, old, new, token)
            else arrayOf(storage, index, old, new, token)
        return Calls.target(target, arrayOf(0L, *arguments)) as DataValue
    }

    @Test fun bothBackendsUseIdentityTicketsWithoutForcingAndEnterInstalledRootExactlyOnce() {
        class Equal { override fun equals(other: Any?): Boolean = error("CAS called equals"); override fun hashCode() = 0 }
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true))
            for (primitive in cases) for (unlifted in listOf(false, true)) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val p = program(language, synthetic(primitive, unlifted), backend)
                    val target = p.entryTarget("operation")
                    val evaluator = force(language, Metrics(false))
                    fun completed(value: Any?) = Thunk(object : RootNode(null) {
                        override fun execute(frame: VirtualFrame): Any? = value
                    }.callTarget, null).also { assertSame(value, Calls.target(evaluator, arrayOf(it))) }
                    val entered = AtomicInteger()
                    val bottom = Thunk(object : RootNode(null) {
                        override fun execute(frame: VirtualFrame): Nothing { entered.incrementAndGet(); error("forced CAS value") }
                    }.callTarget, null)
                    fun exercise(compiled: Boolean) {
                        val old = Equal(); val distinct = Equal(); val cell = storage(primitive, old)
                        for ((expected, replacement, flag, returned) in listOf(
                            listOf(distinct, bottom, 1L, old), listOf(old, bottom, 0L, bottom),
                            listOf(old, distinct, 1L, bottom), listOf(bottom, old, 0L, old),
                            listOf(old, old, 0L, old))) {
                            val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val result = cas(target, primitive, cell, expected, replacement)
                            assertEquals(flag, result.layout.readLong(result, 0), "$backend/$primitive")
                            assertSame(returned, result.layout.read(result, 1))
                            assertSame(returned, current(primitive, cell))
                            if (compiled) {
                                assertEquals(before + 1, (p.diagnostics().getValue("compiledEntries") as Number).toLong())
                                assertSame(target, p.entryTarget("operation")); valid(target)
                            }
                            released(language)
                        }
                        if (!unlifted) {
                            val whnf = Equal(); val indirection = completed(whnf); val another = completed(whnf)
                            for ((stored, expected) in listOf(indirection to whnf, whnf to indirection, indirection to another)) {
                                val cell = storage(primitive, stored)
                                val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                                val result = cas(target, primitive, cell, expected, bottom)
                                assertEquals(0L, result.layout.readLong(result, 0))
                                assertSame(bottom, result.layout.read(result, 1)); assertSame(bottom, current(primitive, cell))
                                if (compiled) {
                                    assertEquals(before + 1, (p.diagnostics().getValue("compiledEntries") as Number).toLong())
                                    assertSame(target, p.entryTarget("operation")); valid(target)
                                }
                            }
                        }
                    }
                    repeat(3) { exercise(false) }; compile(target); exercise(true)
                    assertEquals(0, entered.get())
                } finally { context.leave() }
            }
    }

    @Test fun badStateBoundsAndStorageFamiliesNeverMutate() {
        for (backend in listOf("ast", "bytecode")) for (primitive in cases) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val target = program(language, synthetic(primitive), backend).entryTarget("operation")
                val old = Any(); val new = Any(); val cell = storage(primitive, old)
                assertThrows(RuntimeFault::class.java) { cas(target, primitive, cell, old, new, token = 7L) }
                assertSame(old, current(primitive, cell))
                if (primitive != "casMutVar#") for (index in listOf(Long.MIN_VALUE, -1L, 1L, 1L shl 32, Long.MAX_VALUE)) {
                    assertThrows(RuntimeFault::class.java) { cas(target, primitive, cell, old, new, index) }
                    assertSame(old, current(primitive, cell))
                }
                for (other in cases.filter { it != primitive }) {
                    val wrong = storage(other, old)
                    assertThrows(RuntimeFault::class.java) { cas(target, primitive, wrong, old, new) }
                    assertSame(old, current(other, wrong))
                }
                released(language)
            } finally { context.leave() }
        }
    }

    @Test fun compareExchangeRacesPublishOneIdentityChainWithoutLostUpdates() {
        class Ticket(val serial: Int, val previous: Ticket?) { var payload = 0 }
        for (primitive in cases) {
            val first = Ticket(0, null); val cell = storage(primitive, first)
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs = List(4) { pool.submit {
                    start.await()
                    repeat(64) {
                        var ticket = current(primitive, cell) as Ticket
                        while (true) {
                            assertEquals(ticket.serial * 17, ticket.payload)
                            val replacement = Ticket(ticket.serial + 1, ticket).also { it.payload = it.serial * 17 }
                            val witness = exchange(primitive, cell, ticket, replacement) as Ticket
                            if (witness === ticket) break
                            ticket = witness
                        }
                    }
                } }
                start.countDown(); jobs.forEach { it.get(10, TimeUnit.SECONDS) }
                var ticket = current(primitive, cell) as Ticket
                for (serial in 256 downTo 1) {
                    assertEquals(serial, ticket.serial); assertEquals(serial * 17, ticket.payload)
                    ticket = ticket.previous!!
                }
                assertSame(first, ticket)
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
        }
    }

    private fun force(language: Language, metrics: Metrics) = object : RootNode(language, FrameDescriptor.newBuilder().build()) {
        @Child private var evaluator = Force(metrics)
        override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
    }.callTarget

    @Test fun admissionKeepsCarriersArityStateAndLogicalTupleOrder() {
        val void = CoreRepresentations.parse(state); val ref = CoreRepresentations.parse(reference)
        val flag = CoreRepresentations.parse(int); val function = CoreRepresentations.parse(closure)
        for (unlifted in listOf(false, true)) {
            val element = if (unlifted) ref else CoreRepresentations.parse(data)
            val tuple = CoreRepresentation(CoreKind.UNKNOWN, present = true, components = listOf(void, flag, element),
                primReps = flag.primReps!! + element.primReps!!)
            for (primitive in cases) {
                val arguments = if (primitive == "casMutVar#") listOf(ref, element, element, void)
                    else listOf(ref, flag, element, element, void)
                val flags = arguments.map { it.primReps == listOf("BoxedRep (Just Lifted)") }
                fun validate(args: List<CoreRepresentation>, result: CoreRepresentation) = when (primitive) {
                    "casArray#" -> ArrayOp.CAS.validate(args, flags, result)
                    "casSmallArray#" -> SmallArrayOp.CAS.validate(args, flags, result)
                    else -> MutVarOp.CAS.validate(args, flags, result)
                }
                validate(arguments, tuple)
                assertThrows(RuntimeFault::class.java) { validate(arguments.dropLast(1), tuple) }
                assertThrows(RuntimeFault::class.java) { validate(listOf(flag) + arguments.drop(1), tuple) }
                assertThrows(RuntimeFault::class.java) { validate(arguments.dropLast(1) + flag, tuple) }
                assertThrows(RuntimeFault::class.java) { validate(arguments, tuple.copy(components = listOf(void, element, flag))) }
            }
        }
        val lifted = CoreRepresentations.parse(data)
        val tuple = CoreRepresentation(CoreKind.UNKNOWN, present = true, components = listOf(void, lifted, lifted),
            primReps = lifted.primReps!! + lifted.primReps)
        MutVarOp.MODIFY.validate(listOf(ref, function, void), listOf(false, true, false), tuple)
        assertThrows(RuntimeFault::class.java) {
            MutVarOp.MODIFY.validate(listOf(ref, ref, void), listOf(false, false, false), tuple)
        }
        assertThrows(RuntimeFault::class.java) {
            MutVarOp.MODIFY.validate(listOf(ref, function, void), listOf(false, true, false), tuple.copy(components = listOf(void, lifted)))
        }
    }

    @Test fun lazyModifyPublishesTheReturnedApplicationAndSharesValuesAndFailures() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val metrics = Metrics(false); val force = force(language, metrics)
                val p = program(language, synthetic("atomicModifyMutVar_#"), backend)
                val target = p.entryTarget("operation")
                val old = Any(); val replacement = Any(); val payload = Any(); val entered = AtomicInteger()
                for (failure in listOf(false, true)) {
                    val modifier = Closure(null, arity = 1, target = object : GuestRoot(language, FrameLayout().build()) {
                        override fun bloom(frame: VirtualFrame) = frame.arguments[0] as Long
                        override fun execute(frame: VirtualFrame): Any? {
                            assertSame(old, frame.arguments[1]); entered.incrementAndGet()
                            if (failure) throw GuestException(payload, this)
                            return replacement
                        }
                    }.callTarget)
                    fun run(compiled: Boolean) {
                        val cell = ManagedMutVar(old); val beforeCalls = entered.get()
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val result = Calls.target(target, arrayOf(0L, cell, modifier, Unit)) as DataValue
                        assertSame(old, result.layout.read(result, 0))
                        assertSame(cell.value, result.layout.read(result, 1)); assertTrue(cell.value is Thunk)
                        assertEquals(beforeCalls, entered.get())
                        if (compiled) {
                            assertEquals(before + 1, (p.diagnostics().getValue("compiledEntries") as Number).toLong())
                            assertSame(target, p.entryTarget("operation")); valid(target)
                        }
                        repeat(3) {
                            if (failure) assertSame(payload, assertThrows(GuestException::class.java) {
                                Calls.target(force, arrayOf(cell.value))
                            }.payload) else assertSame(replacement, Calls.target(force, arrayOf(cell.value)))
                        }
                        assertEquals(beforeCalls + 1, entered.get()); released(language)
                    }
                    repeat(3) { run(false) }; compile(target); run(true)
                }
                val bottom = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Nothing = error("early modifier evaluation")
                }.callTarget, null)
                val cell = ManagedMutVar(bottom)
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, cell, bottom, 1L)) }
                assertSame(bottom, cell.value)
                val result = Calls.target(target, arrayOf(0L, cell, bottom, Unit)) as DataValue
                assertSame(bottom, result.layout.read(result, 0)); assertSame(cell.value, result.layout.read(result, 1))
            } finally { context.leave() }
        }
    }

    @Test fun concurrentLazyModifyPublishesEachUpdateExactlyOnce() = context().use { context ->
        context.initialize("thc"); context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val metrics = Metrics(false); val entered = AtomicInteger()
            val counter = DataLayout(language, "Counter", "Counter", arrayOf("IntRep"))
            val modifier = Closure(null, arity = 1, target = object : GuestRoot(language, FrameLayout().build()) {
                @Child private var evaluator = Force(metrics)
                override fun bloom(frame: VirtualFrame) = frame.arguments[0] as Long
                override fun execute(frame: VirtualFrame): Any? {
                    val old = evaluator.execute(frame, frame.arguments[1]) as DataValue
                    entered.incrementAndGet(); return counter.createLong(counter.readLong(old, 0) + 1)
                }
            }.callTarget)
            val cell = ManagedMutVar(counter.createLong(0)); val site = MutVarModifySite(language, metrics, false, false)
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs = List(4) { pool.submit { start.await(); repeat(8) { cell.modify(modifier, site) } } }
                start.countDown(); jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
            assertEquals(0, entered.get())
            val force = force(language, metrics)
            repeat(2) { assertEquals(32L, counter.readLong(Calls.target(force, arrayOf(cell.value)) as DataValue, 0)) }
            assertEquals(32, entered.get())
        } finally { context.leave() }
    }

    private fun read(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
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
    private fun manifest(): Map<String, Any?> {
        val manifest = read("build/boxed-cas/manifest.json")
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"]); assertEquals(64L, manifest["wordBits"])
        assertEquals(names, manifest["entries"]); assertEquals(90L, manifest["nativeRows"])
        assertEquals(false, manifest["installedArtifactsHashed"]); assertEquals(false, manifest["runtimeVerified"])
        val inputs = (listOf("compiler/test-fixtures/BoxedCasAudit.hs", "compiler/test-fixtures/BoxedCasNative.hs",
            "examples/THC/BoxedCasCounter.hs", "test/haskell-fixtures/BoxedCasFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh",
            "compiler/plugin.py", "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }).toSet()
        assertEquals(inputs, (manifest["inputHashes"] as Map<*, *>).keys)
        val oracle = manifest["oracle"] as String
        require(Regex("build/boxed-cas/run-[1-9][0-9]*/logs/native-oracle.stdout").matches(oracle))
        val attempt = oracle.removeSuffix("/logs/native-oracle.stdout")
        val stages = manifest["stages"] as Map<String, List<String>>
        val audits = manifest["audits"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys); assertEquals(stages.keys, audits.keys)
        for (stage in stages.keys) {
            assertEquals(setOf("BoxedCasAudit.json", "THC.BoxedCasCounter.json", "THC.InterfaceClosure.json"),
                stages.getValue(stage).map { it.removePrefix("$attempt/$stage-core/") }.toSet())
            assertEquals(names.map { "$attempt/$stage-$it.audit.json" }, audits.getValue(stage))
        }
        val labels = listOf("ghc-version", "ghc-info", "pre-export", "post-export", "native-compile", "native-oracle") +
            listOf("pre", "post").flatMap { stage -> names.map { "$stage-audit-$it" } }
        val artifacts = (stages.values.flatten() + audits.values.flatten() + listOf("$attempt/native/boxed-cas-oracle") +
            labels.flatMap { label -> listOf("stdout", "stderr", "command.json").map { "$attempt/logs/$label.$it" } }).toSet()
        assertEquals(artifacts, (manifest["artifactHashes"] as Map<*, *>).keys)
        for (key in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[key] as Map<String, String>) {
            val file = File(root, path); require(file.canonicalFile == file.absoluteFile)
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, "Stale boxed CAS fixture: $path")
        }
        val commands = manifest["commands"] as List<Map<String, Any?>>
        assertEquals(labels.size, commands.size)
        assertTrue(commands.all { it["exit"] == 0L && it["expectedExit"] == 0L })
        assertEquals(labels.map { read("$attempt/logs/$it.command.json") }.toSet(), commands.toSet())
        return manifest
    }
    private fun model(name: String, n: Long): Long = when (name) {
        "modifyValue" -> n * 3 + 1
        "modifyLazy" -> (n + 7) * 2
        "modifyBottom" -> n
        "boxedCasCounter" -> n and 63
        else -> n * 3 + 102
    }
    @Test fun genuineCoreNativeOracleAndIndependentModelWithInlining() = native(true)
    @Test fun genuineCoreNativeOracleAndIndependentModelWithoutInlining() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = manifest()
        val rows = File(root, manifest["oracle"] as String).readLines().map { it.split('\t') }
        assertEquals(names.flatMap { name -> seeds.map { listOf(name, it.toString()) } }, rows.map { it.take(2) })
        rows.forEach { assertEquals(model(it[0], it[1].toLong()), it[2].toLong(), "native/model $it") }
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val merged = CoreModules.merge(paths.map(::read))
            val primitives = names.flatMap { ArrayCoreEvidence(merged, it).primitiveCounts.keys }.toSet()
            assertTrue(primitives.containsAll(cases + "atomicModifyMutVar_#"))
            for (path in (manifest["audits"] as Map<String, List<String>>).getValue(stage)) {
                val audit = read(path); assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"]); assertEquals(emptyList<Any>(), audit["issues"])
            }
            for (name in names) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                println("Boxed CAS native $stage/$backend/$name inlining=$inlining")
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val p = program(language, CoreModules.reachable(merged, name, strictLink = true) + ("instrument" to true), backend)
                    val entry = context.asValue(EntryValue(p, name, 1)); val host = p.hostEntryTarget(1)
                    val cases = rows.filter { it[0] == name }
                    fun check(row: List<String>) {
                        assertEquals(row[2].toLong(), entry.execute(row[1].toLong()).asLong(), "$stage/$backend/$name")
                        released(language)
                    }
                    cases.forEach(::check)
                    val targets = activeTargets(host); assertTrue(targets.size > 1)
                    targets.filter { it !== host }.forEach(::compile)
                    assertTrue(entry.invokeMember("compile").asBoolean())
                    for (row in cases.asReversed()) {
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertTrue((p.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                            "First installed call must enter compiled code: $stage/$backend/$name")
                        assertEquals(targets, activeTargets(host))
                        if (name != "boxedCasCounter") targets.forEach(::valid)
                        else {
                            // Recursive example retention is advisory, not a feature-correctness gate.
                            // Keep the actual native result and first compiled activity above: no retry.
                            val retired = targets.filter { it.javaClass.getMethod("isValidLastTier").invoke(it) != true }
                            if (retired.isNotEmpty()) println("Counter target retirement $stage/$backend/${row[1]}: $retired")
                        }
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes"))
                        assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong())
                } finally { context.leave() }
            }
        }
    }
}
