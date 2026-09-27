// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.Json
import java.nio.file.Files
import java.nio.file.Path

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class CoreProcessForeignTest {
    private val root = Path.of(System.getProperty("thc.projectRoot"))
    private fun source(stage: String) = Json.parse(Files.readString(root.resolve("build/process-lifecycle/core/$stage.json")))
    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::calls)
        is List<*> -> (if (value.firstOrNull() == "app" &&
            ((value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>) != null) listOf(value) else emptyList()) + value.flatMap(::calls)
        else -> emptyList()
    }
    private fun validate(call: List<Any?>) = CoreProcessForeign.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>,
        (call[6] as Map<*, *>)["rep"])
    private fun descriptor(call: List<Any?>) = (call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
    private fun copy(call: List<Any?>) = Json.parse(Json.stringify(call)) as MutableList<Any?>

    @Test fun genuineInstalledProcessDeclarationsRetainTheirOwnersAndSafety() {
        val manifest = Json.parse(Files.readString(root.resolve("build/process-lifecycle/core/manifest.json"))) as Map<*, *>
        for (stage in listOf("pre", "post")) {
            val module = source(stage)
            CoreProcessForeign.validateHeads(module)
            val declarations = calls(module)
            assertEquals(4, declarations.size)
            assertEquals(ProcessOp.entries.toSet(), declarations.map { validate(it)!! }.toSet())
            for (call in declarations) {
                val raw = descriptor(call)
                assertEquals(manifest["processUnit"], (raw["target"] as Map<*, *>)["unit"])
                val expected = if ((raw["target"] as Map<*, *>)["symbol"] == "waitForProcess") "interruptible" else "unsafe"
                assertEquals(expected, raw["safety"])
                assertEquals(expected, validate(call)!!.safety)
            }
        }
    }

    @Test fun alteredUnitSafetyConventionArityAndWidthsAreRejected() {
        for (call in calls(source("post"))) {
            fun reject(edit: (MutableList<Any?>) -> Unit) {
                val changed = copy(call).also(edit)
                assertThrows(RuntimeFault::class.java) { validate(changed) }
            }
            for (unit in listOf("main", "process", "process-1.6.25.0-inplace", "other-1.6.26.1-inplace"))
                reject { (descriptor(it)["target"] as MutableMap<String, Any?>)["unit"] = unit }
            for (safety in listOf("safe", "unsafe", "interruptible").filter { it != descriptor(call)["safety"] })
                reject { descriptor(it)["safety"] = safety }
            reject { descriptor(it)["convention"] = "capi" }
            reject { descriptor(it)["arity"] = 99L }
            reject { descriptor(it)["suppliedArity"] = 0L }
            reject { descriptor(it)["schema"] = true }
            reject { (it[3] as MutableList<Any?>)[0] = true }
            reject { ((descriptor(it)["argumentReps"] as List<MutableMap<String, Any?>>)[0])["primReps"] = listOf("IntRep") }
            reject { (descriptor(it)["resultRep"] as MutableMap<String, Any?>)["primReps"] = listOf("Word32Rep") }
        }
    }
}
