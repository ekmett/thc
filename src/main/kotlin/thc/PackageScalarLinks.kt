// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import java.security.MessageDigest
import java.util.HexFormat

/** The C entry is namespaced by the complete component recipe, not the source symbol. */
internal data class PackageScalarSignature(val symbol: String, val entry: String,
    val arguments: List<String>, val result: String)

internal class PackageScalarLink(val unit: String, val target: String,
    val componentSha256: String, val bitcodeSha256: String, val bytes: ByteArray,
    val abi: List<PackageScalarSignature>) {
    fun same(other: PackageScalarLink): Boolean = this === other || unit == other.unit && target == other.target &&
        componentSha256 == other.componentSha256 && bitcodeSha256 == other.bitcodeSha256 &&
        abi == other.abi && bytes.contentEquals(other.bytes)
}

internal data class PackageScalarAdmission(val link: PackageScalarLink, val proved: Set<String>)

/** A package-owned link needs both the typed GHC import and its complete component ABI. */
internal object PackageScalarLinks {
    private val hash = Regex("[0-9a-f]{64}")
    private val symbol = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val reps = setOf("Int32Rep", "Int64Rep", "FloatRep", "DoubleRep")
    private fun check(condition: Boolean, detail: String) = require(condition) { "Invalid package scalar link: $detail" }
    private fun record(value: Any?, keys: String): Map<*, *> {
        check(value is Map<*, *> && value.keys == keys.split(' ').toSet(), "record fields")
        return value as Map<*, *>
    }
    private fun text(value: Any?): String {
        check(value is String && value.isNotEmpty() && '\u0000' !in value, "missing text")
        return value as String
    }
    private fun list(value: Any?): List<*> {
        check(value is List<*>, "missing list")
        return value as List<*>
    }
    private fun version(value: Any?, wanted: Int) = value == wanted || value == wanted.toLong()
    private fun digest(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun calls(value: Any?): List<Any?> = when (value) {
        is Map<*, *> -> listOfNotNull(value["foreignCall"]) + value.values.flatMap(::calls)
        is List<*> -> value.flatMap(::calls)
        else -> emptyList()
    }
    private fun identity(value: Any?): Map<*, *> = record(value, "unit module occurrence namespace").also {
        it.values.forEach(::text)
        check(it["namespace"] in setOf("value", "type", "data"), "name namespace")
    }
    private fun type(value: Any?) {
        check(value is Map<*, *>, "type record")
        val raw = value as Map<*, *>
        when (raw["kind"]) {
            "tycon" -> {
                record(raw, "kind name arguments"); identity(raw["name"])
                list(raw["arguments"]).forEach(::type)
            }
            "application" -> { record(raw, "kind function argument"); type(raw["function"]); type(raw["argument"]) }
            "function" -> { record(raw, "kind multiplicity argument result"); type(raw["multiplicity"]); type(raw["argument"]); type(raw["result"]) }
            else -> check(false, "unknown type")
        }
    }
    private fun target(target: String) {
        val system = System.getProperty("os.name")
        val arch = when (System.getProperty("os.arch")) { "amd64" -> "x86_64"; "arm64" -> "aarch64"; else -> System.getProperty("os.arch") }
        val cpu = target.substringBefore('-').let { if (it == "arm64") "aarch64" else it }
        check(cpu == arch && ((system == "Linux" && target.endsWith("-linux-gnu")) ||
            (system.startsWith("Mac") && ("-darwin" in target || "-apple-macosx" in target))), "target differs from runtime")
    }

    fun read(module: Map<*, *>): PackageScalarAdmission? {
        val raw = module["packageScalarLink"] ?: return null
        check(module["ghc"] == "9.14.1" && version(module["schema"], 1), "GHC/schema")
        check(!module.containsKey("foreign") && !module.containsKey("foreignLink") &&
            !module.containsKey("staticForeignImportStubs") && !module.containsKey("staticForeignExports") &&
            !module.containsKey("staticForeignExportRegistration"), "mixed foreign obligations")
        val fields = record(raw, "schema format profile unit target componentSha256 bitcodeSha256 bitcodeHex abi")
        check(version(fields["schema"], 1) && fields["format"] == "llvm-bitcode" &&
            fields["profile"] == "thc-local-scalar-ccall-v1", "link profile")
        val unit = text(fields["unit"])
        check(unit == module["unit"], "component owner")
        val target = text(fields["target"]).also(::target)
        val componentHash = text(fields["componentSha256"])
        val bitcodeHash = text(fields["bitcodeSha256"])
        check(hash.matches(componentHash) && hash.matches(bitcodeHash), "digest")
        val encoded = text(fields["bitcodeHex"])
        check(encoded.length % 2 == 0 && encoded.all { it in '0'..'9' || it in 'a'..'f' }, "bitcode encoding")
        val bytes = HexFormat.of().parseHex(encoded)
        check(bytes.isNotEmpty() && digest(bytes) == bitcodeHash, "bitcode digest")
        val entries = list(fields["abi"])
        val abi = entries.mapIndexed { index, value ->
            val entry = record(value, "symbol entry arguments result")
            val name = text(entry["symbol"])
            check(symbol.matches(name), "C symbol")
            check(entry["entry"] == "thc_scalar_${componentHash}_$index", "component entry namespace")
            val arguments = list(entry["arguments"])
            check(arguments.all { it in reps } && entry["result"] in reps, "scalar ABI")
            PackageScalarSignature(name, entry["entry"] as String, arguments.map { it as String }, entry["result"] as String)
        }
        check(abi.isNotEmpty() && abi.map { it.symbol } == abi.map { it.symbol }.distinct().sorted(), "sorted unique ABI")
        val bySymbol = abi.associateBy { it.symbol }
        val proof = record(module["staticForeignImports"],
            "schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls")
        check(version(proof["schema"], 1) && proof["scope"] == "retained-static-import-products" &&
            proof["execution"] == "not-linked" && proof["profile"] == "ghc-9.14.1-thc-only-static-c-imports-v1" &&
            proof["unit"] == unit && proof["module"] == module["module"] && proof["status"] == "verified" &&
            version(proof["wordBits"], 64), "typed import profile/owner")
        val product = record(proof["expectedForeign"], "schema execution stubs files")
        check(version(product["schema"], 1) && product["execution"] == "not-linked" && product["files"] == emptyList<Any>(), "foreign product")
        if (product["stubs"] != null) {
            val stubs = record(product["stubs"], "header source initializers finalizers")
            check(stubs["header"] == "" && stubs["source"] == "" && stubs["initializers"] == emptyList<Any>() &&
                stubs["finalizers"] == emptyList<Any>(), "nonempty foreign products")
        }
        val imports = list(proof["imports"])
        check(imports.isNotEmpty(), "empty import inventory")
        val binders = hashSetOf<Map<*, *>>()
        val proved = hashSetOf<String>()
        for (value in imports) {
            val item = record(value, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted")
            val binder = identity(item["binder"])
            check(binder["unit"] == unit && binder["module"] == module["module"] && binder["namespace"] == "value" && binders.add(binder), "import binder")
            check(item["header"] == null && item["unit"] in listOf(null, unit) && item["isFunction"] == true &&
                item["convention"] == "ccall" && item["safety"] == "unsafe" && item["normalizationRole"] == "representational", "static unsafe ccall")
            type(item["declaredType"]); type(item["normalizedType"])
            val name = text(item["symbol"])
            val signature = requireNotNull(bySymbol[name]) { "Unlinked typed package C import: $name" }
            val emitted = record(item["emitted"], "symbol unit convention safety arguments result")
            check(emitted["symbol"] == name && emitted["unit"] == unit && emitted["convention"] == "ccall" &&
                emitted["safety"] == "unsafe" && emitted["arguments"] == signature.arguments + "void" &&
                emitted["result"] == listOf("void", signature.result), "emitted ABI differs from compiled C")
            proved.add(name)
        }
        check(proof["expectedCalls"] == calls(module["bindings"]), "retained Core foreign inventory differs")
        return PackageScalarAdmission(PackageScalarLink(unit, target, componentHash, bitcodeHash, bytes, abi), proved)
    }
}
