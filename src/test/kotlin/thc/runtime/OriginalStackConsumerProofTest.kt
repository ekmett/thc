// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import java.io.File
import java.security.MessageDigest

/** Source/ABI frontier evidence only: no claim that the original decoder runs on THC yet. */
class OriginalStackConsumerProofTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/original-stack")
    private val entries = listOf("captureOriginal", "decodeOriginal", "renderOriginal", "peekOriginalInfoTable", "lookupOriginalIPE", "peekOriginalInfoProv")
    private val retainedHashes = mapOf(
        "GHC.Internal.Stack.CloneStack" to "d0733836485a57ebc40a4ae52ce77319e4dbc44f617cbd396335ae977e5810e4",
        "GHC.Internal.Stack.Decode" to "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42",
        "GHC.Internal.InfoProv.Types" to "63fe524cfd81c88ebd4f835c8718a30b86828c9e53549a2c001cbffac5ab2d1e",
        "GHC.Internal.Heap.InfoTable" to "1065f91361835bb3cf0cf2547ee320c59ba542e65e26ef2ec55c297cdcf9855a")
    private fun read(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun checked(manifest: Map<String, Any?>): Map<String, Any?> {
        require(manifest["format"] == "thc-original-stack-fixture" && manifest["schema"] == 1L)
        require(manifest["entries"] == entries)
        val ghc = manifest["ghc"] as Map<String, Any?>
        require(ghc["version"] == "9.14.1" && ghc["installedArtifactsHashed"] == false && (ghc["path"] as String).isNotEmpty())
        val stages = manifest["stages"] as Map<String, List<String>>
        require(stages.keys == setOf("pre", "post"))
        val retained = manifest["retained"] as List<Map<String, Any?>>
        require(retained.size == retainedHashes.size && retained.map { it["module"] }.toSet() == retainedHashes.keys)
        val artifacts = manifest["artifacts"] as Map<String, String>
        for (record in retained) {
            require(record["fresh"] == false && record["sha256"] == retainedHashes[record["module"]])
            require(record["exporterRevision"] == "62e3400c5b889d3971cb4047709c408fd270255f" &&
                record["ghcSourceRevision"] == "902339d332fb4ce2b3c87dcac1ee6495d41ad886")
            require(artifacts[record["path"]] == record["sha256"])
        }
        require(stages.values.all { paths -> paths.size == paths.toSet().size &&
            paths.count { it.endsWith("/OriginalStackAudit.json") } == 1 && paths.all { it in artifacts } })
        require(manifest["nativeOutput"] in artifacts)
        for (group in listOf("sources", "artifacts")) {
            val records = manifest[group] as Map<String, String>
            require(records.isNotEmpty())
            for ((path, digest) in records) {
                // Never hash an installed toolchain or a path outside this checkout.
                require(!File(path).isAbsolute && ".." !in path.split('/') &&
                    if (group == "sources") path.startsWith("compiler/") || path.startsWith("test/haskell-fixtures/")
                    else path.startsWith("build/original-stack/"))
                require(hash(File(root, path)) == digest) { "Stale $group: $path" }
            }
        }
        require((manifest["commands"] as List<Map<String, Any?>>).all { it["exit"] == 0L && it["timedOut"] != true })
        return manifest
    }
    private fun manifest() = checked(read("build/original-stack/manifest.json"))
    private fun nodes(value: Any?): List<List<Any?>> = buildList {
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.values.forEach(::visit)
                is List<*> -> { add(value); value.forEach(::visit) }
            }
        }
        visit(value)
    }
    private data class Call(val owner: String, val expression: List<Any?>) {
        val metadata get() = expression[6] as Map<String, Any?>
        val descriptor get() = metadata.getValue("foreignCall") as Map<String, Any?>
        val symbol get() = (descriptor.getValue("target") as Map<String, Any?>).getValue("symbol") as String
    }
    private fun calls(module: Map<String, Any?>, entry: String): List<Call> {
        val reached = CoreModules.reachable(module, entry)["bindings"] as List<Map<String, Any?>>
        return reached.flatMap { binding -> nodes(binding["expr"]).filter {
            it.firstOrNull() == "app" && (it.getOrNull(6) as? Map<*, *>)?.get("foreignCall") is Map<*, *>
        }.map { Call(binding.getValue("id") as String, it) } }
    }
    private fun scalar(rep: String?, evaluated: Boolean = false): Map<String, Any?> = mapOf(
        "kind" to when (rep) { null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)" -> "object"; else -> "long" },
        "primReps" to listOfNotNull(rep), "evaluated" to evaluated)
    private fun tuple(vararg reps: String?) = mapOf("kind" to "unknown", "primReps" to reps.filterNotNull(),
        "evaluated" to false, "aggregate" to "unboxed-tuple", "components" to reps.map { scalar(it, true) })
    private val snapshot = "BoxedRep (Just Unlifted)"
    private val specs: Map<String, Pair<List<String?>, Map<String, Any?>>> = buildMap {
        put("stg_cloneMyStackzh", listOf<String?>(null) to tuple(null, snapshot))
        put("lookupIPE", listOf("AddrRep", "AddrRep", null) to tuple(null, "Word8Rep"))
        put("getStackInfoTableAddrzh", listOf(snapshot) to scalar("AddrRep"))
        put("getStackFieldszh", listOf(snapshot) to scalar("Word32Rep"))
        put("getInfoTableAddrszh", listOf(snapshot, "WordRep") to tuple("AddrRep", "AddrRep"))
        put("advanceStackFrameLocationzh", listOf(snapshot, "WordRep") to tuple(snapshot, "WordRep", "IntRep"))
        for (name in listOf("getSmallBitmapzh", "getRetFunSmallBitmapzh"))
            put(name, listOf(snapshot, "WordRep") to tuple("WordRep", "WordRep"))
        for (name in listOf("getLargeBitmapzh", "getBCOLargeBitmapzh", "getRetFunLargeBitmapzh"))
            put(name, listOf(snapshot, "WordRep") to tuple("AddrRep", "WordRep"))
        for ((name, rep) in listOf("getWordzh" to "WordRep", "getStackClosurezh" to "BoxedRep (Just Lifted)",
                "getUnderflowFrameNextChunkzh" to snapshot, "isArgGenBigRetFunTypezh" to "IntRep"))
            put(name, listOf(snapshot, "WordRep") to scalar(rep))
    }
    private fun checkCall(call: Call) {
        val (args, result) = specs.getValue(call.symbol)
        val expected = mapOf("schema" to 1L,
            "target" to mapOf("kind" to "static", "symbol" to call.symbol, "unit" to "ghc-internal", "isFunction" to true),
            "convention" to if (call.symbol == "lookupIPE") "ccall" else "prim", "safety" to "safe",
            "arity" to args.size.toLong(), "suppliedArity" to args.size.toLong(),
            "argumentReps" to args.map { scalar(it) }, "resultRep" to result)
        require(call.descriptor == expected) { "Changed original descriptor: ${call.symbol} in ${call.owner}" }
        require(call.metadata["rep"] == result && call.expression[3] == List(args.size) { false })
        val actual = call.expression[2] as List<List<Any?>>
        require(actual.size == args.size)
        actual.zip(args).forEach { (operand, rep) ->
            val proof = CoreRepresentations.metadata(operand)?.get("rep") as Map<String, Any?>
            require(proof["evaluated"] is Boolean && proof == scalar(rep, proof["evaluated"] as Boolean))
        }
    }
    private fun originalModule(manifest: Map<String, Any?>): Map<String, Any?> {
        val modules = (manifest["retained"] as List<Map<String, Any?>>).map { record ->
            read(record["path"] as String).also { require(it["module"] == record["module"] && it["unit"] == "ghc-internal" &&
                it["ghc"] == "9.14.1" && it["boundary"] == "optimized-Core-after-Tidy-before-CorePrep") }
        }
        return CoreModules.merge(modules)
    }
    private val originalEntries = listOf("cloneMyStack", "decodeStackWithIpe", "prettyStackFrameWithIpe", "peekItbl", "lookupIPE", "peekInfoProv")
    private fun inventory(module: Map<String, Any?>): List<Call> {
        val calls = originalEntries.flatMap { calls(module, it) }.distinctBy { it.owner to it.expression }
        require(calls.map { it.symbol }.toSet() == specs.keys) { "Original reachable stack ABI inventory changed" }
        calls.forEach(::checkCall)
        return calls
    }
    private fun nativeShape(text: String): List<Long> {
        val row = text.trimEnd('\n').split('\t')
        require(row.size == 5 && row[0] == "native-shape")
        val values = row.drop(1).map { it.toLong().also { value -> require(value >= 0) } }
        require(values[0] > 0 && values[1] <= values[0] && values[2] <= values[0])
        return values
    }

    @Test fun originalsRetainEveryColdGetterAndFreshConsumersRemainSeparateEvidence() {
        val manifest = manifest()
        val module = originalModule(manifest)
        val all = inventory(module)
        val reports = mutableListOf<Map<String, Any?>>()
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val fresh = read(paths.single { it.endsWith("/OriginalStackAudit.json") })
            require(fresh["module"] == "OriginalStackAudit" && fresh["ghc"] == "9.14.1")
            require(fresh["boundary"] == if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep")
            val bindings = fresh["bindings"] as List<Map<String, Any?>>
            require(bindings.map { it["name"] }.containsAll(entries))
            val graphs = listOf("fresh-interface" to CoreModules.merge(paths.map(::read)),
                "retained-source-subset" to CoreModules.merge(listOf(fresh) +
                    (manifest["retained"] as List<Map<String, Any?>>).map { read(it["path"] as String) }))
            // Never silently replace an installed unfolding with old source, or call
            // this four-module retained subset a complete fresh dependency closure.
            for ((evidence, linked) in graphs) for (entry in entries) {
                val id = "${fresh.getValue("unit")}:OriginalStackAudit.$entry"
                val reached = CoreModules.reachable(linked, id)["bindings"] as List<Map<String, Any?>>
                val foreign = calls(linked, id)
                foreign.filter { it.symbol in specs }.forEach(::checkCall)
                // Missing Haskell definitions are distinct from explicitly declared FCallIds.
                val frontier = try { CoreModules.reachable(linked, id, strictLink = true); null }
                    catch (missing: IllegalArgumentException) { missing.message }
                reports += mapOf("stage" to stage, "entry" to entry, "evidence" to evidence, "reachableBindings" to reached.size,
                    "foreignCounts" to foreign.groupingBy { it.symbol }.eachCount(), "missingDefinitions" to frontier,
                    "foreignCalls" to foreign.map { mapOf("owner" to it.owner, "descriptor" to it.descriptor) })
            }
        }
        val shape = nativeShape(File(root, manifest["nativeOutput"] as String).readText())
        File(directory, "proof.json").writeText(Json.stringify(mapOf(
            "kind" to "structural-original-source-frontier-not-runtime-success", "consumerFrontiers" to reports,
            "retainedForeignCounts" to all.groupingBy { it.symbol }.eachCount(),
            "originalCalls" to all.map { mapOf("owner" to it.owner, "descriptor" to it.descriptor, "source" to it.metadata["source"]) },
            "nativeShapeOnly" to shape)) + "\n")
        assertEquals(15, specs.size)
    }

    @Test fun descriptorAndInventoryCorruptionCannotHideColdUnsupportedOperations() {
        val module = originalModule(manifest())
        val all = inventory(module)
        val cold = all.first { it.symbol == "getBCOLargeBitmapzh" }
        val badDescriptor = cold.descriptor + ("safety" to "unsafe")
        val badCall = cold.expression.toMutableList().also { it[6] = cold.metadata + ("foreignCall" to badDescriptor) }
        assertThrows(IllegalArgumentException::class.java) { checkCall(Call(cold.owner, badCall)) }
        val withoutDecode = module + ("bindings" to (module["bindings"] as List<Map<String, Any?>>).filter { it["id"] != cold.owner })
        assertThrows(IllegalArgumentException::class.java) { inventory(withoutDecode) }
        for (symbol in specs.keys) {
            val call = all.first { it.symbol == symbol }
            val changed = call.expression.toMutableList().also { it[3] = List((call.expression[3] as List<*>).size) { true } }
            assertThrows(IllegalArgumentException::class.java) { checkCall(Call(call.owner, changed)) }
        }
    }

    @Test fun provenanceAndNativeShapeFailClosedWithoutTreatingCountsAsAnOracle() {
        val manifest = manifest()
        val retained = manifest["retained"] as List<Map<String, Any?>>
        for (change in listOf("fresh" to true, "sha256" to "0".repeat(64), "ghcSourceRevision" to "other"))
            assertThrows(IllegalArgumentException::class.java) { checked(manifest + ("retained" to (listOf(retained[0] + change) + retained.drop(1)))) }
        assertThrows(IllegalArgumentException::class.java) { checked(manifest + ("artifacts" to mapOf("/installed/ghc" to "unhashed"))) }
        assertThrows(IllegalArgumentException::class.java) { checked(manifest + ("ghc" to ((manifest["ghc"] as Map<String, Any?>) + ("installedArtifactsHashed" to true)))) }
        assertEquals(listOf(3L, 0L, 0L, 0L), nativeShape("native-shape\t3\t0\t0\t0\n"))
        assertEquals(listOf(9L, 7L, 7L, 88L), nativeShape("native-shape\t9\t7\t7\t88\n"))
        for (row in listOf("native-shape\t0\t0\t0\t0", "native-shape\t1\t2\t0\t0", "native-shape\t1\t0\t2\t0",
                "native-shape\t1\t0\t0\t-1", "native-shape\t1\t0\t0", "native-shape\t1\t0\t0\t0\nextra"))
            assertThrows(IllegalArgumentException::class.java) { nativeShape(row) }
    }
}
