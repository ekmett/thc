// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.net.URLClassLoader
import javax.tools.ToolProvider

// Truffle 25.3.4.1 groups argument descriptions, but still emits one enormous
// Instructions.getArguments method. Split only this cold introspection method;
// never the interpreter, operation specializations, or argument descriptions.
class BytecodeMetadataSplitter {
    val version = "25.3.4.1"
    private val signature = "        private static List<Instruction.Argument> getArguments(int opcode, long bci, AbstractBytecodeNode bytecode, byte[] bytecodes, Object[] constants) {\n"
    private val switchStart = "            switch (opcode) {\n"
    private val end = "            }\n            throw CompilerDirectives.shouldNotReachHere(\"Invalid opcode\");\n        }\n"
    private val beginMarker = "        // THC argument-metadata split v1 BEGIN\n"
    private val endMarker = "        // THC argument-metadata split v1 END\n"
    private val caseLine = Regex(" {16}case Instructions\\.[A-Z0-9_$]+ :\\n")
    private val argumentLine = Regex(" {28}new [A-Za-z][A-Za-z0-9]*Argument\\([^;{}\\r\\n]*\\)(,|\\);)\\n")
    private val callArgs = "opcode, bci, bytecode, bytecodes, constants"

    data class Group(val labels: String, val expression: String) {
        val text get() = labels + expression
        val labelCount get() = labels.count { it == '\n' }
    }

    private fun requireShape(condition: Boolean) {
        require(condition) { "Unexpected Truffle argument metadata shape; review gradle/bytecode-metadata.gradle.kts for the pinned processor." }
    }

    // Deliberately not a Java parser: accept exactly the pinned generator's
    // straight-line case groups, rejecting added control flow or fallthrough.
    fun groups(body: String): List<Group> {
        val result = mutableListOf<Group>()
        var offset = 0
        while (offset < body.length) {
            val start = offset
            while (true) {
                val label = caseLine.matchAt(body, offset) ?: break
                offset = label.range.last + 1
            }
            requireShape(offset > start)
            val labels = body.substring(start, offset)
            val expressionStart = offset
            val empty = "                        return List.of();\n"
            val nonempty = "                        return List.of(\n"
            if (body.startsWith(empty, offset)) {
                offset += empty.length
            } else {
                requireShape(body.startsWith(nonempty, offset))
                offset += nonempty.length
                do {
                    val argument = argumentLine.matchAt(body, offset)
                    requireShape(argument != null)
                    offset = argument!!.range.last + 1
                } while (argument.groupValues[1] == ",")
            }
            result += Group(labels, body.substring(expressionStart, offset))
        }
        val labels = result.flatMap { it.labels.lines().filter(String::isNotEmpty) }
        requireShape(labels.isNotEmpty() && labels.size <= 4096 && labels.toSet().size == labels.size)
        return result
    }

    private fun render(groups: List<Group>): String {
        val chunks = mutableListOf<MutableList<Group>>()
        for (group in groups) {
            requireShape(group.text.length <= 32000 && group.labelCount <= 512)
            val last = chunks.lastOrNull()
            if (last == null || last.size >= 64 ||
                last.sumOf { it.text.length } + group.text.length > 32000 ||
                last.sumOf { it.labelCount } + group.labelCount > 512) {
                chunks += mutableListOf(group)
            } else {
                last += group
            }
        }
        requireShape(chunks.size <= 128)
        return buildString {
            append(beginMarker).append(signature).append(switchStart)
            chunks.forEachIndexed { index, chunk ->
                chunk.forEach { append(it.labels) }
                append("                    return getArgumentsThcPart$index($callArgs);\n")
            }
            append(end)
            chunks.forEachIndexed { index, chunk ->
                append('\n').append(signature.replace("getArguments(", "getArgumentsThcPart$index("))
                append(switchStart)
                chunk.forEach { append(it.text) }
                append(end)
            }
            append(endMarker)
        }
    }

