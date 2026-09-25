// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
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

class ArithmeticExceptionsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/arithmetic-exceptions")
    private val entries = listOf("scalarDivZero", "scalarOverflow", "scalarUnderflow", "tupleDivZero", "tupleOverflow", "tupleUnderflow")
    private fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target), "Installed last-tier code required")
    }
    private fun model(entry: String, x: Long): Long {
        val kind = when { entry.endsWith("DivZero") -> 1; entry.endsWith("Overflow") -> 2; else -> 3 }
        return if (x == 0L) 100L + kind else x + (if (entry.startsWith("scalar")) 10L else 20L) + kind
    }
    private fun context(): Context = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", "false")
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", "Throw").build()
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

    @Test fun originalSomeExceptionsMatchNativeIncludingFirstCompiledColdRaise() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<*, *>
        for (key in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[key] as Map<*, *>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale arithmetic exception fixture: $path")
        }
        assertEquals(entries, manifest["entries"])
        val rows = File(root, manifest["oracle"] as String).readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(42, rows.values.sumOf { it.size })
        @Suppress("UNCHECKED_CAST")
        val stages = manifest["stages"] as Map<String, List<String>>
        for ((stage, paths) in stages) {
            @Suppress("UNCHECKED_CAST")
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            // The plugin also discovers RTS-only dependencies in thin interface
            // closures. Actual source exports supply any missing executable bodies.
            val closure = Json.parse(File(directory, "$stage/core/THC.InterfaceClosure.json").readText()) as Map<*, *>
            val discovered = (closure["bindings"] as List<*>).map { (it as Map<*, *>)["id"] } +
                (closure["missingDefinitions"] as List<*>).map { (it as Map<*, *>)["id"] }
            for (name in listOf("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#"))
                assertTrue(CoreArithmeticExceptions.payload(name) in discovered, "$stage discovers the original implicit $name payload")
            for (backend in listOf("ast", "bytecode")) for (entry in entries) context().use { context ->
                val selected = rows.getValue(entry).map { it[1].toLong() to it[2].toLong() }
                assertEquals(listOf(Long.MIN_VALUE, -17L, -1L, 0L, 1L, 17L, Long.MAX_VALUE), selected.map { it.first })
                val audit = Json.parse(File(directory, "$stage/$entry-audit.json").readText()) as Map<*, *>
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                val expectedPrimitive = "raise" + entry.removePrefix("scalar").removePrefix("tuple") + "#"
                assertTrue((audit["primitives"] as List<*>).any { (it as Map<*, *>)["name"] == expectedPrimitive },
                    "$stage/$entry retains the intended primitive")
                for ((x, y) in selected) assertEquals(model(entry, x), y, "native/$entry/$x")
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, entry, true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val function = context.asValue(EntryValue(program, entry, 1))
                    val label = "$stage/$backend/$entry"
                    // No arithmetic exception or its handler has run before this
                    // installation. The first invocation of installed leaf code
                    // raises through the original SomeException -> ArithException.
                    repeat(40) { assertEquals(model(entry, 17), function.execute(17L).asLong(), label) }
                    val body = program.entryTarget(entry + "Body")
                    val active = targets(program.entryTarget(entry))
                    assertTrue(active.any { it === body || it.rootNode.name.contains(entry + "Body") },
                        "$label observes the retained primitive body in the actual guest call graph")
                    active.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    active.forEach { assertTrue(valid(it), "$label remains installed before its first raise") }
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(model(entry, 0), function.execute(0L).asLong(), "$label first installed raise")
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                        "$label entered compiled guest code")
                    for ((input, expected) in selected) assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"], label)
                    assertEquals(0L, program.diagnostics()["blackholes"], label)
                } finally { context.leave() }
            }
        }
    }

    @Test fun implicitPayloadIsLazyAndFailureMemoizationSharesItInBothBackends() {
        val boxed = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
        val empty = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "aggregate" to "unboxed-tuple",
            "components" to emptyList<Any>(), "evaluated" to true)
        for (name in listOf("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#")) for (backend in listOf("ast", "bytecode")) {
            val payloadId = CoreArithmeticExceptions.payload(name)!!
            fun binding(id: String, expression: List<Any?>) = mapOf("id" to id, "name" to id, "type" to "SomeException",
                "lifted" to true, "arity" to 0, "rep" to boxed, "expr" to expression)
            // This deliberate bottom is a strictness control only; the native
            // fixture above validates the actual exported exception dictionaries.
            val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Test.ArithmeticLaziness",
                "bindings" to listOf(binding(payloadId, listOf("var", payloadId, mapOf("rep" to boxed))),
                    binding("entry", listOf("app", listOf("prim", name),
                        listOf(listOf("con", "Empty", 0, mapOf("rep" to empty))), listOf(false), false, false, mapOf("rep" to boxed)))),
                "constructors" to listOf(mapOf("id" to "Empty", "name" to "(# #)", "arity" to 0,
                    "kind" to "unboxed-tuple", "tag" to 1, "strictFields" to emptyList<Boolean>(),
                    "fieldLifted" to emptyList<Boolean>(), "fieldReps" to emptyList<List<String>>())))
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, "entry", true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val payload = program.entryValue(payloadId) as Thunk
                    fun invoke() = Calls.target(program.hostEntryTarget(), arrayOf(program.entryValue("entry"), emptyArray<Any?>()))
                    val first = assertThrows(GuestException::class.java) { invoke() }
                    val second = assertThrows(GuestException::class.java) { invoke() }
                    assertNotSame(first, second)
                    assertSame(payload, first.payload)
                    assertSame(first.payload, second.payload)
                    assertEquals(0, payload.state, "$backend/$name never enters its implicit exception CAF")
                    assertEquals(1L, program.diagnostics()["thunkEvaluations"])
                    assertEquals(0L, program.diagnostics()["blackholes"])
                } finally { context.leave() }
            }
        }
    }
}
