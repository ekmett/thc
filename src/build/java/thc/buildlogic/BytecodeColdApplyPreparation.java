// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.util.List;
import java.util.stream.Collectors;
import static thc.buildlogic.BytecodeNormalizers.*;

/** Construct immutable call-site children, never observed targets or specialization state. */
public final class BytecodeColdApplyPreparation {
    private BytecodeColdApplyPreparation() {}
    private static final String MARKER = "THC immutable application child v2";
    private static final String ARGS = "arg0Value, arg1Value, arg2Value, arg3Value, arg4Value";
    private static String type(String name) { return name.equals("Apply") ? "int" : "ArgumentLayout"; }
    private static String parameters(String name) {
        return type(name) + " arg0Value, boolean arg1Value, Metrics arg2Value, boolean[] arg3Value, RootCallTarget arg4Value";
    }
    private static String code(String template, String name) {
        String source = template.replace("@NAME@", name).replace("@TYPE@", type(name))
            .replace("@PARAMETERS@", parameters(name)).replace("@ARGS@", ARGS).replace("@MARKER@", MARKER);
        return source.stripTrailing().lines().map(line -> line.isBlank() ? "" : "    " + line)
            .collect(Collectors.joining("\n")) + "\n";
    }
    static String before(String name) {
        return code("""
            private static final class @NAME@_Node extends Node {

                /**
                 * Source Info: <pre>
                 *   Specialization: {@link @NAME@#apply}
                 *   Parameter: {@link PreparedDispatch} dispatch</pre> */
                @Child private PreparedDispatch dispatch_;

                public @NAME@_Node() {
                }

                private Object executeAndSpecialize(VirtualFrame frameValue, @PARAMETERS@, Object arg5Value, Object[] arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 24 /* imm state_0 */));
                    {
                        Node node__ = null;
                        if (arg5Value instanceof Closure) {
                            Closure arg5Value_ = (Closure) arg5Value;
                            node__ = (this);
                            PreparedDispatch dispatch__ = this.insert((@NAME@.createDispatch(@ARGS@)));
                            Objects.requireNonNull(dispatch__, "A specialization cache returned a default value. The cache initializer must never return a default value for this cache. Use @Cached(neverDefault=false) to allow default values for this cached value or make sure the cache initializer never returns the default value.");
                            VarHandle.storeStoreFence();
                            this.dispatch_ = dispatch__;
                            state_0 = state_0 | 0b1 /* add SpecializationActive[BytecodeRoot.@NAME@.apply(VirtualFrame, @TYPE@, boolean, Metrics, boolean[], RootCallTarget, Closure, Object[], Node, PreparedDispatch)] */;
                            BYTES.putShort($bc, $bci + 24, (short) (state_0 & 0xFFFF));
                            return @NAME@.apply(frameValue, @ARGS@, arg5Value_, arg6Value, node__, dispatch__);
                        }
                    }
                    throw new UnsupportedSpecializationException(this, null, @ARGS@, arg5Value, arg6Value);
                }

                @InliningRoot
                private Object execute(FrameWithoutBoxing frameValue, @PARAMETERS@, Closure arg5Value, Object[] arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 24 /* imm state_0 */));
                    if (state_0 != 0 /* is SpecializationActive[BytecodeRoot.@NAME@.apply(VirtualFrame, @TYPE@, boolean, Metrics, boolean[], RootCallTarget, Closure, Object[], Node, PreparedDispatch)] */) {
                        {
                            PreparedDispatch dispatch__ = this.dispatch_;
                            if (dispatch__ != null) {
                                Node node__ = (this);
                                return @NAME@.apply(frameValue, @ARGS@, arg5Value, arg6Value, node__, dispatch__);
                            }
                        }
                    }
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    return executeAndSpecialize(frameValue, @ARGS@, arg5Value, arg6Value, $bytecode, $bc, $bci);
                }

            }
            """, name);
    }
    static String after(String name) {
        return code("""
            private static final class @NAME@_Node extends Node {
                // @MARKER@
                @Child private PreparedDispatch dispatch_;

                public @NAME@_Node(@PARAMETERS@) {
                    dispatch_ = insert(@NAME@.createDispatch(@ARGS@));
                }

                @InliningRoot
                private Object execute(FrameWithoutBoxing frameValue, @PARAMETERS@, Closure arg5Value, Object[] arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    if (arg5Value == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedSpecializationException(this, null, @ARGS@, arg5Value, arg6Value);
                    }
                    return @NAME@.apply(frameValue, @ARGS@, arg5Value, arg6Value, this, dispatch_);
                }
            }
            """, name);
    }
    static String construction(String name, boolean prepared) {
        String first = name.equals("Apply") ? "BYTES.getIntUnaligned(bc, bci + 2 /* imm arity */)"
            : "(ArgumentLayout) constants[BYTES.getIntUnaligned(bc, bci + 2 /* imm layout */)]";
        String operands = !prepared ? "" : first + ", BYTES.getShort(bc, bci + 22 /* imm tail */) != 0, "
            + "(Metrics) constants[BYTES.getIntUnaligned(bc, bci + 6 /* imm metrics */)], "
            + "(boolean[]) constants[BYTES.getIntUnaligned(bc, bci + 10 /* imm evaluatedArguments */)], "
            + "(RootCallTarget) constants[BYTES.getIntUnaligned(bc, bci + 14 /* imm coldTarget */)]";
        return "                            result[BYTES.getIntUnaligned(bc, bci + 18 /* imm node */)] = insert(new "
            + name + "_Node(" + operands + "));\n                            bci += 26;\n";
    }
    public static String transform(String source, String version) {
        require(VERSION.equals(version), "Review immutable Apply construction for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        boolean prepared = result.contains(MARKER);
        for (String name : List.of("Apply", "ApplyCompact")) {
            String expected = prepared ? after(name) : before(name);
            String ctor = construction(name, prepared);
            require(result.split("private static final class " + name + "_Node", -1).length == 2,
                "Changed " + name + " generated class");
            result = replaceOnce(result, expected, after(name), "Changed " + name + " generated body");
            result = replaceOnce(result, ctor, construction(name, true), "Changed " + name + " construction operands");
        }
        require(result.split(MARKER, -1).length == 3, "Changed immutable Apply markers");
        return newline(source, result);
    }
    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed generated Apply source accepted");
    }
    public static void check() {
        String original = before("Apply") + construction("Apply", false) + before("ApplyCompact") + construction("ApplyCompact", false);
        String result = transform(original, VERSION);
        require(!original.equals(result), "Apply transformation absent");
        require(transform(result, VERSION).equals(result), "Apply transformation is not idempotent");
        require(transform(original.replace("\n", "\r\n"), VERSION).equals(result.replace("\n", "\r\n")), "Apply CRLF mismatch");
        require(!result.contains("state_0") && !result.contains("executeAndSpecialize"), "Apply transformation writes specialization history");
        reject(original, "next-version");
        for (String malformed : List.of(original.replace("+ 24", "+ 26"), original.replace("+ 18", "+ 20"),
                original.replace("arg5Value instanceof Closure", "arg5Value != null"), original + before("Apply"),
                original.replaceFirst("\n", "\r\n"), result.replace("this, dispatch_", "this, null"),
                result.replace("+ 22", "+ 24"), result.replace("bci += 26", "bci += 28"))) reject(malformed, VERSION);
    }
}
