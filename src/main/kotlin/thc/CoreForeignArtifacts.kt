// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import java.security.MessageDigest
import java.util.HexFormat

internal data class ForeignBitcode(val unit: String, val module: String, val target: String,
    val symbols: Set<String>, val abi: Map<String, String>, val bytes: ByteArray)

/** Archive validation is not foreign linking, initialization, or callback registration. */
internal object CoreForeignArtifacts {
    private fun version(value: Any?, expected: Int) = value == expected || value == expected.toLong()
    private val sha = Regex("[0-9a-f]{64}")
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun calls(value: Any?): List<Map<*, *>> = when (value) {
        is Map<*, *> -> listOfNotNull(value["foreignCall"] as? Map<*, *>) + value.values.flatMap(::calls)
        is List<*> -> value.flatMap(::calls)
        else -> emptyList()
    }
    private fun scalar(primitive: String?, evaluated: Boolean) = mapOf(
        "kind" to when (primitive) { null -> "void"; "AddrRep" -> "address"; else -> "long" },
        "primReps" to (primitive?.let { listOf(it) } ?: emptyList<String>()), "evaluated" to evaluated)
    private fun tuple(primitive: String) = mapOf("kind" to "unknown", "primReps" to listOf(primitive),
        "aggregate" to "unboxed-tuple", "components" to listOf(scalar(null, true), scalar(primitive, true)),
        "evaluated" to false)
    private fun callKind(call: Map<*, *>, unit: String, symbol: String): String? {
        fun expected(kind: String): Map<String, Any> {
            val zero = kind == "clock-id"
            return mapOf("schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to symbol,
                "unit" to unit, "isFunction" to true), "convention" to "capi", "safety" to "unsafe",
                "arity" to (if (zero) 1L else 3L), "suppliedArity" to (if (zero) 1L else 3L),
                "argumentReps" to (if (zero) listOf(scalar(null, false)) else
                    listOf(scalar("Word64Rep", false), scalar("AddrRep", false), scalar(null, false))),
                "resultRep" to tuple(if (zero) "Word64Rep" else "Int32Rep"))
        }
        return when (call) { expected("clock-id") -> "clock-id";
            expected("clock-buffer") -> "clock-buffer"; else -> null }
    }

    /** Bytecode is a separate product of the original C stubs, never an alias for an unlinked archive. */
    fun linked(module: Map<*, *>): ForeignBitcode? {
        val raw = module["foreignLink"] ?: return null
        val link = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid foreign bitcode link")
        require(link.keys == setOf("schema", "format", "unit", "module", "target", "symbols", "abi",
            "sourceSha256", "bitcodeSha256", "bitcodeHex") && version(link["schema"], 2) &&
            link["format"] == "llvm-bitcode" && link["unit"] == module["unit"] &&
            link["module"] == module["module"]) { "Invalid foreign bitcode owner/schema" }
        val unit = link["unit"] as? String ?: throw IllegalArgumentException("Invalid foreign bitcode unit")
        val name = link["module"] as? String ?: throw IllegalArgumentException("Invalid foreign bitcode module")
        require(name == "System.CPUTime.Posix.ClockGetTime" && unit.startsWith("base-")) {
            "Foreign module has no complete execution ABI: $unit:$name"
        }
        val foreign = module["foreign"] as? Map<*, *> ?: throw IllegalArgumentException("Missing original foreign archive")
        val stubs = foreign["stubs"] as? Map<*, *> ?: throw IllegalArgumentException("Missing original C stubs")
        require(stubs["header"] == "" && stubs["initializers"] == emptyList<Any>() &&
            stubs["finalizers"] == emptyList<Any>() && foreign["files"] == emptyList<Any>()) {
            "Foreign callbacks, initializers, finalizers, and additional files are not linked"
        }
        val source = stubs["source"] as? String ?: throw IllegalArgumentException("Missing original C source")
        val sourceHash = link["sourceSha256"] as? String
        require(source.isNotEmpty() && sourceHash?.matches(sha) == true &&
            digest(source.toByteArray(Charsets.UTF_8)) == sourceHash) { "Foreign C source hash mismatch" }
        val encoded = link["bitcodeHex"] as? String ?: throw IllegalArgumentException("Missing foreign bitcode")
        val bytes = try { HexFormat.of().parseHex(encoded) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid foreign bitcode encoding")
        }
        val hash = link["bitcodeSha256"] as? String
        require(encoded.isNotEmpty() && encoded.all { it in '0'..'9' || it in 'a'..'f' } &&
            hash?.matches(sha) == true && digest(bytes) == hash) { "Foreign bitcode hash mismatch" }
        val target = link["target"] as? String ?: throw IllegalArgumentException("Missing foreign target")
        val system = System.getProperty("os.name").let {
            when { it.startsWith("Mac") -> "darwin"; it.startsWith("Linux") -> "linux-gnu"; else -> "unsupported" }
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64" -> "x86_64"; "arm64" -> "aarch64"; else -> System.getProperty("os.arch").lowercase()
        }
        val hostSystem = if (system == "darwin") "-darwin" in target else target.endsWith("-linux-gnu")
        require(system != "unsupported" && hostSystem &&
            (target.startsWith("$arch-") || arch == "aarch64" && target.startsWith("arm64-"))) {
            "Foreign bitcode target differs from this runtime"
        }
        val symbols = (link["symbols"] as? List<*>)?.map {
            it as? String ?: throw IllegalArgumentException("Invalid foreign symbol")
        } ?: throw IllegalArgumentException("Missing foreign symbol inventory")
        require(symbols.size == 3 && symbols.distinct() == symbols && symbols.all { it.isNotEmpty() }) {
            "Invalid foreign symbol inventory"
        }
        val abiEntries = link["abi"] as? List<*> ?: throw IllegalArgumentException("Missing CAPI ABI inventory")
        val abi = abiEntries.associate { entry ->
            val fields = entry as? Map<*, *> ?: throw IllegalArgumentException("Invalid CAPI ABI entry")
            require(fields.keys == setOf("symbol", "kind")) { "Invalid CAPI ABI fields" }
            val symbol = fields["symbol"] as? String ?: throw IllegalArgumentException("Invalid CAPI ABI symbol")
            val kind = fields["kind"] as? String ?: throw IllegalArgumentException("Invalid CAPI ABI kind")
            require(kind == "clock-id" || kind == "clock-buffer") { "Invalid CAPI ABI kind" }
            symbol to kind
        }
        require(abiEntries.size == 3 && abi.keys == symbols.toSet() &&
            abi.values.count { it == "clock-id" } == 1 && abi.values.count { it == "clock-buffer" } == 2) {
            "CAPI ABI inventory differs from original symbols"
        }
        val actual = calls(module["bindings"]).map { descriptor ->
            val callTarget = descriptor["target"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid foreign call target")
            val symbol = callTarget["symbol"] as? String ?: throw IllegalArgumentException("Invalid foreign call symbol")
            require(callKind(descriptor, unit, symbol) == abi[symbol]) {
                "CAPI original call disagrees with linked symbol ABI: $symbol"
            }
            symbol
        }.toSet()
        require(actual == symbols.toSet()) { "Linked CAPI symbols differ from original Core declarations" }
        return ForeignBitcode(unit, name, target, symbols.toSet(), abi, bytes)
    }

    /** Backend tests may supply unversioned synthetic Core, but not foreign archives. */
    fun requireExecutableInput(module: Map<*, *>) {
        if (module.containsKey("archiveBindings")) {
            val pending = module["archiveBindings"] as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid Core archive admission state")
            require(pending.isEmpty()) { "Unresolved archive-only Core modules require entry reachability" }
        }
        if (module.containsKey("schema") || module.containsKey("foreign")) requireExecutable(module)
    }

    fun validateArchive(module: Map<*, *>) {
        if (version(module["schema"], 1)) {
            require(!module.containsKey("foreign") && !module.containsKey("staticForeignImportStubs")) { "Foreign artifacts require Core schema 2" }
            PackageScalarLinks.read(module)
            return
        }
        require(version(module["schema"], 2)) { "Unsupported Core schema: ${module["schema"]}" }
        fun record(value: Any?, keys: Set<String>): Map<*, *> {
            require(value is Map<*, *> && value.keys == keys) { "Invalid Core foreign artifact record" }
            return value
        }
        fun text(value: Any?): String {
            require(value is String) { "Invalid Core foreign artifact text" }
            return value
        }
        fun list(value: Any?): List<*> {
            require(value is List<*>) { "Invalid Core foreign artifact list" }
            return value
        }
        fun labels(value: Any?, initializer: Boolean): List<*> = list(value).also { entries ->
            for (entry in entries) {
                val label = record(entry, setOf("isInitializer", "unit", "module", "name"))
                require(label["isInitializer"] == initializer) { "Foreign initializer/finalizer kind mismatch" }
                for (key in listOf("unit", "module", "name"))
                    require(text(label[key]).isNotEmpty()) { "Missing foreign label $key" }
            }
        }
        val artifacts = record(module["foreign"], setOf("schema", "execution", "stubs", "files"))
        require(version(artifacts["schema"], 1) && artifacts["execution"] == "not-linked") {
            "Invalid Core foreign artifact schema/link state"
        }
        var nonempty = false
        artifacts["stubs"]?.let { value ->
            val stub = record(value, setOf("header", "source", "initializers", "finalizers"))
            val header = text(stub["header"])
            val source = text(stub["source"])
            val initializers = labels(stub["initializers"], true)
            val finalizers = labels(stub["finalizers"], false)
            nonempty = header.isNotEmpty() || source.isNotEmpty() || initializers.isNotEmpty() || finalizers.isNotEmpty()
        }
        for (entry in list(artifacts["files"])) {
            val file = record(entry, setOf("language", "source", "extension"))
            require(text(file["language"]).isNotEmpty()) { "Missing foreign source language" }
            text(file["source"])
            text(file["extension"])
            nonempty = true
        }
        require(nonempty) { "Core schema 2 requires foreign artifacts" }
        if (module.containsKey("foreignLink")) linked(module)
        if (PackageScalarLinks.read(module) == null) ManagedImportAdmission.read(module)
    }

    /** Registrations and opaque foreign files can run independently of Core reachability. */
    fun hasRegistrationObligations(module: Map<*, *>): Boolean {
        val foreign = module["foreign"] as? Map<*, *> ?: return false
        // Extra C/object files may contain native constructors absent from the
        // GHC stub-label lists. Their startup behavior needs a native link proof.
        if ((foreign["files"] as? List<*>)?.isNotEmpty() == true) return true
        val stubs = foreign["stubs"] as? Map<*, *> ?: return false
        return (stubs["initializers"] as? List<*>)?.isNotEmpty() == true ||
            (stubs["finalizers"] as? List<*>)?.isNotEmpty() == true
    }

    fun requireExecutable(module: Map<*, *>) {
        validateArchive(module)
        require(version(module["schema"], 1) || linked(module) != null || PackageScalarLinks.read(module) != null || ManagedImportAdmission.read(module) != null) {
            "Unsupported foreign code/registration for ${module["unit"]}:${module["module"]}: " +
                "Core schema 2 is archive-only; native stubs, initializers, finalizers and callbacks are not linked"
        }
    }
}