    fun transform(source: String, processorVersion: String): String {
        require(processorVersion == version) { "Review the argument metadata splitter before changing Truffle $version to $processorVersion." }
        val unix = source.replace("\r\n", "\n")
        requireShape(!unix.contains('\r'))
        val crlf = source.contains("\r\n")
        requireShape(!crlf || source == unix.replace("\n", "\r\n"))
        val result = transformUnix(unix)
        return if (crlf) result.replace("\n", "\r\n") else result
    }

    private fun helperPattern() = Regex(Regex.escape(signature.substringBefore("getArguments")) + "getArgumentsThcPart[0-9]+" +
        Regex.escape(signature.substringAfter("getArguments") + switchStart) + "([\\s\\S]*?)" + Regex.escape(end))

    fun metadataMap(source: String): Map<String, String> {
        val unix = source.replace("\r\n", "\n")
        val bodies = if (unix.contains(beginMarker)) {
            helperPattern().findAll(unix).map { it.groupValues[1] }.toList()
        } else {
            listOf(unix.substringAfter(signature + switchStart).substringBefore(end))
        }
        return bodies.flatMap { groups(it) }.flatMap { group ->
            group.labels.lines().filter(String::isNotEmpty).map { it to group.expression }
        }.toMap()
    }

    private fun transformUnix(source: String): String {
        val start: Int
        val finish: Int
        val originalGroups: List<Group>
        if (source.contains(beginMarker)) {
            start = source.indexOf(beginMarker)
            finish = source.indexOf(endMarker, start).also { requireShape(it >= 0) } + endMarker.length
            requireShape(source.indexOf(beginMarker, start + 1) < 0 && source.indexOf(endMarker, finish) < 0)
            val region = source.substring(start, finish)
            originalGroups = helperPattern().findAll(region).flatMap { groups(it.groupValues[1]) }.toList()
            requireShape(originalGroups.isNotEmpty() && render(originalGroups) == region)
            groups(originalGroups.joinToString("") { it.text })
        } else {
            requireShape(!source.contains("getArgumentsThcPart") && !source.contains("THC argument-metadata split"))
            start = source.indexOf(signature)
            requireShape(start >= 0 && source.indexOf(signature, start + signature.length) < 0)
            val bodyStart = start + signature.length + switchStart.length
            requireShape(source.startsWith(switchStart, start + signature.length))
            val bodyEnd = source.indexOf(end, bodyStart)
            requireShape(bodyEnd >= 0)
            finish = bodyEnd + end.length
            originalGroups = groups(source.substring(bodyStart, bodyEnd))
        }
        return source.substring(0, start) + render(originalGroups) + source.substring(finish)
    }

    fun testFixture(): String {
        val body = buildString {
            repeat(160) { index ->
                append("                case Instructions.OP_$index :\n")
                if (index % 5 == 0) append("                case Instructions.ALIAS_$index :\n")
                if (index == 7) {
                    append("                        return List.of();\n")
                } else {
                    append("                        return List.of(\n")
                    append("                            new IntegerArgument(ArgumentDescriptorImpl.FIRST, bci + $index, bytecodes),\n")
                    append("                            new IntegerArgument(ArgumentDescriptorImpl.SECOND, bci + 2, bytecodes));\n")
                }
            }
        }
        return buildString {
            append("import java.util.List;\npublic class MetadataFixture {\n")
            append("    static class Instruction { interface Argument {} }\n")
            append("    static class AbstractBytecodeNode {}\n")
            append("    record IntegerArgument(String descriptor, long offset, byte[] bytes) implements Instruction.Argument {\n")
            append("        public String toString() { return descriptor + \":\" + offset + \":\" + java.util.Arrays.toString(bytes); }\n    }\n")
            append("    static class ArgumentDescriptorImpl { static final String FIRST = \"first\", SECOND = \"second\"; }\n")
            append("    static class CompilerDirectives { static RuntimeException shouldNotReachHere(String reason) { return new IllegalArgumentException(reason); } }\n")
            append("    static class Instructions {\n")
            repeat(160) { index ->
                append("        static final int OP_$index = $index;\n")
                if (index % 5 == 0) append("        static final int ALIAS_$index = ${1000 + index};\n")
            }
            append("    }\n").append(signature).append(switchStart).append(body).append(end)
            append("    public static String probe(int opcode) {\n")
            append("        try { return getArguments(opcode, 37, new AbstractBytecodeNode(), new byte[] { 2, 3 }, new Object[0]).toString(); }\n")
            append("        catch (IllegalArgumentException invalid) { return invalid.getMessage(); }\n    }\n}\n")
        }
    }
}

