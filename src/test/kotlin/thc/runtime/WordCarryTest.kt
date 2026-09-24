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
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class WordCarryTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("addWordC", "subWordC", "addWordCall", "subWordCall")
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
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
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }

    private data class Row(val name: String, val x: Long, val y: Long, val first: Long, val flag: Long)
    private fun module(stage: String) = Json.parse(File(root,
        "build/tuple-arithmetic/$stage-core/TupleArithmeticAudit.json").readText()) as Map<String, Any?>
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun rows(): List<Row> {
        val manifest = Json.parse(File(root, "build/tuple-arithmetic/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale carry/borrow fixture: $path")
        }
        val modulus = BigInteger.ONE.shiftLeft(64)
        val rows = listOf("oracle.tsv", "call-oracle.tsv").flatMap { name ->
            File(root, "build/tuple-arithmetic/$name").readLines().map { line ->
                val p = line.split('\t'); Row(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong())
            }.filter { it.name in entries }
        }
        assertEquals(entries.toSet(), rows.map { it.name }.toSet())
        assertEquals(rows.size, rows.map { Triple(it.name, it.x, it.y) }.toSet().size)
        for (row in rows) {
            val x = BigInteger.valueOf(row.x).mod(modulus); val y = BigInteger.valueOf(row.y).mod(modulus)
            val result = if (row.name.startsWith("add")) x + y else x - y
            assertEquals(result.toLong(), row.first, "Wrapped field: $row")
            assertEquals(if (result.signum() < 0 || result >= modulus) 1L else 0L, row.flag, "Full-width flag: $row")
        }
        return rows
    }
    @Test fun nativeMixedWordIntFieldsWithInlining() = native(true)
    @Test fun nativeMixedWordIntFieldsAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val rows = rows()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) for (name in entries)
            context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val selected = rows.filter { it.name == name }
                    val audit = Json.parse(File(root, "build/tuple-arithmetic/$stage-$name.audit.json").readText()) as Map<String, Any?>
                    assertEquals(true, audit["accepted"])
                    if (name.endsWith("Call")) assertTrue((audit["reachableBindings"] as List<Map<String, Any?>>)
                        .any { it["id"] == "main:TupleArithmeticAudit." + name.replace("Call", "Result") })
                    val program = program(language, CoreModules.reachable(module(stage), name) + ("instrument" to true), backend)
                    val function = context.asValue(EntryValue(program, name, 3))
                    val host = program.hostEntryTarget(3); val original = program.entryTarget(name)
                    val label = "$stage/$backend/$name/inlining=$inlining"
                    fun check(row: Row, field: Long) {
                        assertEquals(if (field == 0L) row.first else row.flag, function.execute(row.x, row.y, field).asLong(), "$label/$row/$field")
                        released(language)
                    }
                    for (row in selected) for (field in 0L..1L) check(row, field)
                    val targets = activeTargets(host)
                    assertTrue(targets.size > 1, "$label adopted guest path")
                    targets.filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    val allocations = language.handoffState.get().results.allocations
                    val expectedEntries = if (name.endsWith("Call")) 2L else 1L
                    for (row in selected.asReversed()) for (field in 0L..1L) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row, field)
                        assertEquals(expectedEntries, (program.diagnostics().getValue("compiledEntries") as Number).toLong()-before, "$label exact installed entries")
                        assertEquals(targets, activeTargets(host), "$label active target identities")
                        valid(original, "$label original"); targets.forEach { valid(it, "$label active") }
                    }
                    assertEquals(allocations, language.handoffState.get().results.allocations, "$label result slabs reused")
                    if (!name.endsWith("Call")) assertEquals(0L, allocations, "$label direct primitive needs no carrier")
                    for (counter in listOf("unsupportedTraps", "blackholes"))
                        assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                    println("WordCarry PASS $label pairs=${selected.size} comparisons=${selected.size*2} entriesPerCall=$expectedEntries")
                } finally { context.leave() }
            }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun mixedResultFieldOrderSignednessAndLogicalShapeCannotBeForged() {
        val shapes = listOf(
            listOf("IntRep", "WordRep"), listOf("WordRep", "WordRep"), listOf("IntRep", "IntRep"),
            listOf("WordRep", "Int64Rep"), listOf("Word64Rep", "IntRep"))
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in listOf("addWordC", "subWordC")) for (shape in shapes) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(module(stage), name)
                    val app = applications(module).single { (it[1] as List<*>).take(2) == listOf("prim", name+"#") }
                    val rep = (CoreRepresentations.metadata(app)!!["rep"] as MutableMap<String, Any?>)
                    rep["primReps"] = shape
                    (rep["components"] as List<MutableMap<String, Any?>>).forEachIndexed { i, component -> component["primReps"] = listOf(shape[i]) }
                    val error = assertThrows(RuntimeFault::class.java) { program(language, module + ("diagnosticUnsupported" to diagnostic), backend) }
                    assertTrue(error.message.orEmpty().contains("Tuple primitive result representation mismatch"), error.message)
                }
                for (name in listOf("addWordC", "subWordC")) for (variant in listOf("nested", "state", "unknown")) {
                    val module = CoreModules.reachable(module(stage), name)
                    val app = applications(module).single { (it[1] as List<*>).take(2) == listOf("prim", name+"#") }
                    val rep = CoreRepresentations.metadata(app)!!["rep"] as MutableMap<String, Any?>
                    val components = rep["components"] as MutableList<Map<String, Any?>>
                    val flag = components[1]
                    components[1] = when (variant) {
                        "nested" -> mapOf("kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-tuple",
                            "primReps" to listOf("IntRep"), "components" to listOf(flag))
                        "state" -> mapOf("kind" to "void", "evaluated" to true, "primReps" to emptyList<String>())
                        else -> flag + ("kind" to "unknown")
                    }
                    rep["primReps"] = if (variant == "state") listOf("WordRep") else listOf("WordRep", "IntRep")
                    assertThrows(RuntimeFault::class.java, { program(language, module, backend) }, "$stage/$backend/$name/$variant flag")
                }
            } finally { context.leave() }
        }
    }
    @Test fun bothOperandsCompleteBeforeEitherDestinationIsWritten() {
        val descriptor = FrameDescriptor.newBuilder()
        val first = descriptor.addSlot(FrameSlotKind.Long, "first", null)
        val second = descriptor.addSlot(FrameSlotKind.Long, "flag", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor.build())
        val proof = CoreRepresentations.parse(mapOf("aggregate" to "unboxed-tuple", "kind" to "unknown", "evaluated" to true,
            "primReps" to listOf("WordRep", "IntRep"), "components" to listOf("WordRep", "IntRep").map {
                mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf(it)) }))
        for (operation in listOf(TupleArithmeticOp.ADD_WORD_C, TupleArithmeticOp.SUB_WORD_C)) {
            val events = mutableListOf<Int>()
            fun operand(position: Int, fail: Boolean) = object : Expr() {
                override fun execute(frame: VirtualFrame): Any { events.add(position); if (fail) throw RuntimeFault("operand failed"); return 1L }
            }
            frame.setLong(first, 42L); frame.setLong(second, 43L)
            val failing = TupleArithmeticExpression(operation, proof, operand(0, false), operand(1, true))
            assertThrows(RuntimeFault::class.java) { failing.executeTuple(frame, intArrayOf(first, second), 0) }
            assertEquals(listOf(0, 1), events); assertEquals(42L, frame.getLong(first)); assertEquals(43L, frame.getLong(second))
            events.clear()
            TupleArithmeticExpression(operation, proof, operand(0, false), operand(1, false)).executeTuple(frame, intArrayOf(first, second), 0)
            assertEquals(listOf(0, 1), events); assertEquals(if (operation == TupleArithmeticOp.ADD_WORD_C) 2L else 0L, frame.getLong(first))
            assertEquals(0L, frame.getLong(second))
        }
    }
}
