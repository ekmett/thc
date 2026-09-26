// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/** Real Cabal C sources, native observations and unchanged retained library bundles. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class PackageScalarCbitsTest {
    @TempDir lateinit var temporary: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/package-scalar-cbits"
    private val names = setOf("scalarInt32", "scalarInt64", "scalarFloat", "scalarDouble", "scalarMixed", "repeatInt32")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private data class Fixture(val merged: Map<String, Any?>, val records: List<Map<String, Any?>>,
                               val links: List<PackageScalarLink>)

    private fun fixture(): Fixture {
        val manifest = json("$prefix/manifest.json")
        assertEquals(1L, manifest["schema"])
        assertEquals(true, manifest["supported"]); assertEquals(true, manifest["strictAccepted"])
        assertEquals(true, manifest["runtimeVerified"]); assertEquals(60L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "test/fixtures/run-scalar-cbits/src/Scalar.hs", "test/fixtures/run-scalar-cbits/src/ScalarAgain.hs",
            "test/fixtures/run-scalar-cbits/cbits/scalar.c", "test/fixtures/run-scalar-cbits/cbits/scalar.h",
            "src/THC/Driver/ScalarBitcode.hs", "compiler/THC/Plugin.hs", "test/haskell-fixtures/PackageScalarFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"],
            listOf("first", "second").flatMap { listOf("$prefix/$it/packages.json", "$prefix/$it/library.zip", "$prefix/$it/audit.json") }.toSet(), "$prefix/")
        val records = manifest["records"] as List<Map<String, Any?>>
        assertEquals(listOf("first", "second"), records.map { it["name"] })
        assertEquals(2, records.map { it["unit"] }.distinct().size)
        val modules = mutableListOf<Map<String, Any?>>()
        for (record in records) {
            val packages = json(record["packages"] as String)
            val unit = (packages["units"] as List<Map<String, Any?>>).single { it["id"] == record["unit"] }
            val bundle = unit["bundle"] as Map<*, *>
            assertEquals(record["librarySha256"], bundle["sha256"])
            assertEquals("$prefix/${record["name"]}/library.zip", record["libraryBundle"])
            // Select an unchanged, hashed unit from the ordinary acquisition result.
            // No test Core, synthetic provider or edited ZIP is substituted.
            val selected = temporary.resolve("${record["name"]}-packages.json").toFile()
            val retained = bundle + ("path" to File(root, record["libraryBundle"] as String).canonicalPath)
            selected.writeText(Json.stringify(packages + ("units" to listOf(unit + ("bundle" to retained)))))
            CorePackageManifest.visitModules(selected.path) { module, _ -> modules.add(module) }
            val rows = record["observations"] as List<Map<String, Any?>>
            assertEquals(30, rows.size)
            assertEquals(names, rows.map { it["entry"] }.toSet())
            assertEquals(setOf("Scalar", "ScalarAgain"), rows.map { it["module"] }.toSet())
            val zero = rows.single { it["entry"] == "scalarInt32" &&
                ((it["arguments"] as List<*>).single() as Map<*, *>)["value"] == "0" }
            assertEquals(if (record["name"] == "first") "1" else "2", (zero["result"] as Map<*, *>)["value"],
                "native copies must exercise different implementations of the same C symbol")
        }
        val merged = CoreModules.merge(modules)
        val links = merged["packageScalarLinks"] as List<PackageScalarLink>
        assertEquals(2, links.size)
        assertTrue(links.all { link -> link.abi.any { it.symbol == "thc_io_v1_close" } },
            "package-owned leaf must take precedence over the managed adapter with this spelling")
        assertEquals(links[0].abi.map { it.symbol }, links[1].abi.map { it.symbol }, "same source C names")
        assertTrue(links[0].abi.map { it.entry }.intersect(links[1].abi.map { it.entry }.toSet()).isEmpty(), "separate component entry names")
        assertEquals(setOf("Int32Rep", "Int64Rep", "FloatRep", "DoubleRep"),
            links.flatMap { link -> link.abi.flatMap { it.arguments + it.result } }.toSet())
        assertEquals(4, modules.count { it.containsKey("packageScalarLink") }, "two typed importing modules in each component")
        return Fixture(merged, records, links)
    }

    private fun context(native: Boolean = true): Context = Context.newBuilder("thc").allowNativeAccess(native)
        .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build()
    private fun valid(target: RootCallTarget, label: String) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val next = call.currentCallTarget as? RootCallTarget ?: continue
                if (next.rootNode is GuestRoot) visit(next)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    private fun identity(record: Map<String, Any?>, row: Map<String, Any?>) =
        "${record["unit"]}:${row["module"]}.${row["entry"]}"

    private class ScalarCallRoot(language: Language, private val call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun getName() = "package scalar access ${call.signature.symbol}"
        override fun execute(frame: VirtualFrame): Any {
            val arguments = frame.arguments[0] as Array<Any?>
            val state = frame.arguments[1]
            return when (call.result) {
                "Int32Rep", "Int64Rep" -> access.executeLong(arguments, state)
                "FloatRep" -> access.executeFloat(arguments, state)
                "DoubleRep" -> access.executeDouble(arguments, state)
                else -> error("Unexpected test ABI")
            }
        }
    }

    private fun checkNestedForeignScope(threads: GuestThreads, action: () -> Unit) {
        val previous = threads.enterForeign()
        try {
            assertEquals(GuestThreads.DeliveryPermission.NONE, previous)
            action()
            val nested = threads.enterForeign()
            try { assertEquals(GuestThreads.DeliveryPermission.FOREIGN, nested, "scalar call restores its outer foreign extent") }
            finally { threads.leaveForeign(nested) }
        } finally { threads.leaveForeign(previous) }
        val restored = threads.enterForeign()
        try { assertEquals(GuestThreads.DeliveryPermission.NONE, restored, "scalar call leaves no foreign extent") }
        finally { threads.leaveForeign(restored) }
    }

    @Test fun genuineCabalLibrariesMatchNativeInBothFirstInstalledBackends() {
        val fixture = fixture()
        val rows = fixture.records.flatMap { record -> (record["observations"] as List<Map<String, Any?>>).map { record to it } }
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fixture.links.forEach { Language.currentState().packageCbits.link(it) }
                val source = CoreModules.reachable(fixture.merged, rows.map { identity(it.first, it.second) }.distinct(), true) + ("instrument" to true)
                val program: ExecutableProgram = if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
                val entries = rows.map { identity(it.first, it.second) }.distinct().associateWith(program::entryTarget)
                fun check(record: Map<String, Any?>, row: Map<String, Any?>) {
                    val entry = identity(record, row)
                    val arguments = row["arguments"] as List<Map<String, Any?>>
                    assertEquals(listOf("IntRep"), arguments.map { it["rep"] })
                    val expected = row["result"] as Map<String, Any?>
                    assertEquals("IntRep", expected["rep"])
                    val result = Calls.target(entries.getValue(entry), arrayOf(0L, (arguments.single()["value"] as String).toLong())) as Long
                    val label = "$backend/$entry/${arguments.single()["value"]}"
                    when (row["comparison"]) {
                        "exact" -> assertEquals((expected["value"] as String).toLong(), result, label)
                        "float-nan" -> assertTrue(Float.fromBits(result.toInt()).isNaN(), label)
                        "double-nan" -> assertTrue(Double.fromBits(result).isNaN(), label)
                        else -> fail<Unit>("Unknown native comparison: $row")
                    }
                    released(language)
                }
                rows.forEach { (record, row) -> check(record, row) }
                val installed = entries.values.flatMap(::targets).distinct()
                installed.forEach { target ->
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "$backend/${target.rootNode.name} installed")
                }
                // Do not hide a first-entry bailout behind warmup after compilation.
                for ((record, row) in rows.asReversed()) {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    check(record, row)
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                        "$backend/${identity(record, row)} entered compiled guest code")
                    installed.forEach { valid(it, "$backend/${it.rootNode.name} after native call") }
                }
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                println("PackageScalarCbits PASS $backend nativeRows=${rows.size} entries=${entries.size} libraries=${fixture.links.size}")
            } finally { context.leave() }
        }
    }

    @Test fun nativeAuthorityContextOwnershipAndScalarCarriersAreCheckedBeforeCalls() {
        val fixture = fixture()
        context(false).use { context ->
            context.initialize("thc"); context.enter()
            try { assertThrows(RuntimeFault::class.java) { Language.currentState().packageCbits.link(fixture.links.first()) } }
            finally { context.leave() }
        }
        context().use { first ->
            first.initialize("thc"); first.enter()
            try {
                val owner = Language.currentState()
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val registry = owner.packageCbits
                fixture.links.forEach(registry::link)
                fixture.links.forEach(registry::link) // Same component imported by several modules is reusable.
                val link = fixture.links.first()
                assertThrows(RuntimeFault::class.java) {
                    registry.link(PackageScalarLink(link.unit + ":alias", link.target, link.componentSha256,
                        link.bitcodeSha256, link.bytes, link.abi))
                }
                val calls = link.abi.map { signature ->
                    val values: Array<Any?> = signature.arguments.map { when (it) {
                        "Int32Rep" -> 0; "Int64Rep" -> 0L; "FloatRep" -> 0.0f; "DoubleRep" -> 0.0
                        else -> error("Unexpected test ABI")
                    } }.toTypedArray()
                    val target = ScalarCallRoot(language, PackageScalarCall(link, signature)).callTarget
                    fun invoke(arguments: Array<Any?>) = Calls.target(target, arrayOf(arguments, Unit))
                    checkNestedForeignScope(owner.threads) { invoke(values) }
                    invoke(values) // Exercise the cached handle, not just resolution.
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(values, 0L)) }
                    for (index in values.indices) {
                        val invalid = values.copyOf(); invalid[index] = "not a scalar"
                        assertThrows(RuntimeFault::class.java) { invoke(invalid) }
                        if (signature.arguments[index] == "Int32Rep") {
                            invalid[index] = Int.MAX_VALUE.toLong() + 1
                            assertThrows(RuntimeFault::class.java) { invoke(invalid) }
                        }
                    }
                    target to values
                }
                context().use { second ->
                    second.initialize("thc"); second.enter()
                    try {
                        assertThrows(RuntimeFault::class.java) { registry.link(link) }
                        for ((target, values) in calls)
                            assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(values, Unit)) }
                    }
                    finally { second.leave() }
                }
                calls.forEach { (target, values) ->
                    Calls.target(target, arrayOf(values, Unit))
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "cached scalar access before registry close")
                }
                registry.close()
                assertThrows(RuntimeFault::class.java) { registry.link(link) }
                for ((target, values) in calls)
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(values, Unit)) }
            } finally { first.leave() }
        }
    }
}
