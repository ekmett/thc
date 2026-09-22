package thc

import org.graalvm.polyglot.Value

/** Same input cycle in both engines. Timing includes the Polyglot host boundary. */
private data class Window(val calls: Long, val checksum: Long, val elapsed: Long)

private fun window(fn: Value, base: Long, seconds: Double, minimumCalls: Long = 0): Window {
    val duration = (seconds * 1_000_000_000).toLong()
    val start = System.nanoTime()
    var calls = 0L
    var checksum = 0L
    do {
        // A complete number of 16-input cycles permits cross-engine checksums
        // even when time-based windows execute different iteration counts.
        repeat(256) {
            checksum += fn.execute(base + (calls and 15)).asLong()
            calls++
        }
    } while (System.nanoTime() - start < duration || calls < minimumCalls)
    return Window(calls, checksum, System.nanoTime() - start)
}

fun main(args: Array<String>) {
    require(args.size >= 4) { "Usage: probe MODULES ENTRY REPETITIONS INPUTBASE, or MODULES ENTRY --steady WARM_SECONDS SAMPLE_SECONDS SAMPLES INPUTBASE" }
    val modules = args[0].split(',')
    val entry = args[1]
    executionContext().use { context ->
        val fn = loadEntry(context, modules, entry, instrument = false)
        if (args[2] == "--steady") {
            require(args.size == 7)
            val warmSeconds = args[3].toDouble()
            val sampleSeconds = args[4].toDouble()
            val samples = args[5].toInt()
            val base = args[6].toLong()
            require(warmSeconds > 0 && sampleSeconds > 0 && samples > 0)
            repeat(200) { fn.execute(base + (it and 15)).asLong() }
            fn.invokeMember("compile") // Throws unless last-tier guest code is installed.
            System.err.println("PHASE WARM BEGIN")
            val minimumWarmCalls = System.getProperty("thc.minimumWarmCalls", "20000").toLong()
            require(minimumWarmCalls > 0)
            val warm = window(fn, base, warmSeconds, minimumCalls = minimumWarmCalls)
            System.err.println("PHASE WARM END calls=${warm.calls} elapsedNs=${warm.elapsed} checksum=${warm.checksum}")
            fn.invokeMember("compile")
            repeat(samples) { sample ->
                System.err.println("PHASE MEASURE ${sample + 1} BEGIN")
                val result = window(fn, base, sampleSeconds)
                System.err.println("PHASE MEASURE ${sample + 1} END")
                println(listOf(entry, sample + 1, result.calls, base, result.checksum, result.elapsed).joinToString("\t"))
            }
            System.err.println("PHASE VERIFY BEGIN")
            fn.invokeMember("compile")
            System.err.println("PHASE VERIFY END guestLastTierInstalled=true")
        } else {
            val repetitions = args[2].toInt()
            val base = args[3].toLong()
            require(repetitions > 0)
            var warmChecksum = 0L
            repeat(200) { warmChecksum += fn.execute(base + (it and 15)).asLong() }
            fn.invokeMember("compile")
            repeat(200) { warmChecksum += fn.execute(base + (it and 15)).asLong() }
            val start = System.nanoTime()
            var checksum = 0L
            repeat(repetitions) { checksum += fn.execute(base + (it and 15)).asLong() }
            val elapsed = System.nanoTime() - start
            println(listOf(entry, repetitions, base, checksum, elapsed).joinToString("\t"))
            System.err.println("warmChecksum=$warmChecksum")
        }
        System.err.println("diagnostics=${fn.getMember("diagnostics").asString()}")
    }
}
