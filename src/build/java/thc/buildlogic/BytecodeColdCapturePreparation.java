// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import static thc.buildlogic.BytecodeNormalizers.*;

/** Unobserved compiled capture ingress reads the existing layout without seeding history. */
public final class BytecodeColdCapturePreparation {
    private BytecodeColdCapturePreparation() {}
    private static final String MARKER = "THC immutable cold capture v1";
    private static final String EXECUTE =
        "        private Object execute(CaptureLayout arg0Value, int arg1Value, CapturedFrame arg2Value, AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {\n"
        + "            int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 10 /* imm state_0 */));\n";
    private static final String COLD =
        "            // " + MARKER + "\n"
        + "            if (CompilerDirectives.inCompiledCode() && state_0 == 0)\n"
        + "                return arg0Value.read(arg2Value, arg1Value);\n";
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String transform(String source, String version) {
        require(VERSION.equals(version), "Review cold capture ingress for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        String signature = "    private static final class CaptureRead_Node extends Node {";
        int start = result.indexOf(signature);
        require(start >= 0 && result.indexOf(signature, start + 1) < 0, "Changed capture class count");
        int end = result.indexOf("\n    }\n", start);
        require(end > start, "Missing capture class end");
        String original = result.substring(start, end + 7);
        if (original.contains(MARKER))
            original = replaceOnce(original, EXECUTE + COLD, EXECUTE, "Changed cold capture selection");
        // Independently recorded pinned processor body, including every retained
        // primitive specialization, adaptive guard and interpreter quickening path.
        require(digest(original).equals("558839d6205dde1f48aa286516d9309cea6bb43e06796c873b1e02330b0561fe"),
                "Changed pinned CaptureRead body");
        String after = replaceOnce(original, EXECUTE, EXECUTE + COLD, "Changed capture entry");
        return newline(source, result.substring(0, start) + after + result.substring(end + 7));
    }
    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed capture preparation accepted");
    }
    public static void check(String generated) {
        String result = transform(generated, VERSION);
        require(transform(result, VERSION).equals(result), "Capture preparation is not idempotent");
        String lf = unix(result, "Mixed generated source newlines");
        require(transform(lf.replace("\n", "\r\n"), VERSION).equals(lf.replace("\n", "\r\n")), "Capture CRLF mismatch");
        reject(result, "next-version");
        reject(result.replace(COLD, COLD.replace("state_0 == 0", "state_0 != 0")), VERSION);
        reject(result.replace(EXECUTE, EXECUTE.replace("+ 10", "+ 12")), VERSION);
        reject(result.replace(COLD, COLD.replace("arg2Value, arg1Value", "arg2Value, 0")), VERSION);
        reject(result.replace("CaptureRead.number(arg0Value", "CaptureRead.floating(arg0Value"), VERSION);
    }
}
