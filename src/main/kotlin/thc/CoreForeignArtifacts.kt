// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

/** Archive validation is not foreign linking, initialization, or callback registration. */
internal object CoreForeignArtifacts {
    private fun version(value: Any?, expected: Int) = value == expected || value == expected.toLong()

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
    }

    fun requireExecutable(module: Map<*, *>) {
        validateArchive(module)
        require(version(module["schema"], 1)) {
            "Unsupported foreign code/registration for ${module["unit"]}:${module["module"]}: " +
                "Core schema 2 is archive-only; native stubs, initializers, finalizers and callbacks are not linked"
        }
    }
}
