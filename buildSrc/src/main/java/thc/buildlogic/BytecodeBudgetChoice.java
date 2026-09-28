// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

/** Pinned lowering of the compiler's private, immutable budget-choice pair. */
public final class BytecodeBudgetChoice {
    private BytecodeBudgetChoice() {}
    private static final String MARKER = "THC early immutable budget choice v1";
    static final String OLD = """
                        case Instructions.INLINE_CASE_REGIONS_ :
                            bci = handleInlineCaseRegions_(frame, bc, bci, sp);
                            sp += 1;
                            break;
                        case Instructions.INLINE_CASE_REGIONS$UNBOXED_ :
                            bci = handleInlineCaseRegions$unboxed_(frame, bc, bci, sp);
                            sp += 1;
                            break;
""";
    static final String NEW = """
                        case Instructions.INLINE_CASE_REGIONS_ :
                        case Instructions.INLINE_CASE_REGIONS$UNBOXED_ :
                            // THC early immutable budget choice v1: no operand-stack speculation.
                            if (BYTES.getShort(bc, bci + 6) != Instructions.BRANCH_FALSE_UNPROFILED) {
                                throw new IllegalStateException("Budget choice must precede its unprofiled branch");
                            }
                            bci = $root.useInlineCaseRegions() ? bci + 14
                                            : BYTES.getIntUnaligned(bc, bci + 8);
                            break;
""";
    public static String transform(String source, String version) {
        String message = "Unexpected pinned bytecode budget-choice shape";
        BytecodeNormalizers.require(BytecodeNormalizers.VERSION.equals(version), message);
        String result = BytecodeNormalizers.unix(source, message);
        if (result.contains(MARKER)) result = BytecodeNormalizers.replaceOnce(result, NEW, OLD, message);
        BytecodeNormalizers.require(!result.contains(MARKER), message);
        for (String suffix : new String[]{"", "$unboxed"}) {
            String handler = handler(suffix);
            int at = result.indexOf(handler);
            BytecodeNormalizers.require(at >= 0 && result.indexOf(handler, at + handler.length()) < 0, message);
        }
        BytecodeNormalizers.require(result.contains(BytecodeNormalizers.NEW_BRANCH), message);
        return BytecodeNormalizers.newline(source, BytecodeNormalizers.replaceOnce(result, OLD, NEW, message));
    }
    private static String handler(String suffix) {
        return "        private long handleInlineCaseRegions" + suffix + "_(FrameWithoutBoxing frame, byte[] bc, long bci, long sp) {\n" +
                "            InlineCaseRegions_Node node = ACCESS.uncheckedCast(ACCESS.readObject(ACCESS.uncheckedCast(this.cachedNodes_, Node[].class), BYTES.getIntUnaligned(bc, bci + 2 /* imm node */)), InlineCaseRegions_Node.class);\n" +
                "            boolean result = node.execute(this, bc, bci);\n" +
                "            FRAMES." + (suffix.isEmpty() ? "setObject" : "setBoolean") + "(frame, sp, result);\n" +
                "            return bci + 6;\n" +
                "        }\n";
    }
    public static void check() {
        String after = handler("") + handler("$unboxed") + BytecodeNormalizers.NEW_BRANCH;
        String source = "// before\n" + OLD + after;
        String result = transform(source, BytecodeNormalizers.VERSION);
        if (!result.equals("// before\n" + NEW + after))
            throw new AssertionError("Private budget decision was not lowered before operand-stack PE");
        if (!result.equals(transform(result, BytecodeNormalizers.VERSION))) throw new AssertionError("Not idempotent");
        if (!result.replace("\n", "\r\n").equals(transform(source.replace("\n", "\r\n"), BytecodeNormalizers.VERSION)))
            throw new AssertionError("CRLF changed");
        for (String bad : new String[]{source + OLD, source.replace("sp += 1", "sp += 2"),
                source.replace("handleInlineCaseRegions_(", "other_("), result.replace("bci + 8", "bci + 10"),
                source.replace("return bci + 6;", "return bci + 10;"), source.replace("FRAMES.setObject", "FRAMES.setBoolean")}) {
            try { transform(bad, BytecodeNormalizers.VERSION); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("Changed budget dispatch shape accepted");
        }
        try { transform(source, "other"); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unreviewed processor version accepted");
    }
}
