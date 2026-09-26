// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.Json
import thc.Language

/** Exact retained GHC foreign declarations, with explicit synthetic callers. */
@Timeout(30)
class CpuAffinityApiTest {
    private val state = scalar("void")
    private val integer = scalar("long", "IntRep")
    private val cInt = scalar("long", "Int32Rep")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private val boxed = scalar("data", "BoxedRep (Just Lifted)")
    private val reference = scalar("object", "BoxedRep (Just Unlifted)")
    private fun scalar(kind: String, rep: String? = null) = mapOf("kind" to kind,
        "primReps" to if (rep == null) emptyList<String>() else listOf(rep), "evaluated" to true)
    private fun tuple(vararg fields: Map<String, Any?>) = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "components" to fields.toList(), "primReps" to fields.flatMap { it["primReps"] as List<*> }, "evaluated" to true)
    private fun variable(id: String, rep: Map<String, Any?>) = listOf("var", id, mapOf("rep" to rep))
    private fun binder(id: String, rep: Map<String, Any?>) = mapOf("id" to id, "lifted" to (rep == closure || rep == boxed), "rep" to rep)
    private fun application(head: List<Any?>, arguments: List<List<Any?>>, flags: List<Boolean>,
        rep: Map<String, Any?>, foreign: Map<String, Any?>? = null): List<Any?> = listOf("app", head, arguments,
        flags, false, false, mapOf("rep" to rep) + if (foreign == null) emptyMap() else mapOf("foreignCall" to foreign))
    private fun binding(name: String, args: List<Map<String, Any?>>, body: List<Any?>, rep: Map<String, Any?>) =
        mapOf("id" to name, "name" to name, "arity" to args.size, "lifted" to true, "rep" to closure,
            "expr" to listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to rep)))
    private fun tupleCase(value: List<Any?>, first: String, second: String, secondRep: Map<String, Any?>,
        body: List<Any?>, result: Map<String, Any?>): List<Any?> = listOf("case", value, "pair-$first", listOf(
            listOf("data", "tuple2", listOf(first, second), body, mapOf("binders" to
                listOf(binder(first, state), binder(second, secondRep))))),
        mapOf("rep" to result, "binder" to binder("pair-$first", tuple(state, secondRep))))
    private fun descriptor(applied: Boolean): Map<String, Any?> =
        (Json.parse(javaClass.getResource("/core/cpu-affinity-descriptors.json")!!.readText()) as Map<String, Any?>)
            .getValue(if (applied) CoreCpuAffinity.APPLIED else CoreCpuAffinity.SUPPORT) as Map<String, Any?>
    private fun query(applied: Boolean, declaration: Map<String, Any?> = descriptor(applied)) =
        application(variable("foreign-query", closure), listOf(variable("s", state)), listOf(false),
            tuple(state, cInt), declaration)
    private fun module(declaration: Map<String, Any?>? = null): Map<String, Any?> {
        val support = tupleCase(query(false, declaration ?: descriptor(false)), "s1", "n", cInt, variable("n", cInt), cInt)
        val applied = tupleCase(query(true), "s1", "n", cInt, variable("n", cInt), cInt)
        val unit = listOf("con", "unit", 0, mapOf("rep" to boxed))
        val completed = application(listOf("con", "tuple2", 2, mapOf("rep" to closure)),
            listOf(variable("done", state), unit), listOf(false, true), tuple(state, boxed))
        val write = application(listOf("prim", "writeIntArray#"), listOf(variable("buffer", reference),
            listOf("lit", "int", "0", mapOf("rep" to integer)), variable("n", cInt), variable("s1", state)),
            listOf(false, false, false, false), state)
        val afterWrite = listOf("case", write, "done", listOf(listOf("default", null, emptyList<String>(), completed)),
            mapOf("rep" to tuple(state, boxed), "binder" to binder("done", state)))
        val childBody = tupleCase(query(true), "s1", "n", cInt, afterWrite, tuple(state, boxed))
        val action = listOf("lam", listOf(binder("s", state)), childBody,
            mapOf("rep" to closure, "resultRep" to tuple(state, boxed)))
        val fork = application(listOf("prim", "forkOn#"), listOf(variable("cap", integer), action, variable("parent-state", state)),
            listOf(false, true, false), tuple(state, reference))
        val forkBody = tupleCase(fork, "parent-next", "child", reference, variable("child", reference), reference)
        return mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "test", "module" to "CpuAffinitySynthetic",
            "instrument" to true, "bindings" to listOf(
                binding("support", listOf(binder("s", state)), support, cInt),
                binding("applied", listOf(binder("s", state)), applied, cInt),
                binding("fork", listOf(binder("cap", integer), binder("buffer", reference), binder("parent-state", state)), forkBody, reference)),
            "constructors" to listOf(
                mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1),
                mapOf("id" to "unit", "name" to "()", "kind" to "boxed", "arity" to 0, "tag" to 1,
                    "fieldReps" to emptyList<List<String>>(),
                    "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>())))
    }
    private fun context(native: Boolean = false) = Context.newBuilder("thc").allowCreateThread(true)
        .allowNativeAccess(native).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, backend: String, source: Map<String, Any?> = module()): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source, true)

    @Test fun queriesUseCurrentContextAndFirstInstalledEntriesOnBothBackends() {
        for (backend in listOf("ast", "bytecode")) for (native in listOf(false, true)) context(native).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val threads = Language.currentState().threads
                val guest = program(language, backend)
                threads.enterCurrent()
                try {
                    for ((entry, expected) in listOf("support" to threads.cpuAffinity.mode.ordinal.toLong(), "applied" to 0L)) {
                        val target = guest.entryTarget(entry)
                        assertEquals(expected, Calls.target(target, arrayOf(0L, Unit)))
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                        val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected, Calls.target(target, arrayOf(0L, Unit)))
                        assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    }
                    if (!native) assertEquals(CpuAffinityMode.UNAVAILABLE, threads.cpuAffinity.mode)
                    assertThrows(RuntimeFault::class.java) { Calls.target(guest.entryTarget("support"), arrayOf(0L, 1L)) }
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun actualForkQueriesItsOwnPublishedAcceptanceBeforeRunningChildBody() {
        for (backend in listOf("ast", "bytecode")) for (native in listOf(false, true)) context(native).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val guest = program(language, backend)
                val threads = Language.currentState().threads
                threads.enterCurrent()
                try {
                    for (capability in listOf(0L, -1L, Long.MAX_VALUE)) {
                        val buffer = ByteArray(8) { -1 }
                        val child = Calls.target(guest.entryTarget("fork"), arrayOf(0L, capability, buffer, Unit)) as GuestThreadId
                        child.carrier.get()?.let { it.join(5000); assertFalse(it.isAlive) }
                        assertEquals(GuestThreadStatus.FINISHED, threads.status(child))
                        assertEquals(if (child.affinityApplied) 1L else 0L, ManagedByteArray.readInt(buffer, 0))
                        if (!native) assertFalse(child.affinityApplied)
                        assertFalse(threads.currentIdentity().affinityApplied, "Child acceptance does not leak to parent")
                    }
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun exactForeignContractRejectsChangesAndShadowedIdentifiers() {
        val valid = descriptor(false)
        val badResult = tuple(state, integer)
        val variants = listOf(valid + ("safety" to "safe"), valid + ("convention" to "prim"),
            valid + ("arity" to 2), valid + ("resultRep" to badResult),
            valid + ("argumentReps" to listOf(integer)),
            valid + ("target" to ((valid["target"] as Map<String, Any?>) + ("isFunction" to false))))
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (bad in variants) assertThrows(RuntimeFault::class.java) { program(language, backend, module(bad)) }
                assertThrows(RuntimeFault::class.java) { CoreCpuAffinity.validate(query(false), true) }
                assertThrows(RuntimeFault::class.java) {
                    CoreCpuAffinity.validate(query(false).toMutableList().also { it[3] = listOf(true) }, false)
                }
            } finally { context.leave() }
        }
    }
}
