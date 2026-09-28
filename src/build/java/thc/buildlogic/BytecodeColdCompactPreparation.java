// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.util.List;
import static thc.buildlogic.BytecodeNormalizers.*;

/** Prepare the immutable copier child without recording a guest specialization. */
public final class BytecodeColdCompactPreparation {
    private BytecodeColdCompactPreparation() {}
    private static final String MARKER = "THC immutable compact child v1";
    private static final String BEFORE = """
            private static final class AddCompact_Node extends Node {

                /**
                 * Source Info: <pre>
                 *   Specialization: {@link AddCompact#execute}
                 *   Parameter: {@link CompactCopyNode} copier</pre> */
                @Child private CompactCopyNode copier_;

                public AddCompact_Node() {
                }

                private Object executeAndSpecialize(VirtualFrame frameValue, boolean arg0Value, Metrics arg1Value, GlobalBinding[] arg2Value, boolean arg3Value, Object arg4Value, Object arg5Value, Object arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 18 /* imm state_0 */));
                    {
                        Node node__ = null;
                        node__ = (this);
                        CompactCopyNode copier__ = this.insert((AddCompact.create(arg1Value, arg2Value, arg3Value)));
                        Objects.requireNonNull(copier__, "A specialization cache returned a default value. The cache initializer must never return a default value for this cache. Use @Cached(neverDefault=false) to allow default values for this cached value or make sure the cache initializer never returns the default value.");
                        VarHandle.storeStoreFence();
                        this.copier_ = copier__;
                        state_0 = state_0 | 0b1 /* add SpecializationActive[BytecodeRoot.AddCompact.execute(VirtualFrame, boolean, Metrics, GlobalBinding[], boolean, Object, Object, Object, Node, CompactCopyNode)] */;
                        BYTES.putShort($bc, $bci + 18, (short) (state_0 & 0xFFFF));
                        return AddCompact.execute(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, node__, copier__);
                    }
                }

                @InliningRoot
                private Object execute(FrameWithoutBoxing frameValue, boolean arg0Value, Metrics arg1Value, GlobalBinding[] arg2Value, boolean arg3Value, Object arg4Value, Object arg5Value, Object arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 18 /* imm state_0 */));
                    if (state_0 != 0 /* is SpecializationActive[BytecodeRoot.AddCompact.execute(VirtualFrame, boolean, Metrics, GlobalBinding[], boolean, Object, Object, Object, Node, CompactCopyNode)] */) {
                        {
                            CompactCopyNode copier__ = this.copier_;
                            if (copier__ != null) {
                                Node node__ = (this);
                                return AddCompact.execute(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, node__, copier__);
                            }
                        }
                    }
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    return executeAndSpecialize(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, $bytecode, $bc, $bci);
                }

            }
        """;
    private static final String AFTER = """
            private static final class AddCompact_Node extends Node {
                // THC immutable compact child v1
                @Child private CompactCopyNode copier_;

                public AddCompact_Node(Metrics metrics, GlobalBinding[] failures, boolean async) {
                    copier_ = insert(AddCompact.create(metrics, failures, async));
                }

                @InliningRoot
                private Object execute(FrameWithoutBoxing frameValue, boolean arg0Value, Metrics arg1Value, GlobalBinding[] arg2Value, boolean arg3Value, Object arg4Value, Object arg5Value, Object arg6Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {
                    return AddCompact.execute(frameValue, arg0Value, arg1Value, arg2Value, arg3Value, arg4Value, arg5Value, arg6Value, this, copier_);
                }
            }
        """;
    private static final String CONSTRUCTION = "                            result[BYTES.getIntUnaligned(bc, bci + 10 /* imm node */)] = insert(new AddCompact_Node());\n                            bci += 20;\n";
    private static final String PREPARED = CONSTRUCTION.replace("new AddCompact_Node()",
        "new AddCompact_Node((Metrics) constants[BYTES.getIntUnaligned(bc, bci + 2 /* imm metrics */)], (GlobalBinding[]) constants[BYTES.getIntUnaligned(bc, bci + 6 /* imm failures */)], BYTES.getShort(bc, bci + 16 /* imm async */) != 0)");
    private static final String OPERANDS = """
                    boolean sharing_ = BYTES.getShort(bc, bci + 14 /* imm sharing */) != 0;
                    Metrics metrics_ = ACCESS.uncheckedCast(ACCESS.readObject(ACCESS.uncheckedCast(this.constants, Object[].class), BYTES.getIntUnaligned(bc, bci + 2 /* imm metrics */)), Metrics.class);
                    GlobalBinding[] failures_ = ACCESS.uncheckedCast(ACCESS.readObject(ACCESS.uncheckedCast(this.constants, Object[].class), BYTES.getIntUnaligned(bc, bci + 6 /* imm failures */)), GlobalBinding[].class);
                    boolean async_ = BYTES.getShort(bc, bci + 16 /* imm async */) != 0;
                    AddCompact_Node node = ACCESS.uncheckedCast(ACCESS.readObject(ACCESS.uncheckedCast(this.cachedNodes_, Node[].class), BYTES.getIntUnaligned(bc, bci + 10 /* imm node */)), AddCompact_Node.class);
        """;

    public static String transform(String source, String version) {
        require(VERSION.equals(version), "Review immutable compact construction for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        boolean prepared = result.contains(MARKER);
        require(result.split("private static final class AddCompact_Node", -1).length == 2,
            "Changed compact generated class");
        result = replaceOnce(result, OPERANDS, OPERANDS, "Changed compact construction operands");
        result = replaceOnce(result, prepared ? AFTER : BEFORE, AFTER, "Changed compact generated body");
        result = replaceOnce(result, prepared ? PREPARED : CONSTRUCTION, PREPARED, "Changed compact construction");
        return newline(source, result);
    }
    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed generated compact source accepted");
    }
    public static void check() {
        String original = BEFORE + CONSTRUCTION + OPERANDS;
        String result = transform(original, VERSION);
        require(!original.equals(result), "Compact transformation absent");
        require(transform(result, VERSION).equals(result), "Compact transformation is not idempotent");
        require(transform(original.replace("\n", "\r\n"), VERSION).equals(result.replace("\n", "\r\n")), "Compact CRLF mismatch");
        require(!result.contains("state_0") && !result.contains("executeAndSpecialize"), "Compact transformation writes specialization history");
        reject(original, "next-version");
        for (String malformed : List.of(original.replace("+ 18", "+ 20"), original.replace("+ 10", "+ 12"),
                original.replace("arg4Value, arg5Value, arg6Value, node__", "arg4Value, arg6Value, arg5Value, node__"),
                original + BEFORE, original + CONSTRUCTION, original.replaceFirst("\n", "\r\n"),
                result.replace("this, copier_", "this, null"), result.replace("failures, async", "failures, false"),
                result.replace("+ 2 /* imm metrics */", "+ 4 /* imm metrics */"),
                result.replace("+ 6 /* imm failures */", "+ 8 /* imm failures */"),
                result.replace("+ 16 /* imm async */", "+ 14 /* imm async */"),
                result.replace("bci += 20", "bci += 22"))) reject(malformed, VERSION);
    }
}
