// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

/** A retained obligation is a prohibition, never a foreign execution capability. */
internal class PackageNativeArchive(val detail: String, val wholeModule: Boolean,
    private val unit: String, val excluded: List<Map<*, *>>) {
    fun blocks(binding: Any?): Boolean = wholeModule || calls(binding).any { call ->
        val target = call["target"] as? Map<*, *>
        target?.get("unit") == unit && excluded.any { emitted ->
            target["symbol"] == emitted["symbol"] && call["convention"] == emitted["convention"] &&
                call["safety"] == emitted["safety"]
        }
    }

    companion object {
        fun calls(value: Any?): List<Map<*, *>> = when (value) {
            is Map<*, *> -> listOfNotNull(value["foreignCall"] as? Map<*, *>) + value.values.flatMap(::calls)
            is List<*> -> value.flatMap(::calls)
            else -> emptyList()
        }
    }
}

internal object PackageNativeArchives {
    private fun check(value: Boolean, reason: String) = require(value) { "Invalid package native archive: $reason" }
    private fun version(value: Any?, n: Int) = value == n || value == n.toLong()
    private fun record(value: Any?, fields: String): Map<*, *> {
        check(value is Map<*, *> && value.keys == fields.split(' ').toSet(), "record fields")
        return value as Map<*, *>
    }
    private fun text(value: Any?): String {
        check(value is String && value.isNotEmpty() && '\u0000' !in value, "text")
        return value as String
    }
    private fun list(value: Any?): List<*> {
        check(value is List<*>, "list")
        return value as List<*>
    }
    private val scalar = setOf("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
        "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "FloatRep", "DoubleRep", "AddrRep")
    private val inputs = scalar + setOf("ByteArray#", "MutableByteArray#")

    fun excluded(module: Map<*, *>): List<*> = ((module["packageNativeArchive"] as? Map<*, *>)
        ?.get("unsupportedImports") as? List<*>) ?: emptyList<Any>()

    fun read(module: Map<*, *>): PackageNativeArchive? {
        val raw = module["packageNativeArchive"] ?: return null
        val archive = record(raw, "schema profile execution unit module unsupportedImports unclassifiedReason unresolvedSymbols artifact")
        val unit = text(module["unit"])
        check(version(archive["schema"], 1) && archive["profile"] == "thc-package-native-archive-v1" &&
            archive["execution"] == "not-linked" && archive["unit"] == unit && archive["module"] == module["module"], "profile/owner")
        check(!module.containsKey("foreignLink") && !module.containsKey("packageScalarLink"), "mixed foreign profiles")
        val unknown = archive["unclassifiedReason"]
        check(unknown == null || unknown == "non-static-c-import-declaration", "unclassified reason")
        val proof = module["staticForeignImports"]
        val imports = if (unknown != null) {
            val p = record(proof, "schema scope execution profile unit module status reason")
            proofIdentity(p, module)
            check(p["status"] == "unclassified" && p["reason"] == unknown, "unclassified provenance")
            emptyList()
        } else if (proof == null) {
            check(!module.containsKey("foreign") && !module.containsKey("staticForeignImportStubs"), "missing import provenance")
            emptyList()
        } else {
            val p = record(proof, "schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls")
            proofIdentity(p, module)
            check(p["status"] == "verified" && version(p["wordBits"], 64), "verified import profile")
            val product = record(p["expectedForeign"], "schema execution stubs files")
            check(version(product["schema"], 1) && product["execution"] == "not-linked" && product["files"] == emptyList<Any>(), "foreign product")
            product["stubs"]?.let {
                val stubs = record(it, "header source initializers finalizers")
                check(stubs["header"] == "" && stubs["source"] is String && stubs["initializers"] == emptyList<Any>() &&
                    stubs["finalizers"] == emptyList<Any>(), "foreign stub obligations")
                check(module.containsKey("foreign") || stubs["source"] == "", "missing retained stubs")
            }
            if (module.containsKey("foreign")) check(module["foreign"] == product, "retained product differs")
            check(p["expectedCalls"] == PackageNativeArchive.calls(module["bindings"]), "retained Core inventory differs")
            val binders = hashSetOf<Any?>()
            list(p["imports"]).map { item ->
                val entry = record(item, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted")
                val binder = PackageScalarLinks.archiveIdentity(entry["binder"])
                check(binder["unit"] == unit && binder["module"] == module["module"] && binder["namespace"] == "value" && binders.add(binder), "import binder")
                text(entry["symbol"])
                check(entry["header"] == null || entry["header"] is String && text(entry["header"]).none { it in "\n\r\"\\" }, "import header")
                check(entry["unit"] in listOf(null, unit) && entry["convention"] in setOf("ccall", "capi") &&
                    (entry["isFunction"] == true || entry["convention"] == "capi" && entry["isFunction"] == false) &&
                    entry["safety"] in setOf("unsafe", "safe", "interruptible") && entry["normalizationRole"] == "representational", "import metadata")
                PackageScalarLinks.archiveType(entry["declaredType"]); PackageScalarLinks.archiveType(entry["normalizedType"])
                val emitted = record(entry["emitted"], "symbol unit convention safety arguments result")
                check(text(emitted["symbol"]).matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && emitted["unit"] == unit &&
                    emitted["convention"] == entry["convention"] && emitted["safety"] == entry["safety"], "emitted identity")
                val args = list(emitted["arguments"]); val result = list(emitted["result"])
                check(args.isNotEmpty() && args.last() == "void" && args.dropLast(1).all { it in inputs } &&
                    (result == listOf("void") || result.size == 2 && result[0] == "void" && result[1] in scalar), "emitted carriers")
                emitted
            }
        }
        if (module.containsKey("staticForeignImportStubs")) check(module["staticForeignImportStubs"] == proof, "retained stub provenance differs")
        val expected = imports.filter { emitted ->
            emitted["safety"] == "interruptible" || emitted["safety"] == "safe" &&
                ((emitted["arguments"] as List<*>).dropLast(1).any { it !in scalar || it == "AddrRep" } ||
                    (emitted["result"] as List<*>).last() == "AddrRep")
        }
        check(list(archive["unsupportedImports"]) == expected, "unsupported import inventory differs")
        val unresolved = list(archive["unresolvedSymbols"]).map(::text)
        check(unresolved.distinct() == unresolved, "duplicate unresolved symbols")
        val artifact = archive["artifact"]
        check((artifact == null) == unresolved.isEmpty(), "unresolved artifact pair")
        check(unknown != null || expected.isNotEmpty() || unresolved.isNotEmpty(), "empty archive obligation")
        if (artifact != null) {
            check(!module.containsKey("packageNativeLink") && unknown == null, "archive is also executable")
            // Validate the actual compiled bytes and complete typed ABI, but do
            // not hand this admission to the merger or load it into Sulong.
            @Suppress("UNCHECKED_CAST")
            PackageScalarLinks.read((module as Map<String, Any?>) + ("packageNativeLink" to artifact), validateArchive = false)
        }
        return PackageNativeArchive("${unit}:${module["module"]} has archive-only native obligations" +
            " (unsupported=${expected.size}, unclassified=$unknown, unresolved=$unresolved)",
            unknown != null || unresolved.isNotEmpty(), unit, expected)
    }

    private fun proofIdentity(proof: Map<*, *>, module: Map<*, *>) {
        check(version(proof["schema"], 1) && proof["scope"] == "retained-static-import-products" &&
            proof["execution"] == "not-linked" && proof["profile"] == "ghc-9.14.1-thc-only-static-c-imports-v1" &&
            proof["unit"] == module["unit"] && proof["module"] == module["module"], "typed provenance identity")
    }
}
