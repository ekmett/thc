// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.util.List;
import static thc.buildlogic.BytecodeNormalizers.*;

/** Construct the immutable interop child without observing a foreign receiver or executing guest code. */
public final class BytecodeColdPolyglotPreparation {
    private BytecodeColdPolyglotPreparation() {}
    private static final String MARKER = "THC immutable storage interop child v1";
    private static final String BEFORE = """
            private static final class PolyglotStorage_Node extends Node {

                /**
                 * Source Info: <pre>
                 *   Specialization: {@link PolyglotStorage#apply}
                 *   Parameter: {@link PolyglotAccess} access</pre> */
                @Child private PolyglotAccess access_;

                public PolyglotStorage_Node() {
                }

                private void executeAndSpecialize(VirtualFrame frameValue, LocalAccessor arg0Value, PolyglotOp arg1Value, Object[] arg2Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 14 /* imm state_0 */));
                    {
                        Node node__ = null;
                        PolyglotAccess access__ = this.insert((PolyglotStorage.createAccess()));
                        Objects.requireNonNull(access__, "A specialization cache returned a default value. The cache initializer must never return a default value for this cache. Use @Cached(neverDefault=false) to allow default values for this cached value or make sure the cache initializer never returns the default value.");
                        VarHandle.storeStoreFence();
                        this.access_ = access__;
                        node__ = (this);
                        state_0 = state_0 | 0b1 /* add SpecializationActive[BytecodeRoot.PolyglotStorage.apply(VirtualFrame, LocalAccessor, PolyglotOp, Object[], PolyglotAccess, Node)] */;
                        BYTES.putShort($bc, $bci + 14, (short) (state_0 & 0xFFFF));
                        PolyglotStorage.apply(frameValue, arg0Value, arg1Value, arg2Value, access__, node__);
                        return;
                    }
                }

                @InliningRoot
                private void execute(FrameWithoutBoxing frameValue, LocalAccessor arg0Value, PolyglotOp arg1Value, Object[] arg2Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 14 /* imm state_0 */));
                    if (state_0 != 0 /* is SpecializationActive[BytecodeRoot.PolyglotStorage.apply(VirtualFrame, LocalAccessor, PolyglotOp, Object[], PolyglotAccess, Node)] */) {
                        {
                            PolyglotAccess access__ = this.access_;
                            if (access__ != null) {
                                Node node__ = (this);
                                PolyglotStorage.apply(frameValue, arg0Value, arg1Value, arg2Value, access__, node__);
                                return;
                            }
                        }
                    }
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    executeAndSpecialize(frameValue, arg0Value, arg1Value, arg2Value, $bytecode, $bc, $bci);
                    return;
                }

            }
        """;
    private static final String AFTER = """
            private static final class PolyglotStorage_Node extends Node {
                // THC immutable storage interop child v1
                @Child private PolyglotAccess access_;

                public PolyglotStorage_Node() {
                    access_ = insert(PolyglotStorage.createAccess());
                }

                @InliningRoot
                private void execute(FrameWithoutBoxing frameValue, LocalAccessor arg0Value, PolyglotOp arg1Value, Object[] arg2Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    PolyglotStorage.apply(frameValue, arg0Value, arg1Value, arg2Value, access_, this);
                }
            }
        """;
    public static String transform(String source, String version) {
        require(VERSION.equals(version), "Review immutable polyglot construction for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        require(result.split("private static final class PolyglotStorage_Node", -1).length == 2,
            "Changed polyglot generated class");
        return newline(source, replaceOnce(result, result.contains(MARKER) ? AFTER : BEFORE, AFTER,
            "Changed polyglot generated body"));
    }
    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed generated polyglot source accepted");
    }
    public static void check() {
        String result = transform(BEFORE, VERSION);
        require(!BEFORE.equals(result), "Polyglot transformation absent");
        require(transform(result, VERSION).equals(result), "Polyglot transformation is not idempotent");
        require(transform(BEFORE.replace("\n", "\r\n"), VERSION).equals(result.replace("\n", "\r\n")), "Polyglot CRLF mismatch");
        require(!result.contains("state_0") && !result.contains("executeAndSpecialize"), "Polyglot transformation writes specialization history");
        reject(BEFORE, "next-version");
        for (String malformed : List.of(BEFORE.replace("+ 14", "+ 16"), BEFORE + BEFORE,
                BEFORE.replace("arg2Value, access__, node__", "arg2Value, null, node__"),
                BEFORE.replaceFirst("\n", "\r\n"), result.replace("access_, this", "null, this"),
                result.replace("insert(PolyglotStorage.createAccess())", "null"))) reject(malformed, VERSION);
    }
}
