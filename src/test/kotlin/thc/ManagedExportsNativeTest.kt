// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class ManagedExportsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/interface-core")
    private val core = File(directory, "typed-foreign-exports/managed.json")
    private val unit = "thc-interface-fixture-0.1"
    private val module = "ForeignExportManaged"
    private fun verifiedFile(file: File): String {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals((manifest["artifactHashes"] as Map<String, String>)[file.relativeTo(root).path], digest)
        return file.readText()
    }
    private fun original(): Map<String, Any?> {
        assertFalse(File(directory, "typed-export-source/$module.hs").exists())
        return Json.parse(verifiedFile(core)) as Map<String, Any?>
    }
    private fun request(source: Map<String, Any?>, backend: String = "ast") = mapOf(
        "mode" to "managed-exports", "modules" to listOf(source), "strictLink" to true, "backend" to backend)
    private fun exports(namespace: Value) = namespace.getMember(unit).getMember(module)
    private fun load(context: Context, backend: String): Value {
        original()
        return exports(loadManagedExports(context, listOf(core.absolutePath), backend))
    }
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("compiler.Inlining", "false").build()

    @Test fun genuineRetainedExportsMatchNativeAndShareTheirProgramAndCaf() {
        val native = verifiedFile(File(directory, "logs/managed-export-native-oracle.stdout")).trim().lines()
        assertEquals(listOf("-18", "2.25", "-1.5", "17", "3", "7", "9223372036854775828"), native)
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            val symbols = load(context, backend)
            assertEquals(setOf("thc_add_one", "thc_add_alias", "thc_float", "thc_double", "thc_word64",
                "thc_constant", "thc_next", "thc_next_alias", "thc_unit"), symbols.memberKeys)
            assertEquals(native[0].toInt(), symbols.getMember("thc_add_one").execute(-19).asInt())
            assertEquals(native[0].toInt(), symbols.getMember("thc_add_alias").execute(-19).asInt())
            assertEquals(native[1].toFloat(), symbols.getMember("thc_float").execute(1.25f).asFloat())
            assertEquals(native[2].toDouble(), symbols.getMember("thc_double").execute(-3.5).asDouble())
            assertEquals(native[3].toInt(), symbols.getMember("thc_constant").execute().asInt())
            val upper = BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(19))
            assertEquals(BigInteger(native[6]), symbols.getMember("thc_word64").execute(upper).asBigInteger())
            assertEquals(native[4].toInt(), symbols.getMember("thc_next").execute(3).asInt())
            assertEquals(native[5].toInt(), symbols.getMember("thc_next_alias").execute(4).asInt())
            assertTrue(symbols.getMember("thc_unit").execute().isNull)
            assertEquals(symbols.memberKeys, context.getBindings("thc").getMember(unit).getMember(module).memberKeys)
            context.enter()
            try {
                val registry = Language.currentState().managedExports
                val interop = InteropLibrary.getUncached()
                val raw = interop.readMember(interop.readMember(registry.scope, unit), module)
                val first = interop.readMember(raw, "thc_next") as ManagedExportValue
                val second = interop.readMember(raw, "thc_next_alias") as ManagedExportValue
                assertSame(first.program, second.program)
                assertEquals(0L, first.program.diagnostics()["unsupportedTraps"])
                val language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().arguments.depth)
            } finally { context.leave() }
        }
    }

    @Test fun exactArchiveAdmissionAndOldKernelRejectionStaySeparate() {
        val source = original()
        ManagedExportPlan.read(request(source))
        assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(source)) }
        fun reject(changed: Map<String, Any?>) {
            assertThrows(RuntimeException::class.java) { ManagedExportPlan.read(request(changed)) }
        }
        val proof = source["staticForeignExportRegistration"] as Map<String, Any?>
        val product = source["foreign"] as Map<String, Any?>
        val stubs = product["stubs"] as Map<String, Any?>
        reject(source + ("staticForeignExportRegistration" to (proof + ("schema" to 1L))))
        reject(source + ("staticForeignExportRegistration" to (proof + ("status" to "unclassified"))))
        reject(source + ("staticForeignExportRegistration" to (proof + ("roots" to emptyList<Any>()))))
        reject(source + ("foreign" to (product + ("stubs" to (stubs + ("source" to "changed"))))))
        val noInitializers = stubs + ("initializers" to emptyList<Any>())
        reject(source + ("foreign" to (product + ("stubs" to noInitializers))))
        val inventory = source["staticForeignExports"] as Map<String, Any?>
        val declarations = inventory["exports"] as List<Map<String, Any?>>
        val renamed = listOf(declarations.first() + ("symbol" to "different_alias")) + declarations.drop(1)
        reject(source + ("staticForeignExports" to (inventory + ("exports" to renamed))))
        reject(source + ("unit" to "another-unit"))
        for (variant in listOf("signatures", "foreign-file", "instrumented")) {
            val rejected = Json.parse(verifiedFile(File(directory, "typed-foreign-exports/$variant.json"))) as Map<String, Any?>
            reject(rejected)
        }
    }

    @Test fun hostValidationPrecedesIoAndFailedLoadsDoNotPublish() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            val source = original()
            val proof = source["staticForeignExportRegistration"] as Map<String, Any?>
            assertThrows(RuntimeException::class.java) { context.eval("thc", Json.stringify(request(
                source + ("staticForeignExportRegistration" to (proof + ("schema" to 1L))), backend))) }
            assertTrue(context.getBindings("thc").memberKeys.isEmpty())
            val symbols = load(context, backend)
            val next = symbols.getMember("thc_next")
            for (arguments in listOf(emptyArray(), arrayOf<Any>(1, 2), arrayOf<Any>(1L shl 32), arrayOf<Any>(1.5), arrayOf<Any>("3")))
                assertThrows(RuntimeException::class.java) { next.execute(*arguments) }
            assertEquals(1, next.execute(1).asInt(), "Rejected arguments must not mutate the original CAF")
            assertThrows(RuntimeException::class.java) { load(context, backend) }
            assertEquals(2, symbols.getMember("thc_next_alias").execute(1).asInt())
        }
    }

    @Test fun cachedLoadSourceStillCreatesContextOwnedProgramsAndRejectsForeignOrClosedValues() {
        val request = Source.newBuilder("thc", Json.stringify(request(original())), "managed-exports").cached(true).buildLiteral()
        Engine.newBuilder().build().use { engine ->
            val first = Context.newBuilder("thc").engine(engine).build()
            val second = Context.newBuilder("thc").engine(engine).build()
            var raw: Any? = null
            try {
                val a = exports(first.eval(request)).getMember("thc_next")
                val b = exports(second.eval(request)).getMember("thc_next_alias")
                assertEquals(3, a.execute(3).asInt())
                assertEquals(4, b.execute(4).asInt(), "A second context must own a fresh original CAF")
                first.enter()
                try { raw = Language.currentState().managedExports.scope } finally { first.leave() }
                second.enter()
                try { assertThrows(RuntimeFault::class.java) { InteropLibrary.getUncached().getMembers(raw) } }
                finally { second.leave() }
                first.close()
                assertThrows(RuntimeException::class.java) { a.execute(1) }
                assertThrows(RuntimeFault::class.java) { InteropLibrary.getUncached().getMembers(raw) }
                assertEquals(5, b.execute(1).asInt())
            } finally { first.close(); second.close() }
        }
    }

    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val found = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(root)
            nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }.forEach(::visit)
            found.add(target)
        }
        visit(entry)
        return found
    }

    @Test fun firstInstalledPublicInvocationKeepsTypedPureAndIoResults() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            val symbols = load(context, backend)
            for ((name, initial) in listOf("thc_add_one" to 0, "thc_next" to 0)) {
                val callable = symbols.getMember(name)
                repeat(4) { callable.execute(initial) }
                context.enter()
                try {
                    val interop = InteropLibrary.getUncached()
                    val scope = Language.currentState().managedExports.scope
                    val raw = interop.readMember(interop.readMember(interop.readMember(scope, unit), module), name) as ManagedExportValue
                    val targets = (targets(raw.guestTarget) + raw.ioTarget?.let(::targets).orEmpty()).distinct()
                    val cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    targets.forEach { target ->
                        cls.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, cls.getMethod("isValidLastTier").invoke(target))
                    }
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode", cls).invoke(runtime, raw.guestTarget)
                    val before = (raw.program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(if (name == "thc_add_one") 8 else 7, callable.execute(7).asInt())
                    assertTrue((raw.program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                        "$backend/$name must enter installed guest code on the first public invocation")
                    assertEquals(0L, raw.program.diagnostics()["unsupportedTraps"])
                } finally { context.leave() }
            }
        }
    }
}
