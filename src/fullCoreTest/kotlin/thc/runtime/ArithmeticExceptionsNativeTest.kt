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
    private fun context(): Context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
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
        val hashes = listOf(manifest["inputHashes"], manifest["artifactHashes"],
            (manifest["native"] as Map<*, *>)["artifactHashes"])
        for (record in hashes) for ((path, expected) in record as Map<*, *>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale arithmetic exception fixture: $path")
        }
        assertEquals(entries, manifest["entries"])
        val rows = File(root, manifest["oracle"] as String).readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(42, rows.values.sumOf { it.size })
        @Suppress("UNCHECKED_CAST")
        val stages = manifest["stages"] as Map<String, String>
        val installedText = StringBuilder()
        val targetLayout = CorePackageManifest.appendModules(installedText,
            File(root, manifest["packageManifest"] as String).path)
        assertNotNull(targetLayout, "Original installed RTS target layout is required")
        val originals = Json.parse("[$installedText]") as List<Map<String, Any?>>
        for ((stage, path) in stages) {
            @Suppress("UNCHECKED_CAST")
            val module = CoreModules.merge(originals +
                listOf(Json.parse(File(root, path).readText()) as Map<String, Any?>)) + ("targetLayout" to targetLayout!!)
            // The plugin also discovers RTS-only dependencies in thin interface
            // closures. Complete installed Core supplies the executable bodies.
            val closure = Json.parse(File(directory, "$stage/core/THC.InterfaceClosure.json").readText()) as Map<*, *>
            val discovered = (closure["bindings"] as List<*>).map { (it as Map<*, *>)["id"] } +
                (closure["missingDefinitions"] as List<*>).map { (it as Map<*, *>)["id"] }
            for (name in listOf("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#"))
                assertTrue(CoreArithmeticExceptions.payload(name) in discovered, "$stage discovers the original implicit $name payload")
            for (backend in listOf("ast", "bytecode")) for (entry in entries)
                for (cold in listOf(false, true)) context().use { context ->
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
                    val label = "$stage/$backend/$entry/${if (cold) "cold" else "profiled"}"
                    // The cold scenario first encounters exceptions in installed
                    // guest code. The separate profiled scenario must preserve
                    // that code on the first compiled throw, without recompiling.
                    repeat(5) {
                        val warm = if (cold) selected.filter { it.first != 0L } else selected
                        for ((input, expected) in warm) assertEquals(expected, function.execute(input).asLong(), label)
                    }
                    val body = program.entryTarget(entry + "Body")
                    val active = targets(program.entryTarget(entry))
                    assertTrue(active.any { it === body || it.rootNode.name.contains(entry + "Body") },
                        "$label observes the retained primitive body in the actual guest call graph")
                    active.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    active.forEach { assertTrue(valid(it), "$label remains installed before its first raise") }
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(model(entry, 0), function.execute(0L).asLong(), "$label first installed raise")
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= 2,
                        "$label entered the retained compiled guest call chain")
                    if (!cold) active.forEach {
                        assertTrue(valid(it), "$label first installed throw retains compiled ${it.rootNode.name}")
                    }
                    for ((input, expected) in selected) assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"], label)
                    assertEquals(0L, program.diagnostics()["blackholes"], label)
                } finally { context.leave() }
            }
        }
    }

}
