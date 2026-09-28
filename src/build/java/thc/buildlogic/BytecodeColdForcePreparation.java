// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import static thc.buildlogic.BytecodeNormalizers.*;

/** Cold compiled forcing uses the existing generic algorithm, without observed specialization state. */
public final class BytecodeColdForcePreparation {
    private BytecodeColdForcePreparation() {}
    private static final String MARKER = "THC immutable cold force v1";
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String constructor(String name, boolean prepared) {
        return "        public " + name + "_Node(" + (prepared ? "Metrics metrics, boolean async" : "") + ") {\n"
            + (prepared ? "            // " + MARKER + "\n            force_force_ = insert(" + name + ".createForce(metrics, async));\n" : "")
            + "        }\n";
    }
    private static String execute(String name) {
        boolean local = name.equals("ForceLocal");
        return "        private Object execute(FrameWithoutBoxing frameValue, Metrics arg0Value, "
            + (local ? "LocalAccessor arg1Value, boolean arg2Value, boolean arg3Value, Object arg4Value" : "boolean arg1Value, Object arg2Value")
            + ", AbstractBytecodeNode $bytecode, byte[] $bc, long $bci) {\n"
            + "            int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + " + (local ? 18 : 12) + " /* imm state_0 */));\n";
    }
    private static String cold(String name) {
        return "            if (CompilerDirectives.inCompiledCode() && state_0 == 0)\n"
            + "                return " + name + ".force(frameValue, arg0Value, "
            + (name.equals("ForceLocal") ? "arg1Value, arg2Value, arg3Value, arg4Value, this, " : "arg1Value, arg2Value, ")
            + "force_force_);\n";
    }
    private static String construction(String name, boolean prepared) {
        boolean local = name.equals("ForceLocal");
        return "                            result[BYTES.getIntUnaligned(bc, bci + " + (local ? 10 : 6) + " /* imm node */)] = insert(new " + name + "_Node("
            + (prepared ? "(Metrics) constants[BYTES.getIntUnaligned(bc, bci + 2 /* imm metrics */)], BYTES.getShort(bc, bci + " + (local ? 16 : 10) + " /* imm async */) != 0" : "")
            + "));\n                            bci += " + (local ? 22 : 16) + ";\n";
    }
    public static String transform(String source, String version) {
        require(VERSION.equals(version), "Review cold forcing for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        for (String name : List.of("ForceValue", "ForceLocal")) {
            String signature = "    private static final class " + name + "_Node extends Node {";
            int start = result.indexOf(signature);
            require(start >= 0 && result.indexOf(signature, start + 1) < 0, "Changed force class count");
            int end = result.indexOf("\n    }\n", start);
            require(end > start, "Missing force class end");
            String body = result.substring(start, end + 7);
            boolean prepared = body.contains(MARKER);
            String original = body;
            String following = "\n" + (name.equals("ForceLocal") ? "                " : "            ") + "Objects.requireNonNull(force__,";
            String allocation = "Force force__ = this.insert((" + name + ".createForce(arg0Value, "
                + (name.equals("ForceLocal") ? "arg3Value" : "arg1Value") + ")));" + following;
            String reused = "Force force__ = this.force_force_;" + following;
            if (prepared) {
                original = replaceOnce(original, constructor(name, true), constructor(name, false), "Changed force constructor");
                original = replaceOnce(original, execute(name) + cold(name), execute(name), "Changed cold force selection");
                original = replaceOnce(original, reused, allocation, "Changed interpreter force child");
            }
            // Independent pinned processor output, before this transformation. This
            // also checks every retained adaptive specialization and quickening path.
            String expected = name.equals("ForceLocal")
                ? "a9f0b39dd432a0092531d97caa3d7480d4a84572e429a261a11848ffd13a9a4d"
                : "73a352bbf53eb65456c819fa2969a94da2915b3998ef2b232e77e971e74a5eb4";
            require(digest(original).equals(expected), "Changed pinned " + name + " body");
            String after = replaceOnce(original, constructor(name, false), constructor(name, true), "Changed force constructor");
            after = replaceOnce(after, execute(name), execute(name) + cold(name), "Changed force entry");
            after = replaceOnce(after, allocation, reused, "Changed force allocation");
            result = result.substring(0, start) + after + result.substring(end + 7);
            result = replaceOnce(result, construction(name, prepared), construction(name, true), "Changed force construction operands");
        }
        return newline(source, result);
    }
    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed force preparation accepted");
    }
    public static void check(String generated) {
        String result = transform(generated, VERSION);
        require(transform(result, VERSION).equals(result), "Force preparation is not idempotent");
        String lf = unix(result, "Mixed generated source newlines");
        require(transform(lf.replace("\n", "\r\n"), VERSION).equals(lf.replace("\n", "\r\n")), "Force CRLF mismatch");
        reject(result, "next-version");
        for (String name : List.of("ForceValue", "ForceLocal")) {
            reject(result.replace(constructor(name, true), constructor(name, true).replace("metrics, async", "metrics, false")), VERSION);
            reject(result.replace(cold(name), cold(name).replace("state_0 == 0", "state_0 != 0")), VERSION);
            reject(result.replace(construction(name, true), construction(name, true).replace("imm metrics", "changed")), VERSION);
            reject(result.replace(name + ".number(arg0Value", name + ".floating(arg0Value"), VERSION);
        }
    }
}
