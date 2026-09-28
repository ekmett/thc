// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

/** Pinned lowering of the compiler's private, immutable budget-choice pair. */
public final class BytecodeBudgetChoice {
    private BytecodeBudgetChoice() {}
    private static final String MARKER = "THC early immutable budget choice v2";
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
                            // THC early immutable budget choice v2: no operand-stack speculation.
                            if (BYTES.getShort(bc, bci + 6) != Instructions.BRANCH_FALSE) {
                                throw new IllegalStateException("Budget choice must precede its conditional branch");
                            }
                            bci = $root.useInlineCaseRegions() ? bci + 18
                                            : BYTES.getIntUnaligned(bc, bci + 8);
                            break;
""";
    // The stock branch is twelve bytes: opcode, target, profile and operand.
    // Check its complete initial handler before skipping the private immutable pair.
    static final String BRANCH = """
        @EarlyInline
        private long handleBranchFalse(FrameWithoutBoxing frame, byte[] bc, long bci, long sp) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            boolean condition_ = handleBranchFalse$slow(frame, bc, bci, sp, null);
            if (profileBranch(BYTES.getIntUnaligned(bc, bci + 6 /* imm branch_profile */), condition_)) {
                return bci + 12;
            } else {
                return BYTES.getIntUnaligned(bc, bci + 2 /* imm branch_target */);
            }
        }
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
        BytecodeNormalizers.require(result.contains(BRANCH), message);
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
        String after = handler("") + handler("$unboxed") + BRANCH;
        String source = "// before\n" + OLD + after;
        String result = transform(source, BytecodeNormalizers.VERSION);
        if (!result.equals("// before\n" + NEW + after))
            throw new AssertionError("Private budget decision was not lowered before operand-stack PE");
        if (!result.equals(transform(result, BytecodeNormalizers.VERSION))) throw new AssertionError("Not idempotent");
        if (!result.replace("\n", "\r\n").equals(transform(source.replace("\n", "\r\n"), BytecodeNormalizers.VERSION)))
            throw new AssertionError("CRLF changed");
        for (String bad : new String[]{source + OLD, source.replace("sp += 1", "sp += 2"),
                source.replace("handleInlineCaseRegions_(", "other_("), result.replace("bci + 8", "bci + 10"),
                source.replace("return bci + 6;", "return bci + 10;"), source.replace("FRAMES.setObject", "FRAMES.setBoolean"),
                source.replace("return bci + 12;", "return bci + 10;"),
                source.replace("bci + 6 /* imm branch_profile */", "bci + 4 /* imm branch_profile */"),
                source.replace("bci + 2 /* imm branch_target */", "bci + 4 /* imm branch_target */"),
                result.replace("bci + 18", "bci + 14")}) {
            try { transform(bad, BytecodeNormalizers.VERSION); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("Changed budget dispatch shape accepted");
        }
        try { transform(source, "other"); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unreviewed processor version accepted");
    }
}
