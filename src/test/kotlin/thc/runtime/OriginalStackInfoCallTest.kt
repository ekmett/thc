// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language

/** Unchanged original FCall applications in synthetic scalar-result consumers. */
class OriginalStackInfoCallTest {
    private fun original() = Json.parse(javaClass.getResource("/core/original-stack-info-calls.json")!!.readText())
        as Map<String, Any?>
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun module(layout: Any? = StackInfoTestLayout.layout(), mutate: (MutableMap<String, Any?>) -> Unit = {}): Map<String, Any?> {
        val source = original()
        val bindings = (source.getValue("calls") as List<Map<String, Any?>>).flatMap { record ->
            val call = record.getValue("application") as List<Any?>
            val tuple = (call[6] as Map<String, Any?>).getValue("rep") as Map<String, Any?>
            val foreign = (call[6] as Map<String, Any?>).getValue("foreignCall") as Map<String, Any?>
            val symbol = (foreign.getValue("target") as Map<String, Any?>).getValue("symbol") as String
            val components = tuple["components"] as? List<Map<String, Any?>>
            val choices = if (symbol == "getInfoTableAddrszh") listOf(0, 1) else listOf(components?.lastIndex ?: 0)
            choices.map { choice ->
                val name = when (symbol) {
                    "getStackInfoTableAddrzh" -> "stack"
                    "getInfoTableAddrszh" -> if (choice == 0) "frame" else "key"
                    else -> "lookup"
                }
                val formals = (call[2] as List<List<Any?>>).map { argument ->
                    mapOf("id" to argument[1], "name" to argument[1], "lifted" to false,
                        "rep" to (argument.last() as Map<String, Any?>).getValue("rep"))
                }
                val result = components?.get(choice) ?: tuple
                val body = if (components == null) call else {
                    val ids = components.indices.map { "$name-result-$it" }
                    listOf("case", call, "$name-pair", listOf(listOf("data", "ghc-internal:GHC.Internal.Types.(#,#)",
                        ids, listOf("var", ids[choice], mapOf("rep" to result)), mapOf("binders" to components.mapIndexed { i, rep ->
                            mapOf("id" to ids[i], "lifted" to false, "rep" to rep)
                        }))), mapOf("rep" to result, "binder" to mapOf("id" to "$name-pair", "lifted" to false, "rep" to tuple)))
                }
                mutableMapOf<String, Any?>("id" to "test:OriginalStackInfo.$name", "name" to name,
                    "arity" to formals.size, "lifted" to true, "rep" to closure,
                    "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to result))).also(mutate)
            }
        }
        return mapOf("schema" to 1, "module" to "OriginalStackInfo", "unit" to "test", "ghc" to "9.14.1",
            "instrument" to true, "bindings" to bindings, "targetLayout" to layout,
            "constructors" to listOf(mapOf("id" to "ghc-internal:GHC.Internal.Types.(#,#)",
                "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)),
            "sourceFiles" to source.getValue("sourceFiles"), "sourceSpans" to source.getValue("sourceSpans"))
    }

    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun context(inlining: Boolean = false) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget, label: String) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private class Capture : Expr() {
        override fun execute(frame: VirtualFrame): Any = ManagedStackSnapshot.capture(this)
    }
    private class CaptureRoot(language: Language) : GuestRoot(language, FrameLayout().build()) {
        @field:Child private var body = Capture()
        override fun execute(frame: VirtualFrame): Any = body.execute(frame)
        override fun bloom(frame: VirtualFrame) = 0L
        override fun getName() = "stack-info-call-test"
    }

    @Test fun originalApplicationsExecuteInBothBackendsAndFirstInstalledCompiledEntries() {
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val snapshot = CaptureRoot(language).callTarget.call() as ManagedStackSnapshot
                val layout = StackInfoTestLayout.layout()
                val program = load(language, backend, module(layout))
                val targets = listOf("stack", "frame", "key", "lookup").associateWith(program::entryTarget)
                var compiled = false
                fun call(name: String, vararg arguments: Any?): Any? {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val target = targets.getValue(name)
                    val result = Calls.target(target, arrayOf(0L, *arguments))
                    if (compiled) {
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), name)
                        valid(target, "$backend/inlining=$inlining/$name")
                    }
                    released(language)
                    return result
                }
                fun exercise() {
                    val stack = call("stack", snapshot) as ManagedAddress
                    val standard = call("frame", snapshot, 0L) as ManagedAddress
                    val key = call("key", snapshot, 0L) as ManagedAddress
                    val typeByte = layout.offset("infoTableTypeOffset").toLong() + if (StackInfoTestLayout.endian == "little") 0 else 3
                    assertEquals(53L, stack.readWord8(typeByte))
                    assertEquals(30L, standard.readWord8(typeByte))
                    assertTrue(standard.plus(layout.offset("infoTableBytes").toLong()).sameLocation(key))
                    val storage = ManagedAllocation.mutable(89, 8)
                    val output = ManagedAddress.fromAllocation(storage).plus(5)
                    assertEquals(1L, call("lookup", key, output, Unit))
                    assertTrue(key.sameLocation(storage.readAddressByteOffset(5)))
                    assertEquals("THC managed diagnostic frame", storage.readAddressByteOffset(13).utf8())
                    val before = storage.readAddressByteOffset(13)
                    assertEquals(0L, call("lookup", ManagedAddress.nullAddress(), output, Unit))
                    assertSame(before, storage.readAddressByteOffset(13))
                }
                repeat(3) { exercise() }
                targets.forEach { (name, target) ->
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "$backend/inlining=$inlining/$name installation")
                }
                compiled = true
                exercise() // first call after installation; no settling or recompilation
                exercise()
                compiled = false
                val key = call("key", snapshot, 0L) as ManagedAddress
                val output = ManagedAddress.fromAllocation(ManagedAllocation.mutable(72, 8))
                assertThrows(RuntimeFault::class.java) { call("lookup", key, output, 9L) }
                assertThrows(RuntimeFault::class.java) { call("frame", snapshot, -1L) }
                released(language)
            } finally { context.leave() }
        }
    }

    @Test fun typedLayoutAndStoredOperandProofsAreRequiredBeforeExecution() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (layout in listOf(null, StackInfoTestLayout.document(), StackInfoTestLayout.layout(mapOf("tablesNextToCode" to false))))
                    assertThrows(RuntimeFault::class.java) { load(language, backend, module(layout)) }
                val bad = module { binding ->
                    val lambda = (binding["expr"] as List<Any?>).toMutableList()
                    val formals = (lambda[1] as List<Map<String, Any?>>).toMutableList()
                    formals[0] = formals[0] + ("rep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true))
                    lambda[1] = formals; binding["expr"] = lambda
                }
                assertThrows(RuntimeFault::class.java) { load(language, backend, bad) }
                released(language)
            } finally { context.leave() }
        }
    }
}
