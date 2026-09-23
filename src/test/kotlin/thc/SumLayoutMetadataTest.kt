@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/** Exact metadata retains unsupported families alongside bounded result consumers. */
class SumLayoutMetadataTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    @Test fun genuineSumLayoutsEnforceResultCapabilityBoundariesOnBothBackends() {
        val manifest = Json.parse(File(root, "build/sum-layout/provenance.json").readText()) as Map<String, Any?>
        for (item in (manifest["sources"] as List<Map<String, String>>) +
                (manifest["artifacts"] as List<Map<String, String>>)) {
            val relative = File(item.getValue("path"))
            val path = if (relative.isAbsolute) relative else File(root, relative.path)
            val actual = MessageDigest.getInstance("SHA-256").digest(path.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(item["sha256"], actual, "Stale sum metadata evidence: $path")
        }
        val checks = Json.parse(File(root, "build/sum-layout/checks.json").readText()) as Map<String, Any?>
        val stages = checks["coverage"] as List<Map<String, Any?>>
        assertEquals(listOf("pre", "post"), stages.map { it["stage"] })
        assertEquals(130, (checks["nativeRows"] as Number).toInt())
        assertEquals(6, (checks["supportedSumEntries"] as Number).toInt())
        val manifestProof = checks["provenance"] as Map<String, String>
        assertEquals("build/sum-layout/provenance.json", manifestProof["path"])
        assertEquals(manifestProof["sha256"], MessageDigest.getInstance("SHA-256")
            .digest(File(root, manifestProof.getValue("path")).readBytes()).joinToString("") { "%02x".format(it) })
        for (stage in stages) {
            val name = stage["stage"] as String
            assertEquals(17, (stage["exactResultShapes"] as Number).toInt())
            assertEquals(27, (stage["audits"] as List<*>).size)
            val module = Json.parse(File(root, "build/sum-layout/$name-core/SumLayoutAudit.json").readText())
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                for (entry in stage["audits"] as List<Map<String, Any?>>) {
                    val request = Json.stringify(mapOf("modules" to listOf(module), "entry" to entry["entry"],
                        "backend" to backend, "diagnosticUnsupported" to false))
                    if (entry["accepted"] == true) {
                        val target = context.eval("thc", request)
                        File(root, "build/sum-layout/oracle.tsv").forEachLine { row ->
                            val columns = row.split('\t')
                            if (columns[0] == entry["entry"])
                                assertEquals(columns[2].toLong(), target.execute(columns[1].toLong()).asLong(), "$name/$backend/$row")
                        }
                    } else {
                        val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request) }
                        assertTrue(error.message.orEmpty().contains("Unsupported Core aggregate representation:"),
                            "$name/$backend/${entry["entry"]}: ${error.message}")
                    }
                }
            }
        }
    }
}
