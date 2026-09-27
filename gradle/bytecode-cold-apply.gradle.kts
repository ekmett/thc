// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

/** A single specialization's child depends only on immutable constant operands,
 * not on observed guest values. Construct it with its owning cached operation.
 * No specialization state bit is seeded or rewritten. All other operations and
 * their ordinary specialization protocols remain byte-for-byte unchanged. */
class BytecodeColdApplyPreparation {
    val version = "25.3.4.1"
    private val marker = "THC immutable application child v1"
    private val dollar = '$'
    private fun type(name: String) = if (name == "Apply") "int" else "ArgumentLayout"
    private fun parameters(name: String) = "${type(name)} arg0Value, boolean arg1Value, Metrics arg2Value, boolean[] arg3Value"
    private val args = "arg0Value, arg1Value, arg2Value, arg3Value"
    private fun executeParameters(name: String) = "FrameWithoutBoxing frameValue, ${parameters(name)}, Closure arg4Value, Object[] arg5Value, AbstractBytecodeNode ${dollar}bytecode, byte[] ${dollar}bc, long ${dollar}bci"
    private fun specialization(name: String) = "BytecodeRoot.$name.apply(VirtualFrame, ${type(name)}, boolean, Metrics, boolean[], Closure, Object[], Node, PreparedDispatch)"
    fun before(name: String): String = """
    private static final class ${name}_Node extends Node {

        /**
         * Source Info: <pre>
         *   Specialization: {@link $name#apply}
         *   Parameter: {@link PreparedDispatch} dispatch</pre> */
        @Child private PreparedDispatch dispatch_;

        public ${name}_Node() {
        }

        private Object executeAndSpecialize(VirtualFrame frameValue, ${parameters(name)}, Object arg4Value, Object[] arg5Value, AbstractBytecodeNode ${dollar}bytecode, byte[] ${dollar}bc, long ${dollar}bci) {
            int state_0 = Short.toUnsignedInt(BYTES.getShort(${dollar}bc, ${dollar}bci + 20 /* imm state_0 */));
            {
                Node node__ = null;
                if (arg4Value instanceof Closure) {
                    Closure arg4Value_ = (Closure) arg4Value;
                    node__ = (this);
                    PreparedDispatch dispatch__ = this.insert(($name.createDispatch($args)));
                    Objects.requireNonNull(dispatch__, "A specialization cache returned a default value. The cache initializer must never return a default value for this cache. Use @Cached(neverDefault=false) to allow default values for this cached value or make sure the cache initializer never returns the default value.");
                    VarHandle.storeStoreFence();
                    this.dispatch_ = dispatch__;
                    state_0 = state_0 | 0b1 /* add SpecializationActive[${specialization(name)}] */;
                    BYTES.putShort(${dollar}bc, ${dollar}bci + 20, (short) (state_0 & 0xFFFF));
                    return $name.apply(frameValue, $args, arg4Value_, arg5Value, node__, dispatch__);
                }
            }
            throw new UnsupportedSpecializationException(this, null, $args, arg4Value, arg5Value);
        }

        @InliningRoot
        private Object execute(${executeParameters(name)}) {
            int state_0 = Short.toUnsignedInt(BYTES.getShort(${dollar}bc, ${dollar}bci + 20 /* imm state_0 */));
            if (state_0 != 0 /* is SpecializationActive[${specialization(name)}] */) {
                {
                    PreparedDispatch dispatch__ = this.dispatch_;
                    if (dispatch__ != null) {
                        Node node__ = (this);
                        return $name.apply(frameValue, $args, arg4Value, arg5Value, node__, dispatch__);
                    }
                }
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return executeAndSpecialize(frameValue, $args, arg4Value, arg5Value, ${dollar}bytecode, ${dollar}bc, ${dollar}bci);
        }

    }
""".trimIndent().lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" } + "\n"

    fun after(name: String): String = """
    private static final class ${name}_Node extends Node {
        // $marker
        @Child private PreparedDispatch dispatch_;

        public ${name}_Node(${parameters(name)}) {
            dispatch_ = insert($name.createDispatch($args));
        }

        @InliningRoot
        private Object execute(${executeParameters(name)}) {
            if (arg4Value == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new UnsupportedSpecializationException(this, null, $args, arg4Value, arg5Value);
            }
            return $name.apply(frameValue, $args, arg4Value, arg5Value, this, dispatch_);
        }
    }
""".trimIndent().lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" } + "\n"

