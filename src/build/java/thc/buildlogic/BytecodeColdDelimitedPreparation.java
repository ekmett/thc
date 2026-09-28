// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.util.List;
import static thc.buildlogic.BytecodeNormalizers.*;

/** A delimited site's immutable children belong to construction, not its first guest call. */
public final class BytecodeColdDelimitedPreparation {
    private BytecodeColdDelimitedPreparation() {}
    private static final String MARKER = "THC immutable delimited child v1";
    private static final String BEFORE = """
            private static final class DelimitedBoundary_Node extends Node {

                /**
                 * Source Info: <pre>
                 *   Specialization: {@link DelimitedBoundary#invoke}
                 *   Parameter: {@link DelimitedActionSite} site</pre> */
                @Child private DelimitedActionSite site_;

                public DelimitedBoundary_Node() {
                }

                private Object executeAndSpecialize(VirtualFrame frameValue, String arg0Value, TupleShape arg1Value, Language arg2Value, Metrics arg3Value, Object arg4Value, Object arg5Value, Object arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 22 /* imm state_0 */));
                    DelimitedActionSite site__ = this.insert((DelimitedBoundary.create(arg2Value, arg3Value)));
                    Objects.requireNonNull(site__, "A specialization cache returned a default value. The cache initializer must never return a default value for this cache. Use @Cached(neverDefault=false) to allow default values for this cached value or make sure the cache initializer never returns the default value.");
                    VarHandle.storeStoreFence();
                    this.site_ = site__;
                    state_0 = state_0 | 0b1 /* add SpecializationActive[BytecodeRoot.DelimitedBoundary.invoke(VirtualFrame, String, TupleShape, Language, Metrics, Object, Object, Object, DelimitedActionSite)] */;
                    BYTES.putShort($bc, $bci + 22, (short) (state_0 & 0xFFFF));
                    return DelimitedBoundary.invoke(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, site__);
                }

                @InliningRoot
                private Object execute(FrameWithoutBoxing frameValue, String arg0Value, TupleShape arg1Value, Language arg2Value, Metrics arg3Value, Object arg4Value, Object arg5Value, Object arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 22 /* imm state_0 */));
                    if (state_0 != 0 /* is SpecializationActive[BytecodeRoot.DelimitedBoundary.invoke(VirtualFrame, String, TupleShape, Language, Metrics, Object, Object, Object, DelimitedActionSite)] */) {
                        {
                            DelimitedActionSite site__ = this.site_;
                            if (site__ != null) {
                                return DelimitedBoundary.invoke(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, site__);
                            }
                        }
                    }
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    return executeAndSpecialize(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, $bytecode, $bc, $bci);
                }

            }
        """;
    private static final String AFTER = """
            private static final class DelimitedBoundary_Node extends Node {
                // THC immutable delimited child v1
                @Child private DelimitedActionSite site_;

                public DelimitedBoundary_Node(Language language, Metrics metrics) {
                    site_ = insert(DelimitedBoundary.create(language, metrics));
                }

                @InliningRoot
                private Object execute(FrameWithoutBoxing frameValue, String arg0Value, TupleShape arg1Value, Language arg2Value, Metrics arg3Value, Object arg4Value, Object arg5Value, Object arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    return DelimitedBoundary.invoke(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, site_);
                }
            }
        """;
    private static final String CONSTRUCTION = "                            result[BYTES.getIntUnaligned(bc, bci + 18 /* imm node */)] = insert(new DelimitedBoundary_Node());\n                            bci += 24;\n";
    private static final String PREPARED = CONSTRUCTION.replace("new DelimitedBoundary_Node()",
        "new DelimitedBoundary_Node((Language) constants[BYTES.getIntUnaligned(bc, bci + 10 /* imm language */)], (Metrics) constants[BYTES.getIntUnaligned(bc, bci + 14 /* imm metrics */)])");

    public static String transform(String source, String version) {
        require(VERSION.equals(version), "Review immutable delimited construction for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        boolean prepared = result.contains(MARKER);
        require(result.split("private static final class DelimitedBoundary_Node", -1).length == 2,
            "Changed delimited generated class");
        result = replaceOnce(result, prepared ? AFTER : BEFORE, AFTER, "Changed delimited generated body");
        result = replaceOnce(result, prepared ? PREPARED : CONSTRUCTION, PREPARED, "Changed delimited construction operands");
        return newline(source, result);
    }
    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed generated delimited source accepted");
    }
    public static void check() {
        String original = BEFORE + CONSTRUCTION;
        String result = transform(original, VERSION);
        require(!original.equals(result), "Delimited transformation absent");
        require(transform(result, VERSION).equals(result), "Delimited transformation is not idempotent");
        require(transform(original.replace("\n", "\r\n"), VERSION).equals(result.replace("\n", "\r\n")), "Delimited CRLF mismatch");
        require(!result.contains("state_0") && !result.contains("executeAndSpecialize"), "Delimited transformation writes specialization history");
        reject(original, "next-version");
        for (String malformed : List.of(original.replace("+ 22", "+ 24"), original.replace("+ 18", "+ 20"),
                original.replace("arg4Value, arg5Value, arg6Value, site__", "arg4Value, arg6Value, arg5Value, site__"),
                original + BEFORE, original.replaceFirst("\n", "\r\n"), result.replace("arg6Value, site_", "arg6Value, null"),
                result.replace("+ 10", "+ 12"), result.replace("+ 14", "+ 16"), result.replace("bci += 24", "bci += 26")))
            reject(malformed, VERSION);
    }
}
