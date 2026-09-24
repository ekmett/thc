// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.nio.ByteOrder
import java.security.MessageDigest

class IntArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("unboxedAccum", "unboxedST", "unboxedEmpty", "orderedInts", "aliasIntBytes")
    private val operations = listOf(ByteArrayOp.READ_INT, ByteArrayOp.WRITE_INT, ByteArrayOp.INDEX_INT)
    private fun manifest() = Json.parse(File(root, "build/int-arrays/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target), label)

    // A list/byte model independent of the native oracle and managed VarHandle.
    private fun mathematical(name: String, seed: Long): Long {
        val x = BigInteger.valueOf(seed)
        fun n(value: Long) = BigInteger.valueOf(value)
        if (name == "unboxedEmpty") return (x + n(7)).toLong()
        if (name == "unboxedAccum" || name == "unboxedST") {
            val a = MutableList(8) { x }
            if (name == "unboxedAccum") {
                for ((key, value) in listOf(-3 to n(3), 0 to n(5), -3 to n(-2), 4 to x))
                    a[key + 3] = a[key + 3] + value
            } else {
                val before = a[0]
                a[3] = before + n(7)
                val after = a[3]
                a[7] = n(3) * after - x
            }
            return (a[0] * n(7) + a[3] * n(11) + a[7] * n(13)).toLong()
        }
        val pattern = BigInteger("55aa55aa55aa55aa", 16)
        if (name == "orderedInts") {
            val a = mutableListOf(x, x + n(1), x.xor(pattern))
            val b = listOf(x + n(71))
            val before = a[1]
            a[1] = x + n(17)
            val after = a[1]
            return (before*n(3) + after*n(5) + a[0]*n(7) + a[1]*n(11) +
                a[2]*n(13) + b[0]*n(17) + n(32)).toLong()
        }
        check(name == "aliasIntBytes")
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        val modulus = BigInteger.ONE.shiftLeft(64)
        val bytes = listOf(x, x.xor(pattern)).flatMap { value ->
            (0..7).map { byte -> value.mod(modulus).shiftRight((if (little) byte else 7-byte)*8).and(n(255)) }
        }.toMutableList()
        bytes[7] = (x+n(101)).mod(n(256))
        bytes[8] = (x+n(37)).mod(n(256))
        fun cell(index: Int): BigInteger {
            var value = BigInteger.ZERO
            for (byte in 0..7) value = value.or(bytes[index*8+byte].shiftLeft((if (little) byte else 7-byte)*8))
            return if (value.testBit(63)) value-modulus else value
        }
        val a = cell(0); val b = cell(1)
        return (a*n(3) + b*n(5) + a*n(7) + b*n(11) + bytes[0]*n(17) +
            bytes[7]*n(19) + bytes[8]*n(23) + bytes[15]*n(29)).toLong()
    }

    @Test fun genuinePublicArraysAndPrimitiveAliasesMatchNativeAndIndependentModel() {
        val manifest = manifest()
        assertEquals(names.toSet(), (manifest["entries"] as List<String>).toSet())
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale Int-array fixture: $path; rerun prepare-int-arrays.py")
        }
        val rows = File(root, "build/int-arrays/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in names) {
                val cases = rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
                assertEquals(cases.size, cases.map { it.first }.toSet().size)
                assertEquals((manifest["inputs"] as List<Number>).map { it.toLong() }.toSet(), cases.map { it.first }.toSet())
                cases.forEach { (input, native) -> assertEquals(mathematical(name, input), native, "Native $name($input)") }
                for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val label = "$stage/$backend/$name"
                        val program = program(language, CoreModules.reachable(module, name) + ("instrument" to true), backend)
                        val host = program.hostEntryTarget(1)
                        val function = context.asValue(EntryValue(program, name, 1))
                        fun check(row: Pair<Long, Long>) = assertEquals(row.second, function.execute(row.first).asLong(), "$label(${row.first})")
                        fun compiled() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        cases.forEach(::check)
                        assertTrue(function.invokeMember("compile").asBoolean(), "$label installation")
                        val original = program.entryTarget(name)
                        val active = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                            .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                            .ifEmpty { listOf(original) }
                        for (row in cases.asReversed()) {
                            val before = compiled()
                            check(row)
                            assertTrue(compiled() > before, "$label(${row.first}) must enter installed guest code")
                            valid(host, "$label host remains installed")
                            active.forEach { valid(it, "$label active target remains installed") }
                        }
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        assertEquals(0, language.handoffState.get().results.depth, "$label releases tuple results")
                        if (name in listOf("orderedInts", "aliasIntBytes"))
                            assertEquals(0L, language.handoffState.get().results.allocations, "$label direct tuple destinations")
                    } finally { context.leave() }
                }
            }
        }
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    private fun paths() = (manifest()["stages"] as Map<String, List<String>>).getValue("pre")

    @Test fun exactIntStateShapesSaturationAndLevityAreRequiredInBothLoadModes() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (mutation in 0..9) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(merged(paths), "orderedInts")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    val flags = app[3] as MutableList<Any?>
                    val metadata = CoreRepresentations.metadata(app) as MutableMap<String, Any?>
                    when (mutation) {
                        0 -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex); metadata.remove("callDemand") }
                        1 -> { args.add(args[0]); flags.add(false); metadata.remove("callDemand") }
                        2 -> metadata.remove("rep")
                        3 -> metadata["rep"] = mapOf("kind" to "long", "primReps" to listOf("WordRep"), "evaluated" to true)
                        4 -> flags[0] = true
                        5 -> (CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["kind"] = "unknown"
                        6 -> (CoreRepresentations.metadata(args[1] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["primReps"] = listOf("Int64Rep")
                        7 -> (CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["primReps"] = listOf("BoxedRep (Just Lifted)")
                        8 -> if (operation == ByteArrayOp.READ_INT) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else metadata["rep"] = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true)
                        9 -> if (operation == ByteArrayOp.READ_INT) (metadata["rep"] as MutableMap<String, Any?>)["kind"] = "long"
                            else flags[1] = true
                    }
                    assertThrows(RuntimeFault::class.java, {
                        program(language, module + ("diagnosticUnsupported" to diagnostic), backend)
                    }, "$backend/${operation.primitive}/mutation$mutation/$diagnostic")
                }
                for (operation in operations) {
                    val module = CoreModules.reachable(merged(paths), "orderedInts")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val primitive = (app[1] as List<*>).toList(); app.clear(); app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) { program(language, module, backend) }
                }
            } finally { context.leave() }
        }
    }

    @Test fun invalidElementIndicesFailOnBothBackendsWithoutComparingNativeUndefinedAccesses() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (index in listOf(Long.MIN_VALUE, -1L, 3L, 1L shl 32, 1L shl 61, Long.MAX_VALUE)) {
                    val module = CoreModules.reachable(merged(paths), "orderedInts")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    args[1] = listOf("lit", "int", index.toString(), CoreRepresentations.metadata(args[1] as List<Any?>))
                    val program = program(language, module, backend)
                    val function = context.asValue(EntryValue(program, "orderedInts", 1))
                    val failure = assertThrows(PolyglotException::class.java) { function.execute(5L) }
                    assertTrue(failure.message.orEmpty().contains("ByteArray# Int index"), "$backend/$operation/$index: $failure")
                    assertEquals(0, language.handoffState.get().results.depth)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
                val good = context.asValue(EntryValue(program(language,
                    CoreModules.reachable(merged(paths), "orderedInts"), backend), "orderedInts", 1))
                assertEquals(mathematical("orderedInts", 5), good.execute(5L).asLong())
            } finally { context.leave() }
        }
    }
}
