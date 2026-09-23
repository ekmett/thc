package thc

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import java.io.File

/** Real containers/primitive oracle checks. Unsupported frontiers are never execution passes. */
@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    require(args.size == 2 && args[1] in setOf("ast", "bytecode")) {
        "Usage: library-check CASES_JSON ast|bytecode"
    }
    val backend = args[1]
    val cases = Json.parse(File(args[0]).readText()) as Map<String, Any?>
    require((cases["schema"] as Number).toInt() == 1)
    val groups = cases["groups"] as List<Map<String, Any?>>
    require(groups.map { it["id"] }.toSet() == setOf("set", "intmap", "intmap-primops"))
    fun diagnostics(function: Value) = Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>
    fun count(function: Value, name: String) = (diagnostics(function)[name] as Number).toLong()
    for (group in groups) {
        val modules = (group["modules"] as List<String>).also { require(it.isNotEmpty()) }
        val execution = group["execution"] as String
        require(execution in setOf("supported", "diagnostic", "frontier"))
        val diagnostic = execution == "diagnostic"
        val audit = Json.parse(File(group["audit"] as String).readText()) as Map<String, Any?>
        require(audit["accepted"] == (execution == "supported")) { "Static audit disagrees with the declared coverage frontier" }
        val entries = group["entries"] as List<Map<String, Any?>>
        require(entries.isNotEmpty())
        for (entry in entries) {
            val name = entry["name"] as String
            fun rows(key: String): List<Pair<Long, Long>> = (entry[key] as List<List<Number>>).map {
                require(it.size == 2)
                it[0].toLong() to it[1].toLong()
            }
            val warm = rows("warm")
            val cold = rows("cold")
            require(warm.isNotEmpty() && cold.isNotEmpty())
            require(warm.map { it.first }.toSet().intersect(cold.map { it.first }.toSet()).isEmpty())
            val all = warm + cold
            require(all.map { it.first }.toSet().size == all.size)
            fun request(mode: Boolean) = CoreModules.request(modules, name,
                instrument = true, diagnosticUnsupported = mode, backend = backend)
            if (execution == "frontier") {
                executionContext().use { context ->
                    val failure = try {
                        context.eval("thc", request(false))
                        error("Strict loading unexpectedly accepted unsupported entry $name")
                    } catch (exception: PolyglotException) { exception }
                    check(failure.message.orEmpty().contains("Unsupported") ||
                        failure.message.orEmpty().contains("Unresolved") ||
                        failure.message.orEmpty().contains("unsupported")) { "Unexpected loader failure: $failure" }
                    println("LIBRARY_UNSUPPORTED\t$backend\t$name\t${failure.message}")
                }
                continue
            }
            fun checkPolicy(function: Value) {
                val data = diagnostics(function)
                check(data["backend"] == backend)
                check(data["unsupportedPolicy"] == if (diagnostic) "diagnostic-traps" else "reject-at-load")
                val deferred = data["deferredUnsupported"] as List<*>
                check(deferred.isNotEmpty() == diagnostic) { "Unexpected runtime frontier for $name: $deferred" }
                check(count(function, "unsupportedTraps") == 0L) { "Unsupported trap reached by $name" }
                check(count(function, "blackholes") == 0L) { "Unexpected blackhole in $name" }
            }
            fun checkRows(function: Value, rows: List<Pair<Long, Long>>, phase: String, compiled: Boolean) {
                for ((input, expected) in rows) {
                    val before = count(function, "compiledEntries")
                    val actual = function.execute(input).asLong()
                    check(actual == expected) { "$backend $phase $name($input): $actual != native $expected" }
                    if (compiled) check(count(function, "compiledEntries") > before) {
                        "$backend $phase $name($input) did not enter installed guest code"
                    }
                    checkPolicy(function)
                    println("VERIFIED_LIBRARY\t$backend\t$phase\t$name\t$input\t$actual")
                }
            }
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build().use { context ->
                if (diagnostic) {
                    val failure = try {
                        context.eval("thc", request(false))
                        error("Strict loading unexpectedly accepted diagnostic entry $name")
                    } catch (exception: PolyglotException) { exception }
                    println("LIBRARY_STRICT_REJECTION\t$backend\t$name\t${failure.message}")
                }
                val function = context.eval("thc", request(diagnostic))
                checkRows(function, all, "interpreted", compiled = false)
                check(count(function, "compiledEntries") == 0L)
            }
            // Fresh state makes the cold inputs genuinely unseen by this compilation.
            executionContext().use { context ->
                val function = context.eval("thc", request(diagnostic))
                repeat(40) { index ->
                    val (input, expected) = warm[index % warm.size]
                    check(function.execute(input).asLong() == expected)
                }
                checkPolicy(function)
                check(function.invokeMember("compile").asBoolean()) { "Failed guest compilation: $backend $name" }
                checkRows(function, warm, "compiled-warm", compiled = true)
                checkRows(function, cold, "compiled-cold", compiled = true)
                check(function.invokeMember("compile").asBoolean()) { "Failed post-cold compilation request: $backend $name" }
                checkRows(function, all.reversed(), "post-cold-compiled", compiled = true)
                println("LIBRARY_DIAGNOSTICS\t$backend\t$name\t${function.getMember("diagnostics").asString()}")
            }
        }
    }
}