    fun construction(name: String, prepared: Boolean): String {
        val first = if (name == "Apply") "BYTES.getIntUnaligned(bc, bci + 2 /* imm arity */)" else
            "(ArgumentLayout) constants[BYTES.getIntUnaligned(bc, bci + 2 /* imm layout */)]"
        val operands = if (!prepared) "" else "$first, BYTES.getShort(bc, bci + 18 /* imm tail */) != 0, " +
            "(Metrics) constants[BYTES.getIntUnaligned(bc, bci + 6 /* imm metrics */)], " +
            "(boolean[]) constants[BYTES.getIntUnaligned(bc, bci + 10 /* imm evaluatedArguments */)]"
        return "                            result[BYTES.getIntUnaligned(bc, bci + 14 /* imm node */)] = insert(new ${name}_Node($operands));\n" +
            "                            bci += 22;\n"
    }

    fun transform(source: String, processorVersion: String): String {
        require(processorVersion == version) { "Review immutable Apply construction for Truffle $processorVersion" }
        val unix = source.replace("\r\n", "\n")
        require(!unix.contains('\r'))
        val crlf = source.contains("\r\n")
        require(!crlf || source == unix.replace("\n", "\r\n"))
        val prepared = unix.contains(marker)
        var result = unix
        for (name in listOf("Apply", "ApplyCompact")) {
            val expected = if (prepared) after(name) else before(name)
            val ctor = construction(name, prepared)
            require(Regex(Regex.escape(expected)).findAll(result).count() == 1) { "Changed $name generated body" }
            require(Regex(Regex.escape(ctor)).findAll(result).count() == 1) { "Changed $name construction operands" }
            require(Regex("private static final class ${name}_Node").findAll(result).count() == 1)
            if (!prepared) result = result.replace(expected, after(name)).replace(ctor, construction(name, true))
        }
        require(Regex(Regex.escape(marker)).findAll(result).count() == 2)
        return if (crlf) result.replace("\n", "\r\n") else result
    }

    fun fixture() = listOf("Apply", "ApplyCompact").joinToString("") { before(it) + construction(it, false) }
}

val testBytecodeColdApplyPreparation = tasks.register("testBytecodeColdApplyPreparation") {
    group = "verification"
    inputs.file("gradle/bytecode-cold-apply.gradle.kts")
    doLast {
        val patch = BytecodeColdApplyPreparation()
        val original = patch.fixture()
        val after = patch.transform(original, patch.version)
        check(after != original)
        check(patch.transform(after, patch.version) == after)
        check(patch.transform(original.replace("\n", "\r\n"), patch.version) == after.replace("\n", "\r\n"))
        check(!after.contains("state_0") && !after.contains("executeAndSpecialize"))
        fun reject(source: String, version: String = patch.version) {
            check(runCatching { patch.transform(source, version) }.exceptionOrNull() is IllegalArgumentException)
        }
        reject(original, "next-version")
        reject(original.replace("+ 20", "+ 22"))
        reject(original.replace("+ 14", "+ 16"))
        reject(original.replace("arg4Value instanceof Closure", "arg4Value != null"))
        reject(original + patch.before("Apply"))
        reject(original.replaceFirst("\n", "\r\n"))
        reject(after.replace("this, dispatch_", "this, null"))
        reject(after.replace("+ 18", "+ 20"))
        reject(after.replace("bci += 22", "bci += 24"))
        logger.lifecycle("Immutable Apply construction: exact body/operand/version, no state-bit writes, CRLF/idempotence/negative controls passed.")
    }
}

tasks.named("check") { dependsOn(testBytecodeColdApplyPreparation) }
tasks.matching { it.name == "kaptKotlin" }.configureEach {
    inputs.file("gradle/bytecode-cold-apply.gradle.kts")
    doLast {
        val dependency = configurations.getByName("protocolProcessorSources").dependencies.single {
            it.group == "org.graalvm.truffle" && it.name == "truffle-dsl-processor"
        }
        val source = layout.buildDirectory.file("generated/source/kapt/main/thc/runtime/BytecodeRootGen.java").get().asFile
        val before = source.readText()
        val after = BytecodeColdApplyPreparation().transform(before, checkNotNull(dependency.version))
        if (before != after) source.writeText(after)
    }
}