val testBytecodeMetadataSplit = tasks.register("testBytecodeMetadataSplit") {
    group = "verification"
    description = "Check lossless, fail-closed splitting of generated Truffle instruction argument metadata."
    inputs.file("gradle/bytecode-metadata.gradle.kts")
    doLast {
        val splitter = BytecodeMetadataSplitter()
        val original = splitter.testFixture()
        val transformed = splitter.transform(original, splitter.version)
        check(original != transformed)
        check(splitter.transform(transformed, splitter.version) == transformed)
        check(splitter.metadataMap(original) == splitter.metadataMap(transformed))
        check(splitter.transform(original.replace("\n", "\r\n"), splitter.version) == transformed.replace("\n", "\r\n"))
        fun reject(source: String, version: String = splitter.version) {
            check(runCatching { splitter.transform(source, version) }.exceptionOrNull() is IllegalArgumentException)
        }
        reject(original, "next-version")
        reject(original.replace("switch (opcode)", "switch (opcode + 1)"))
        reject(original.replace("case Instructions.OP_1 :", "case Instructions.OP_0 :"))
        reject(original.replace("return List.of();", "break;"))
        reject(original.replace("return List.of();", "return java.util.List.of();"))
        reject(original.replace("case Instructions.OP_1 :", "default :"))
        reject(transformed.replace("return getArgumentsThcPart0", "return getArgumentsThcPart1"))
        reject(transformed.replace("split v1 END", "split v2 END"))
        // Compile and compare every real/fallthrough opcode and invalid-opcode
        // behavior, independently of the transformer's structural checks.
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "A JDK is required." }
        val results = listOf(original, transformed).mapIndexed { index, source ->
            val directory = temporaryDir.resolve("variant$index").apply { mkdirs() }
            val input = directory.resolve("MetadataFixture.java").apply { writeText(source) }
            check(compiler.run(null, null, null, "-d", directory.path, input.path) == 0)
            URLClassLoader(arrayOf(directory.toURI().toURL()), null).use { loader ->
                val probe = loader.loadClass("MetadataFixture").getMethod("probe", Int::class.javaPrimitiveType)
                ((0 until 160) + (0 until 160 step 5).map { 1000 + it } + listOf(-1, 160, 9999)).map {
                    probe.invoke(null, it)
                }
            }
        }
        check(results[0] == results[1])
        logger.lifecycle("Argument metadata split: 192 opcode paths and invalid-opcode controls match; shape/version/idempotence controls passed.")
        // Optional real generated input: read only, useful without rebuilding
        // another worktree's runtime or checking a multi-megabyte fixture in.
        providers.gradleProperty("thc.metadataFixture").orNull?.let { path ->
            val source = file(path).readText()
            val normalized = splitter.transform(source, splitter.version)
            check(splitter.transform(normalized, splitter.version) == normalized)
            val before = splitter.metadataMap(source)
            check(before == splitter.metadataMap(normalized))
            logger.lifecycle("Real generated metadata validated: ${before.size} opcode argument descriptions unchanged ($path).")
        }
    }
}

tasks.named("check") { dependsOn(testBytecodeMetadataSplit) }
tasks.matching { it.name == "kaptKotlin" }.configureEach {
    inputs.file("gradle/bytecode-metadata.gradle.kts")
    doLast {
        val dependency = configurations.getByName("kapt").dependencies.single {
            it.group == "org.graalvm.truffle" && it.name == "truffle-dsl-processor"
        }
        val source = layout.buildDirectory.file("generated/source/kapt/main/thc/runtime/BytecodeRootGen.java").get().asFile
        val before = source.readText()
        val after = BytecodeMetadataSplitter().transform(before, checkNotNull(dependency.version))
        if (before != after) source.writeText(after)
    }
}
