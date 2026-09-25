// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Source/ABI frontier evidence only: no claim that the original decoder runs on THC yet. */
class OriginalStackConsumerProofTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/reports/original-stack")
    private val proofPath = "compiler/test-fixtures/OriginalStackProof.json"
    private val proofHash = "db63661c12a6ecb757697e759fcb95e4d51f3689619bdb7682a041788eb41d4f"
    private val entries = listOf("captureOriginal", "decodeOriginal", "renderOriginal", "renderOriginalNames", "peekOriginalInfoTable", "lookupOriginalIPE", "peekOriginalInfoProv")
    private val retainedHashes = mapOf(
        "GHC.Internal.Stack.CloneStack" to "d0733836485a57ebc40a4ae52ce77319e4dbc44f617cbd396335ae977e5810e4",
        "GHC.Internal.Stack.Decode" to "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42",
        "GHC.Internal.InfoProv.Types" to "63fe524cfd81c88ebd4f835c8718a30b86828c9e53549a2c001cbffac5ab2d1e",
        "GHC.Internal.Heap.InfoTable" to "1065f91361835bb3cf0cf2547ee320c59ba542e65e26ef2ec55c297cdcf9855a")
    private fun read(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private val pinnedSources = listOf("GHC/Internal/Stack/CloneStack.hs", "GHC/Internal/Stack/Decode.hs",
        "GHC/Internal/InfoProv/Types.hsc", "GHC/Internal/Heap/InfoTable.hsc")
    private val requiredInputs = setOf("compiler/test-fixtures/OriginalStackAudit.hs",
        "compiler/test-fixtures/OriginalStackAuditNative.hs", "test/haskell-fixtures/StackFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", proofPath,
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/THC/Plugin.hs",
        "compiler/THC/CBV.hs", "compiler/THC/Demands.hs", "compiler/THC/Sources.hs", "compiler/THC/Wired.hs", "compiler/plugin.py",
        "compiler/pinned-ghc-internal/LICENSE") + pinnedSources.map { "compiler/pinned-ghc-internal/$it" }
    private fun contained(path: String, input: Boolean): File {
        require(!File(path).isAbsolute && ".." !in path.split('/'))
        require(if (input) path in requiredInputs else path.startsWith("build/original-stack/"))
        val canonicalRoot = root.canonicalFile.toPath()
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(if (input) canonicalRoot else canonicalRoot.resolve("build/original-stack")))
        return file
    }
    private fun checked(manifest: Map<String, Any?>): Map<String, Any?> {
        require(manifest["format"] == "thc-original-stack-fixture" && manifest["schema"] == 1L)
        require(manifest["entries"] == entries)
        val ghc = manifest["ghc"] as Map<String, Any?>
        require(ghc["version"] == "9.14.1" && ghc["installedArtifactsHashed"] == false && (ghc["path"] as String).isNotEmpty())
        val stages = manifest["stages"] as Map<String, List<String>>
        require(stages.keys == setOf("pre", "post"))
        require(manifest["proofResource"] == proofPath)
        val inputs = manifest["inputHashes"] as Map<String, String>
        require(inputs.keys == requiredInputs && inputs[proofPath] == proofHash)
        val artifacts = manifest["artifactHashes"] as Map<String, String>
        require(stages.values.all { paths -> paths.size == paths.toSet().size &&
            paths.count { it.endsWith("/OriginalStackAudit.json") } == 1 && paths.all { it in artifacts } })
        require(manifest["nativeOutput"] in artifacts)
        for (group in listOf("inputHashes", "artifactHashes")) {
            val records = manifest[group] as Map<String, String>
            require(records.isNotEmpty())
            for ((path, digest) in records) {
                // Resolve symlinks before reading; never hash an installed toolchain.
                require(hash(contained(path, group == "inputHashes")) == digest) { "Stale $group: $path" }
            }
        }
        val commands = manifest["commands"] as List<Map<String, Any?>>
        require(commands.size == 6 && commands.all { it["exit"] == 0L && it["timedOut"] != true })
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
        return callsIn(reached)
    }
    private fun callsIn(bindings: Collection<Map<String, Any?>>): List<Call> =
        bindings.flatMap { binding -> nodes(binding["expr"]).filter {
            it.firstOrNull() == "app" && (it.getOrNull(6) as? Map<*, *>)?.get("foreignCall") is Map<*, *>
        }.map { Call(binding.getValue("id") as String, it) } }
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
    private fun proof(): Map<String, Any?> = read(proofPath).also { require(hash(contained(proofPath, true)) == proofHash) }
    private fun inventory(proof: Map<String, Any?>): List<Call> {
        require(proof["format"] == "thc-original-stack-core-excerpts" && proof["schema"] == 1L && proof["fresh"] == false)
        require(proof["exporterRevision"] == "62e3400c5b889d3971cb4047709c408fd270255f" &&
            proof["ghcSourceRevision"] == "902339d332fb4ce2b3c87dcac1ee6495d41ad886" && proof["ghc"] == "9.14.1" &&
            proof["boundary"] == "optimized-Core-after-Tidy-before-CorePrep")
        require(proof["license"] == "BSD-3-Clause" && proof["licenseFile"] == "compiler/pinned-ghc-internal/LICENSE")
        val originals = proof["originals"] as List<Map<String, Any?>>
        require(originals.size == retainedHashes.size && originals.map { it["module"] }.toSet() == retainedHashes.keys)
        originals.zip(pinnedSources).forEach { (record, source) ->
            require(record["unit"] == "ghc-internal" && record["sha256"] == retainedHashes[record["module"]])
            require(record["exportPath"] == "${record["module"]}/${record["module"]}.json" &&
                record["sourcePath"] == "libraries/ghc-internal/src/$source" &&
                record["sourceSha256"] == hash(contained("compiler/pinned-ghc-internal/$source", true)))
        }
        val ownerList = proof["owners"] as List<Map<String, Any?>>
        val owners = ownerList.associateBy { it["id"] as String }
        require(owners.size == ownerList.size)
        owners.forEach { (id, owner) ->
            require(owner["module"] in retainedHashes && id.startsWith("ghc-internal:${owner["module"]}."))
            val path = owner["path"] as List<*>
            require(path.size == 2 && path[0] == "bindings" && path[1] is Long && path[1] as Long >= 0)
            require((owner["canonicalSha256"] as String).matches(Regex("[0-9a-f]{64}")))
        }
        fun located(record: Map<String, Any?>) {
            val owner = owners.getValue(record["owner"] as String)
            val path = record["path"] as List<*>
            require(path.take(3) == (owner["path"] as List<*>) + "expr")
            require(path.all { it is String || it is Long && it >= 0 })
        }
        val records = proof["calls"] as List<Map<String, Any?>>
        require(records.map { it["owner"] to it["path"] }.toSet().size == records.size)
        val calls = records.map { record -> located(record); Call(record["owner"] as String, record["expression"] as List<Any?>) }
        require(calls.groupingBy { it.symbol }.eachCount() == specs.keys.associateWith {
            when (it) { "getStackClosurezh" -> 12; "getWordzh" -> 3; else -> 1 }
        }) { "Original reachable stack ABI occurrence inventory changed" }
        val roots = proof["roots"] as List<String>
        require(roots == listOf("GHC.Internal.Stack.CloneStack.cloneMyStack", "GHC.Internal.Stack.Decode.decodeStackWithIpe",
            "GHC.Internal.Stack.Decode.prettyStackFrameWithIpe", "GHC.Internal.Heap.InfoTable.peekItbl",
            "GHC.Internal.InfoProv.Types.lookupIPE", "GHC.Internal.InfoProv.Types.peekInfoProv").map { "ghc-internal:$it" })
        val routes = proof["routes"] as List<Map<String, Any?>>
        require(routes.map { it["owner"] }.toSet() == calls.map { it.owner }.toSet() &&
            routes.map { it["owner"] }.toSet().size == routes.size)
        routes.forEach { route ->
            var target = route["owner"] as String
            for (hop in (route["hops"] as List<Map<String, Any?>>).asReversed()) {
                located(hop)
                val reference = hop["expression"] as List<*>
                require(reference[0] == "var" && reference[1] == target)
                target = hop["owner"] as String
            }
            require(target in roots)
        }
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

    /** Optional extra comparison, never a prerequisite or a skipped normal proof. */
    private fun compareOriginals(proof: Map<String, Any?>): Boolean {
        val retainedRoot = System.getenv("THC_STACK_RETAINED_ROOT") ?: return false
        val originals = (proof["originals"] as List<Map<String, Any?>>).associate { record ->
            val file = File(retainedRoot, record["exportPath"] as String)
            require(hash(file) == record["sha256"])
            record["module"] as String to (Json.parse(file.readText()) as Map<String, Any?>)
        }
        val owners = (proof["owners"] as List<Map<String, Any?>>).associateBy { it["id"] }
        fun at(value: Any?, path: List<*>): Any? = path.fold(value) { node, step ->
            when (step) { is Long -> (node as List<*>)[step.toInt()]; else -> (node as Map<*, *>)[step] }
        }
        for (owner in owners.values) {
            val binding = at(originals.getValue(owner["module"] as String), owner["path"] as List<*>)
            val digest = MessageDigest.getInstance("SHA-256").digest(Json.stringify(binding).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            require(digest == owner["canonicalSha256"])
        }
        val references = (proof["routes"] as List<Map<String, Any?>>).flatMap { it["hops"] as List<Map<String, Any?>> }
        for (excerpt in (proof["calls"] as List<Map<String, Any?>>) + references) {
            val owner = owners.getValue(excerpt["owner"])
            require(at(originals.getValue(owner["module"] as String), excerpt["path"] as List<*>) == excerpt["expression"])
        }
        val module = CoreModules.merge(originals.values.toList())
        val reached = (proof["roots"] as List<String>).flatMap {
            CoreModules.reachable(module, it)["bindings"] as List<Map<String, Any?>>
        }.associateBy { it["id"] }
        require(callsIn(reached.values).groupingBy { it }.eachCount() == inventory(proof).groupingBy { it }.eachCount())
        return true
    }

    @Test fun originalsRetainEveryColdGetterAndFreshConsumersRemainSeparateEvidence() {
        val manifest = manifest()
        val proof = proof()
        val all = inventory(proof)
        val originalArchiveCompared = compareOriginals(proof)
        val reports = mutableListOf<Map<String, Any?>>()
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val fresh = read(paths.single { it.endsWith("/OriginalStackAudit.json") })
            require(fresh["module"] == "OriginalStackAudit" && fresh["ghc"] == "9.14.1")
            require(fresh["boundary"] == if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep")
            val bindings = fresh["bindings"] as List<Map<String, Any?>>
            require(bindings.map { it["name"] }.containsAll(entries))
            val linked = CoreModules.merge(paths.map(::read))
            // Portable excerpts are not modules and must never substitute for an unfolding.
            for (entry in entries) {
                val id = "${fresh.getValue("unit")}:OriginalStackAudit.$entry"
                val reached = CoreModules.reachable(linked, id)["bindings"] as List<Map<String, Any?>>
                val foreign = calls(linked, id)
                foreign.filter { it.symbol in specs }.forEach(::checkCall)
                // Missing Haskell definitions are distinct from explicitly declared FCallIds.
                val frontier = try { CoreModules.reachable(linked, id, strictLink = true); null }
                    catch (missing: IllegalArgumentException) { missing.message }
                reports += mapOf("stage" to stage, "entry" to entry, "evidence" to "fresh-interface", "reachableBindings" to reached.size,
                    "foreignCounts" to foreign.groupingBy { it.symbol }.eachCount(), "missingDefinitions" to frontier,
                    "foreignCalls" to foreign.map { mapOf("owner" to it.owner, "descriptor" to it.descriptor) })
            }
        }
        val shape = nativeShape(File(root, manifest["nativeOutput"] as String).readText())
        directory.mkdirs()
        File(directory, "proof.json").writeText(Json.stringify(mapOf(
            "kind" to "structural-original-source-frontier-not-runtime-success", "consumerFrontiers" to reports,
            "originalArchiveCompared" to originalArchiveCompared,
            "retainedForeignCounts" to all.groupingBy { it.symbol }.eachCount(),
            "originalCalls" to all.map { mapOf("owner" to it.owner, "descriptor" to it.descriptor, "source" to it.metadata["source"]) },
            "nativeShapeOnly" to shape)) + "\n")
        assertEquals(15, specs.size)
    }

    @Test fun completePinnedSourceLinksTheOriginalDecoderAndRenderer() {
        val formatterRoot = File(root, "build/original-stack-formatter").canonicalFile.toPath()
        val sourceReceipt = read("build/original-stack-formatter/manifest.json")
        val originals = (sourceReceipt["originals"] as List<String>).map { path ->
            val file = File(root, path).canonicalFile
            require(file.toPath().startsWith(formatterRoot) && path.endsWith(".json"))
            read(path)
        }
        require(originals.any { it["module"] == "GHC.Internal.Stack.Decode" })
        for (paths in (manifest()["stages"] as Map<String, List<String>>).values) {
            val consumer = read(paths.single { it.endsWith("/OriginalStackAudit.json") })
            val linked = CoreModules.reachable(CoreModules.merge(originals + consumer),
                "renderOriginalNames", strictLink = true)
            val ids = (linked["bindings"] as List<Map<String, Any?>>).map { it["id"] }.toSet()
            assertTrue(ids.any { it is String && it.startsWith("ghc-internal:GHC.Internal.Stack.Decode.") })
            assertTrue("${consumer["unit"]}:OriginalStackAudit.renderOriginalNames" in ids)
        }
    }

    @Test fun descriptorAndInventoryCorruptionCannotHideColdUnsupportedOperations() {
        manifest()
        val proof = proof()
        val all = inventory(proof)
        val cold = all.first { it.symbol == "getBCOLargeBitmapzh" }
        val badDescriptor = cold.descriptor + ("safety" to "unsafe")
        val badCall = cold.expression.toMutableList().also { it[6] = cold.metadata + ("foreignCall" to badDescriptor) }
        assertThrows(IllegalArgumentException::class.java) { checkCall(Call(cold.owner, badCall)) }
        val records = proof["calls"] as List<Map<String, Any?>>
        assertThrows(IllegalArgumentException::class.java) { inventory(proof + ("calls" to records.filter { it["owner"] != cold.owner })) }
        assertThrows(IllegalArgumentException::class.java) { inventory(proof + ("calls" to (records + records[0]))) }
        val otherLocation = records[0] + ("path" to ((records[0]["path"] as List<*>) + 0L))
        assertThrows(IllegalArgumentException::class.java) { inventory(proof + ("calls" to (records + otherLocation))) }
        val routes = proof["routes"] as List<Map<String, Any?>>
        val noPath = routes.map { if (it["owner"] == cold.owner) it + ("hops" to emptyList<Any>()) else it }
        assertThrows(IllegalArgumentException::class.java) { inventory(proof + ("routes" to noPath)) }
        for (symbol in specs.keys) {
            val call = all.first { it.symbol == symbol }
            val changed = call.expression.toMutableList().also { it[3] = List((call.expression[3] as List<*>).size) { true } }
            assertThrows(IllegalArgumentException::class.java) { checkCall(Call(call.owner, changed)) }
        }
    }

    @Test fun provenanceAndNativeShapeFailClosedWithoutTreatingCountsAsAnOracle() {
        val manifest = manifest()
        val proof = proof()
        for (change in listOf("fresh" to true, "exporterRevision" to "other", "ghcSourceRevision" to "other"))
            assertThrows(IllegalArgumentException::class.java) { inventory(proof + change) }
        val inputs = manifest["inputHashes"] as Map<String, String>
        for (path in requiredInputs)
            assertThrows(IllegalArgumentException::class.java) { checked(manifest + ("inputHashes" to (inputs - path))) }
        assertThrows(IllegalArgumentException::class.java) { checked(manifest + ("inputHashes" to (inputs + (proofPath to "0".repeat(64))))) }
        assertThrows(IllegalArgumentException::class.java) { checked(manifest + ("artifactHashes" to mapOf("/installed/ghc" to "unhashed"))) }
        assertThrows(IllegalArgumentException::class.java) { checked(manifest + ("ghc" to ((manifest["ghc"] as Map<String, Any?>) + ("installedArtifactsHashed" to true)))) }
        val outside = Files.createTempDirectory(File(root, "build").toPath(), "stack-proof-outside-")
        val linkDirectory = Files.createTempDirectory(File(root, "build/original-stack").toPath(), "containment-")
        val link = linkDirectory.resolve("outside-link")
        try {
            Files.createSymbolicLink(link, outside)
            val path = root.toPath().relativize(link).toString().replace(File.separatorChar, '/')
            assertThrows(IllegalArgumentException::class.java) { contained("$path/no-file", false) }
        } finally { Files.deleteIfExists(link); Files.delete(linkDirectory); Files.delete(outside) }
        assertEquals(listOf(3L, 0L, 0L, 0L), nativeShape("native-shape\t3\t0\t0\t0\n"))
        assertEquals(listOf(9L, 7L, 7L, 88L), nativeShape("native-shape\t9\t7\t7\t88\n"))
        for (row in listOf("native-shape\t0\t0\t0\t0", "native-shape\t1\t2\t0\t0", "native-shape\t1\t0\t2\t0",
                "native-shape\t1\t0\t0\t-1", "native-shape\t1\t0\t0", "native-shape\t1\t0\t0\t0\nextra"))
            assertThrows(IllegalArgumentException::class.java) { nativeShape(row) }
    }
}
