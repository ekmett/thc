// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
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

class TagToEnumTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build()
    private fun module(stage: String): Map<String, Any?> = CoreModules.merge(listOf("TagToEnumAudit", "TagToEnumExternal").map {
        Json.parse(File(root, "build/tag-to-enum/$stage-core/$it.json").readText()) as Map<String, Any?>
    })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target, "installed")
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
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        assertEquals(0, language.handoffState.get().arguments.depth)
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().arguments.retainedReferences())
        assertEquals(0, language.handoffState.get().results.retainedReferences())
    }
    private fun verifyEvidence() {
        val evidence = Json.parse(File(root, "build/tag-to-enum/provenance.json").readText()) as Map<String, Any?>
        val records = listOf("sources", "artifacts").flatMap { evidence[it] as List<Map<String, String>> }
        for (record in records) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, record.getValue("path")).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(record["sha256"], hash, "Stale tagToEnum evidence: ${record["path"]}")
        }
        for (stage in listOf("pre", "post")) assertEquals(true,
            (Json.parse(File(root, "build/tag-to-enum/$stage-audit.json").readText()) as Map<*, *>)["accepted"])
    }
    @Test fun nativeEnumsWithInlining() = native(true)
    @Test fun nativeEnumsAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        verifyEvidence()
        val rows = File(root, "build/tag-to-enum/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(29, rows.values.sumOf { it.size })
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) for ((name, selected) in rows) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = program(language, CoreModules.reachable(module(stage), name) + ("instrument" to true), backend)
                val function = context.asValue(EntryValue(program, name, 1)); val host = program.hostEntryTarget(1)
                val original = program.entryTarget(name); val label = "$stage/$backend/$name/inline=$inlining"
                fun check(row: List<String>) = assertEquals(row[2].toLong(), function.execute(row[1].toLong()).asLong(), "$label/${row[1]}")
                selected.forEach(::check)
                val targets = activeTargets(host)
                assertTrue(targets.size > 1, "$label actual adopted guest target")
                targets.filter { it !== host }.forEach(::compile)
                assertTrue(function.invokeMember("compile").asBoolean())
                for (row in selected.asReversed()) {
                    val before = count(program); check(row)
                    assertTrue(count(program) > before, "$label/${row[1]} actual compiled guest entry")
                    assertEquals(targets, activeTargets(host), "$label active target identities")
                    valid(original, "$label original"); targets.forEach { valid(it, "$label active") }
                    released(language)
                }
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
            } finally { context.leave() }
        }
    }
    private fun bindings(module: Map<String, Any?>) = module["bindings"] as MutableList<MutableMap<String, Any?>>
    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app" && (value.getOrNull(1) as? List<*>)?.take(2) == listOf("prim", "tagToEnum#")) listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun directEnumRootsUseTypedNullaryValuesAndEveryCompiledCallEntersExactlyOnce() {
        verifyEvidence()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in listOf("chooseBool", "chooseOrdering", "chooseColour", "chooseExternal")) {
                    val linked = CoreModules.reachable(module(stage), name)
                    val ids = ((applications(linked).single()[6] as Map<*, *>)["enumFamily"] as Map<*, *>)["constructors"] as List<String>
                    val p = program(language, linked + ("instrument" to true), backend)
                    val host = p.hostEntryTarget(1); val target = p.entryTarget(name)
                    fun call(tag: Long) = Calls.target(host, arrayOf(p.entryValue(name), arrayOf(tag))) as DataValue
                    val values = ids.indices.map { call(it.toLong()).also { v -> assertEquals(ids[it], v.layout.id); assertEquals(0, v.layout.arity) } }
                    compile(target); compile(host)
                    for (tag in ids.indices.reversed()) {
                        val before = count(p); assertSame(values[tag], call(tag.toLong()))
                        assertEquals(before + 1, count(p), "$stage/$backend/$name/$tag")
                        valid(target, name); valid(host, name)
                    }
                    for (tag in listOf(-1L, ids.size.toLong(), Long.MIN_VALUE, Long.MAX_VALUE, 1L shl 32, (1L shl 32) + 1)) {
                        val failure = assertThrows(RuntimeFault::class.java) { call(tag) }
                        assertTrue(failure.message!!.contains("tag out of range: $tag")); released(language)
                    }
                }
            } finally { context.leave() }
        }
    }
    @Test fun malformedOrUnsaturatedEnumProofsRejectAtLoad() {
        for (backend in listOf("ast", "bytecode")) for (variant in listOf("missing", "family", "empty", "reverse", "duplicate", "missing-con", "wrong-tag", "float-tag", "fields", "newtype", "truncated-family", "extra-family-member", "word", "unknown", "aggregate", "function-proof", "result", "lifted", "zero", "two", "bare")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module("pre"), "chooseBool")
                val app = applications(linked).single(); val metadata = app[6] as MutableMap<String, Any?>
                val family = metadata["enumFamily"] as MutableMap<String, Any?>
                val ids = family["constructors"] as MutableList<String>
                val cons = linked["constructors"] as MutableList<MutableMap<String, Any?>>
                val con = cons.single { it["id"] == ids[0] }
                when (variant) {
                    "missing" -> metadata.remove("enumFamily")
                    "family" -> family["typeConstructor"] = "Other"
                    "empty" -> ids.clear()
                    "reverse" -> ids.reverse()
                    "duplicate" -> ids[1] = ids[0]
                    "missing-con" -> cons.remove(con)
                    "wrong-tag" -> con["tag"] = 2L
                    "float-tag" -> con["tag"] = 1.0
                    "fields" -> con["fieldTypes"] = listOf(mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true))
                    "newtype" -> con["kind"] = "newtype"
                    "truncated-family" -> {
                        family["constructors"] = listOf(ids[0])
                        con["enumFamily"] = family.toMap()
                    }
                    "extra-family-member" -> cons.add(con.toMutableMap().apply { put("id", "Extra") })
                    "word" -> ((app[2] as List<List<Any?>>)[0][2] as MutableMap<String, Any?>)["rep"] = mapOf("kind" to "long", "primReps" to listOf("WordRep"), "evaluated" to true)
                    "unknown" -> app[2] = listOf(listOf("lit", "int", "0"))
                    "aggregate" -> metadata["rep"] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                    "function-proof" -> (app[1] as MutableList<Any?>)[2] = mapOf("rep" to emptyList<Any?>())
                    "result" -> metadata["rep"] = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
                    "lifted" -> app[3] = listOf(true)
                    "zero" -> { app[2] = emptyList<Any?>(); app[3] = emptyList<Boolean>() }
                    "two" -> { app[2] = (app[2] as List<Any?>) + (app[2] as List<Any?>); app[3] = listOf(false, false) }
                    "bare" -> (bindings(linked).single()["expr"] as MutableList<Any?>)[2] = app[1]
                }
                val failure = assertThrows(RuntimeException::class.java, { program(language, linked, backend) }, "$backend/$variant")
                assertTrue(failure is RuntimeFault || failure is UnsupportedCore, "$backend/$variant ${failure.javaClass}")
                if (variant == "truncated-family" || variant == "extra-family-member")
                    assertTrue(failure.message.orEmpty().contains("Contradictory family record"), "$backend/$variant ${failure.message}")
            } finally { context.leave() }
        }
    }
    @Test fun lexicalIntProofRefinesLegacyOrUnknownOccurrencesWithoutGuessingTheFamily() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (proof in listOf(null, mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false),
                    mapOf("kind" to "unknown", "primReps" to listOf("IntRep"), "evaluated" to false))) {
                    val linked = CoreModules.reachable(module("pre"), "chooseBool")
                    val app = applications(linked).single(); val old = (app[2] as List<List<Any?>>).single()
                    app[2] = listOf(old.take(2) + if (proof == null) emptyList() else listOf(mapOf("rep" to proof)))
                    val p = program(language, linked, backend)
                    val value = Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue("chooseBool"), arrayOf(1L))) as DataValue
                    assertEquals("True", value.layout.name)
                }
            } finally { context.leave() }
        }
    }
    @Test fun genuineParameterizedAndDataFamilyEnumsRemainRejected() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = Json.parse(File(root, "build/tag-to-enum/$stage-core/TagToEnumFrontier.json").readText()) as Map<String, Any?>
                for (entry in listOf("parameterized", "family")) {
                    val failure = assertThrows(RuntimeFault::class.java) { program(language, CoreModules.reachable(module, entry), backend) }
                    assertTrue(failure.message!!.contains("tagToEnum#"))
                }
            } finally { context.leave() }
        }
    }
    @Test fun typedTagOperandExecutesOnceBeforeSelectionAndFailure() = context().use { context ->
        context.initialize("thc"); context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val values = arrayOf(DataLayout(language, "A", "A", emptyArray()).allocate(), DataLayout(language, "B", "B", emptyArray()).allocate())
            var calls = 0; var tag = 1L; var fail = false
            val operand = object : Expr() {
                override fun execute(frame: VirtualFrame): Any = error("Object operand path must not execute")
                override fun executeLong(frame: VirtualFrame): Long { calls++; if (fail) throw RuntimeFault("tag operand failed"); return tag }
            }
            val node = TagToEnum(EnumFamily(values), operand)
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
            assertSame(values[1], node.executeDataValue(frame)); assertEquals(1, calls)
            tag = Long.MAX_VALUE; assertThrows(RuntimeFault::class.java) { node.executeDataValue(frame) }; assertEquals(2, calls)
            fail = true; val failure = assertThrows(RuntimeFault::class.java) { node.executeDataValue(frame) }
            assertEquals("tag operand failed", failure.message); assertEquals(3, calls)
        } finally { context.leave() }
    }
}
