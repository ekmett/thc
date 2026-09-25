// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import java.security.MessageDigest
import java.util.HexFormat

internal data class ForeignBitcode(val unit: String, val module: String, val target: String,
    val symbols: Set<String>, val bytes: ByteArray)

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

    /** Bytecode is a separate product of the original C stubs, never an alias for an unlinked archive. */
    fun linked(module: Map<*, *>): ForeignBitcode? {
        val raw = module["foreignLink"] ?: return null
        val link = raw as? Map<*, *> ?: error("Invalid foreign bitcode link")
        require(link.keys == setOf("schema", "format", "unit", "module", "target", "symbols",
            "sourceSha256", "bitcodeSha256", "bitcodeHex") && version(link["schema"], 1) &&
            link["format"] == "llvm-bitcode" && link["unit"] == module["unit"] &&
            link["module"] == module["module"]) { "Invalid foreign bitcode owner/schema" }
        val unit = link["unit"] as? String ?: error("Invalid foreign bitcode unit")
        val name = link["module"] as? String ?: error("Invalid foreign bitcode module")
        require(name == "System.CPUTime.Posix.ClockGetTime" && unit.startsWith("base-")) {
            "Foreign module has no complete execution ABI: $unit:$name"
        }
        val foreign = module["foreign"] as? Map<*, *> ?: error("Missing original foreign archive")
        val stubs = foreign["stubs"] as? Map<*, *> ?: error("Missing original C stubs")
        require(stubs["header"] == "" && stubs["initializers"] == emptyList<Any>() &&
            stubs["finalizers"] == emptyList<Any>() && foreign["files"] == emptyList<Any>()) {
            "Foreign callbacks, initializers, finalizers, and additional files are not linked"
        }
        val source = stubs["source"] as? String ?: error("Missing original C source")
        val sourceHash = link["sourceSha256"] as? String
        require(source.isNotEmpty() && sourceHash?.matches(sha) == true &&
            digest(source.toByteArray(Charsets.UTF_8)) == sourceHash) { "Foreign C source hash mismatch" }
        val encoded = link["bitcodeHex"] as? String ?: error("Missing foreign bitcode")
        val bytes = try { HexFormat.of().parseHex(encoded) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid foreign bitcode encoding")
        }
        val hash = link["bitcodeSha256"] as? String
        require(encoded.isNotEmpty() && encoded.all { it in '0'..'9' || it in 'a'..'f' } &&
            hash?.matches(sha) == true && digest(bytes) == hash) { "Foreign bitcode hash mismatch" }
        val target = link["target"] as? String ?: error("Missing foreign target")
        val system = System.getProperty("os.name").let { if (it.startsWith("Mac")) "darwin" else it.lowercase() }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64" -> "x86_64"; "arm64" -> "aarch64"; else -> System.getProperty("os.arch").lowercase()
        }
        require(target.contains(system) && (target.startsWith(arch) || arch == "aarch64" && target.startsWith("arm64"))) {
            "Foreign bitcode target differs from this runtime"
        }
        val symbols = (link["symbols"] as? List<*>)?.map {
            it as? String ?: error("Invalid foreign symbol")
        } ?: error("Missing foreign symbol inventory")
        require(symbols.size == 3 && symbols.distinct() == symbols && symbols.all { it.isNotEmpty() }) {
            "Invalid foreign symbol inventory"
        }
        val actual = calls(module["bindings"]).map { descriptor ->
            val callTarget = descriptor["target"] as? Map<*, *> ?: error("Invalid foreign call target")
            require(callTarget["kind"] == "static" && callTarget["isFunction"] == true &&
                callTarget["unit"] == unit && descriptor["convention"] == "capi" &&
                descriptor["safety"] == "unsafe") { "Unsupported foreign call in linked CAPI module" }
            callTarget["symbol"] as? String ?: error("Invalid foreign call symbol")
        }.toSet()
        require(actual == symbols.toSet()) { "Linked CAPI symbols differ from original Core declarations" }
        return ForeignBitcode(unit, name, target, symbols.toSet(), bytes)
    }

    /** Backend tests may supply unversioned synthetic Core, but not foreign archives. */
    fun requireExecutableInput(module: Map<*, *>) {
        if (module.containsKey("schema") || module.containsKey("foreign")) requireExecutable(module)
    }

    fun validateArchive(module: Map<*, *>) {
        if (version(module["schema"], 1)) {
            require(!module.containsKey("foreign")) { "Foreign artifacts require Core schema 2" }
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
    }

    fun requireExecutable(module: Map<*, *>) {
        validateArchive(module)
        require(version(module["schema"], 1) || linked(module) != null) {
            "Unsupported foreign code/registration for ${module["unit"]}:${module["module"]}: " +
                "Core schema 2 is archive-only; native stubs, initializers, finalizers and callbacks are not linked"
        }
    }
}
