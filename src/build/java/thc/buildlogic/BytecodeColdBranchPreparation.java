// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import static thc.buildlogic.BytecodeNormalizers.*;

/** Compile unobserved branches generically; interpreter execution alone learns their history. */
public final class BytecodeColdBranchPreparation {
    private BytecodeColdBranchPreparation() {}
    private static final String MARKER = "THC unobserved compiled branch v1";
    private static final String ENTRY = "        private long handleBranchFalse(FrameWithoutBoxing frame, byte[] bc, long bci, long sp) {\n";
    private static final String COLD_ENTRY = ENTRY + """
            // THC unobserved compiled branch v1: consume the real Boolean, not a learned type.
            if (CompilerDirectives.inCompiledCode()) {
                int profileIndex = BYTES.getIntUnaligned(bc, bci + 6 /* imm branch_profile */);
                int[] branchProfiles = ACCESS.uncheckedCast(this.branchProfiles_, int[].class);
                if (ACCESS.readInt(branchProfiles, profileIndex * 2) == 0 &&
                        ACCESS.readInt(branchProfiles, profileIndex * 2 + 1) == 0) {
                    boolean condition = (boolean) FRAMES.getValue(frame, sp - 1);
                    FRAMES.clear(frame, sp - 1);
                    return condition ? bci + 12 : BYTES.getIntUnaligned(bc, bci + 2 /* imm branch_target */);
                }
            }
""";
    private static final String COUNTS = """
            } else {
                t = ACCESS.readInt(branchProfiles, profileIndex * 2);
                f = ACCESS.readInt(branchProfiles, profileIndex * 2 + 1);
""";
    private static final String COLD_COUNTS = COUNTS + """
                // THC unobserved compiled branch v1: no fabricated observations or probabilities.
                if (CompilerDirectives.inCompiledCode() && t == 0 && f == 0) return condition;
""";
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String body(String source, String start, String end) {
        int at = source.indexOf(start), finish = source.indexOf(end, at);
        require(at >= 0 && finish > at && source.indexOf(start, at + 1) < 0, "Changed pinned branch method count");
        return source.substring(at, finish);
    }
    public static String restore(String source, String version) {
        require(VERSION.equals(version), "Review cold branch preparation for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        if (result.contains(MARKER)) {
            result = replaceOnce(result, COLD_ENTRY, ENTRY, "Changed cold branch dispatch");
            result = replaceOnce(result, COLD_COUNTS, COUNTS, "Changed cold branch profile");
        }
        require(!result.contains(MARKER), "Changed cold branch marker count");
        // Independent pinned processor bodies: every retained adaptive, operand,
        // clear, overflow, learned transfer and probability path is checked too.
        require(digest(body(result, "        private boolean handleBranchFalse$slow(", "        private void handlePop$slow("))
                .equals("9cf06ad39cf5871fabb5e6c3b5cc117cbc2a0e176d968a45ef9c640dd2269c50"), "Changed pinned branch dispatch body");
        require(digest(body(result, "        private boolean profileBranch(", "        @Override\n        public Object executeOSR("))
                .equals("f48010c8a228d7f994e285b5fb9529fdf4e3fcec9c674937496ecb609fd8a556"), "Changed pinned branch profile body");
        return newline(source, result);
    }
    public static String transform(String source, String version) {
        String result = unix(restore(source, version), "Mixed generated source newlines");
        result = replaceOnce(result, ENTRY, COLD_ENTRY, "Changed branch dispatch signature");
        result = replaceOnce(result, COUNTS, COLD_COUNTS, "Changed compiled profile counts");
        return newline(source, result);
    }
    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed cold branch preparation accepted");
    }
    public static void check(String generated) {
        String result = transform(generated, VERSION);
        require(transform(result, VERSION).equals(result), "Cold branch preparation is not idempotent");
        require(restore(result, VERSION).equals(restore(generated, VERSION)), "Cold branch restoration changed adaptive code");
        String lf = unix(result, "Mixed generated source newlines");
        require(transform(lf.replace("\n", "\r\n"), VERSION).equals(lf.replace("\n", "\r\n")), "Cold branch CRLF mismatch");
        reject(result, "next-version");
        for (String bad : List.of(result.replace("&& t == 0 && f == 0", "&& t == 0"),
                result.replace("boolean condition = (boolean) FRAMES.getValue", "boolean condition = (boolean) FRAMES.expectObject"),
                result.replace("FRAMES.clear(frame, sp - 1);\n                    return condition", "return condition"),
                result.replace("return condition ? bci + 12", "return condition ? bci + 10"),
                result.replace("/* imm branch_target */", "/* changed target */"),
                result.replace("t = Math.addExact(t, 1)", "t = Math.addExact(t, 2)"),
                result.replace("if (f == 0)", "if (f != 0)"),
                result.replace("(double) t / (double) (t + f)", "0.5"),
                result + COLD_ENTRY, result.replaceFirst("\n", "\r\n"))) reject(bad, VERSION);
    }
}
