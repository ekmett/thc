// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import java.security.MessageDigest
import java.util.HexFormat

/** The C entry is namespaced by the complete component recipe, not the source symbol. */
internal data class PackageScalarSignature(val symbol: String, val entry: String,
    val arguments: List<String>, val result: String, val convention: String = "ccall")

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
    private val nativeReps = reps + setOf("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep",
        "Word16Rep", "Word32Rep", "Word64Rep", "AddrRep", "ByteArray#", "MutableByteArray#")
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
    private fun type(value: Any?, depth: Int = 0) {
        check(value is Map<*, *>, "type record")
        val raw = value as Map<*, *>
        when (raw["kind"]) {
            "tycon" -> {
                record(raw, "kind name arguments"); identity(raw["name"])
                list(raw["arguments"]).forEach { type(it, depth) }
            }
            "application" -> { record(raw, "kind function argument"); type(raw["function"], depth); type(raw["argument"], depth) }
            "function" -> { record(raw, "kind multiplicity argument result"); type(raw["multiplicity"], depth); type(raw["argument"], depth); type(raw["result"], depth) }
            "forall" -> { record(raw, "kind binderKind body"); type(raw["binderKind"], depth); type(raw["body"], depth + 1) }
            "bound-variable" -> {
                record(raw, "kind index")
                val index = raw["index"]
                check((index is Long || index is Int) && (index as Number).toLong() in 0 until depth.toLong(), "free import type variable")
            }
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
        val native = module.containsKey("packageNativeLink")
        val raw = module[if (native) "packageNativeLink" else "packageScalarLink"] ?: return null
        check(!native || !module.containsKey("packageScalarLink"), "two package link profiles")
        check(module["ghc"] == "9.14.1" && (version(module["schema"], 1) ||
            native && version(module["schema"], 2)), "GHC/schema")
        check((native || !module.containsKey("foreign")) && !module.containsKey("foreignLink") &&
            (native || !module.containsKey("staticForeignImportStubs")) && !module.containsKey("staticForeignExports") &&
            !module.containsKey("staticForeignExportRegistration"), "mixed foreign obligations")
        val inputs = native && raw is Map<*, *> && raw.containsKey("buildInputs")
        val fields = record(raw, "schema format profile unit target componentSha256 bitcodeSha256 bitcodeHex abi" +
            if (inputs) " buildInputs" else "")
        if (inputs) check(fields["buildInputs"] is Map<*, *>, "build inputs record")
        check(version(fields["schema"], 1) && fields["format"] == "llvm-bitcode" &&
            fields["profile"] == (if (native) "thc-package-c-ffi-v1" else "thc-local-scalar-ccall-v1"), "link profile")
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
            val entry = record(value, if (native) "symbol entry convention safety arguments result" else "symbol entry arguments result")
            val name = text(entry["symbol"])
            check(symbol.matches(name), "C symbol")
            check(entry["entry"] == "thc_${if (native) "native" else "scalar"}_${componentHash}_$index", "component entry namespace")
            val arguments = list(entry["arguments"])
            val admitted = if (native) nativeReps else reps
            check(arguments.all { it in admitted } && entry["result"] in
                (if (native) nativeReps - setOf("AddrRep", "ByteArray#", "MutableByteArray#") + "void" else reps), "C ABI")
            val convention = if (native) text(entry["convention"]) else "ccall"
            check(!native || convention in setOf("ccall", "capi") && entry["safety"] == "unsafe", "unsupported C calling convention/safety")
            PackageScalarSignature(name, entry["entry"] as String, arguments.map { it as String }, entry["result"] as String, convention)
        }
        val ordered = compareBy<PackageScalarSignature>({ it.symbol }, { it.convention },
            { it.arguments.joinToString("\u0000") }, { it.result })
        check(abi.isNotEmpty() && abi == abi.sortedWith(ordered) &&
            abi.map { listOf(it.symbol, it.convention, it.arguments, it.result) }.distinct().size == abi.size,
            "sorted unique ABI")
        val bySymbol = abi.groupBy { it.symbol }
        bySymbol.values.forEach { variants ->
            check(native || variants.size == 1, "duplicate scalar ABI symbol")
            check(variants.map { listOf(it.convention, it.arguments.map { rep ->
                if (rep in setOf("ByteArray#", "MutableByteArray#")) "AddrRep" else rep }, it.result) }.distinct().size == 1,
                "conflicting C ABI variants")
            check(variants.map { listOf(it.convention, it.arguments.map { rep ->
                if (rep == "MutableByteArray#") "ByteArray#" else rep }, it.result) }.distinct().size == variants.size,
                "ambiguous byte-array mutability variants")
        }
        val link = PackageScalarLink(unit, target, componentHash, bitcodeHash, bytes, abi)
        if (native && !module.containsKey("staticForeignImports")) {
            check(!module.containsKey("foreign") && !module.containsKey("staticForeignImportStubs"),
                "foreign products lack import provenance")
            // GHC omits an empty declaration inventory in modules containing
            // only inlined calls. Their unit's declarations are checked at merge.
            return PackageScalarAdmission(link, emptySet())
        }
        val proof = record(module["staticForeignImports"],
            "schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls")
        if (native && module.containsKey("staticForeignImportStubs"))
            check(module["staticForeignImportStubs"] == proof, "retained CAPI import provenance differs")
        check(version(proof["schema"], 1) && proof["scope"] == "retained-static-import-products" &&
            proof["execution"] == "not-linked" && proof["profile"] == "ghc-9.14.1-thc-only-static-c-imports-v1" &&
            proof["unit"] == unit && proof["module"] == module["module"] && proof["status"] == "verified" &&
            version(proof["wordBits"], 64), "typed import profile/owner")
        val product = record(proof["expectedForeign"], "schema execution stubs files")
        check(version(product["schema"], 1) && product["execution"] == "not-linked" && product["files"] == emptyList<Any>(), "foreign product")
        if (native && module.containsKey("foreign")) check(product == module["foreign"], "retained C stubs differ")
        if (product["stubs"] != null) {
            val stubs = record(product["stubs"], "header source initializers finalizers")
            check(stubs["header"] == "" && (if (native) stubs["source"] is String else stubs["source"] == "") && stubs["initializers"] == emptyList<Any>() &&
                stubs["finalizers"] == emptyList<Any>(), "nonempty foreign products")
            check(!native || module.containsKey("foreign") || stubs["source"] == "", "missing retained C stubs")
        }
        val imports = list(proof["imports"])
        // Inlining may move calls into another module in this component. The
        // merger checks the complete unit's declaration inventory before use.
        check(native || imports.isNotEmpty(), "empty import inventory")
        val binders = hashSetOf<Map<*, *>>()
        val proved = hashSetOf<String>()
        for (value in imports) {
            val item = record(value, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted")
            val binder = identity(item["binder"])
            check(binder["unit"] == unit && binder["module"] == module["module"] && binder["namespace"] == "value" && binders.add(binder), "import binder")
            val convention = item["convention"]
            // A ccall declaration may retain a header name too. It does not
            // change the emitted C symbol or imply a generated CAPI wrapper.
            check((item["header"] == null || native && item["header"] is String &&
                (item["header"] as String).isNotEmpty() && '\u0000' !in (item["header"] as String)) &&
                item["unit"] in listOf(null, unit) && (item["isFunction"] == true || native && convention == "capi" && item["isFunction"] == false) &&
                convention in (if (native) setOf("ccall", "capi") else setOf("ccall")) && item["safety"] == "unsafe" &&
                item["normalizationRole"] == "representational", "static unsafe C import")
            type(item["declaredType"]); type(item["normalizedType"])
            text(item["symbol"])
            val emitted = record(item["emitted"], "symbol unit convention safety arguments result")
            val name = if (native) text(emitted["symbol"]) else text(item["symbol"])
            val signature = requireNotNull(bySymbol[name]?.singleOrNull {
                it.convention == convention && emitted["arguments"] == it.arguments + "void" &&
                    emitted["result"] == (if (it.result == "void") listOf("void") else listOf("void", it.result))
            }) { "Unlinked typed package C import variant: $name" }
            check(emitted["symbol"] == name && emitted["unit"] == unit && emitted["convention"] == signature.convention && convention == signature.convention &&
                emitted["safety"] == "unsafe" && emitted["arguments"] == signature.arguments + "void" &&
                emitted["result"] == (if (signature.result == "void") listOf("void") else listOf("void", signature.result)), "emitted ABI differs from compiled C")
            proved.add(signature.entry)
        }
        check(proof["expectedCalls"] == calls(module["bindings"]), "retained Core foreign inventory differs")
        return PackageScalarAdmission(link, proved)
    }
}
