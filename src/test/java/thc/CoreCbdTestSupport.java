// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** CBD test models use the real encoder; malformed container controls use an independent ZIP writer. */
public final class CoreCbdTestSupport {
    private CoreCbdTestSupport() {}
    private static String fixtureExecutable;

    /** Encode a synthetic test model explicitly; runtime JSON paths are never rewritten. */
    public static Path writeModel(Path output, Map<String, Object> model) throws IOException {
        var input = Files.createTempFile("thc-core-model-", ".json");
        try {
            Files.writeString(input, Json.stringify(model));
            run(List.of(fixtures(), "compact-model", input.toString(), output.toAbsolutePath().toString()));
            return output;
        } finally {
            Files.deleteIfExists(input);
        }
    }

    static synchronized String fixtures() throws IOException {
        if (fixtureExecutable == null) {
            var configured = System.getenv("THC_FIXTURES");
            var prepared = Path.of("build/thc-fixtures.path");
            String executable;
            if (configured != null && !configured.isBlank()) executable = configured;
            else if (Files.isRegularFile(prepared)) executable = Files.readString(prepared).strip();
            else {
                throw new IOException("Missing build/thc-fixtures.path; prepare this test with "
                    + "make fixtures TESTS=<class>, or set THC_FIXTURES to its encoder executable");
            }
            if (executable.isBlank() || !Files.isRegularFile(Path.of(executable)))
                throw new IOException("Build thc-fixtures first or set THC_FIXTURES to its executable: " + executable);
            fixtureExecutable = executable;
        }
        return fixtureExecutable;
    }

    private static String run(List<String> command) throws IOException {
        var log = Files.createTempFile("thc-core-model-", ".log");
        var errors = Files.createTempFile("thc-core-model-", ".err");
        try {
            var process = new ProcessBuilder(command).redirectOutput(log.toFile()).redirectError(errors.toFile()).start();
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    throw new IOException("CBD fixture command timed out: " + command + "\n" + Files.readString(errors));
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("CBD fixture command interrupted", interrupted);
            }
            var text = Files.readString(log);
            if (process.exitValue() != 0)
                throw new IOException("CBD fixture command failed: " + command + "\n" + text + Files.readString(errors));
            return text;
        } finally {
            Files.deleteIfExists(log);
            Files.deleteIfExists(errors);
        }
    }

    static byte[] header(byte[] facts, long count, int summaries, int debug) {
        return header(facts, new byte[0], count, summaries, debug);
    }
    static byte[] header(byte[] facts, byte[] strings, long count, int summaries, int debug) {
        return ByteBuffer.allocate(40 + strings.length + facts.length).order(ByteOrder.LITTLE_ENDIAN)
            .put("THCCBD1\0".getBytes(StandardCharsets.UTF_8)).putShort((short) 1).putShort((short) 2)
            .putInt(summaries).putLong(count).putInt(debug).putInt(0).putLong(strings.length).put(strings).put(facts).array();
    }
    static byte[] header() { return header(new byte[0], 0, 0, 0); }
    static byte[] archive(byte[] header, List<byte[]> segments, Set<String> deflated, boolean reverse) throws IOException {
        var names = List.of("data", "strings", "names", "filenames", "line-columns", "symbols", "header");
        var contents = new ArrayList<>(segments); contents.add(header);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (int index = 0; index < Math.min(names.size(), contents.size()); index++) {
                int i = reverse && index < 6 ? 5 - index : index;
                var name = names.get(i); var bytes = contents.get(i);
                var entry = new ZipEntry(name); entry.setTime(315532800000L);
                if (!deflated.contains(name)) {
                    entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length);
                    var crc = new CRC32(); crc.update(bytes); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private static ByteBuffer fields(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    /** Small ZIP64 model uses sentinel fields despite small actual members. */
    static byte[] zip64(boolean offsetsOnly) {
        var names = List.of("data", "strings", "names", "filenames", "line-columns", "symbols", "header");
        var contents = List.of(new byte[]{42}, new byte[0], new byte[0], new byte[0], new byte[0], new byte[0], header());
        var out = new ByteArrayOutputStream(); var offsets = new ArrayList<Long>();
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i); var bytes = contents.get(i); offsets.add((long) out.size());
            var crc = new CRC32(); crc.update(bytes);
            out.writeBytes(fields(30).putInt(0x04034b50).putShort((short) (offsetsOnly ? 20 : 45))
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) crc.getValue())
                .putInt(offsetsOnly ? bytes.length : -1).putInt(offsetsOnly ? bytes.length : -1)
                .putShort((short) name.length()).putShort((short) (offsetsOnly ? 0 : 20)).array());
            out.writeBytes(name.getBytes(StandardCharsets.UTF_8));
            if (!offsetsOnly) out.writeBytes(fields(20).putShort((short) 1).putShort((short) 16)
                .putLong(bytes.length).putLong(bytes.length).array());
            out.writeBytes(bytes);
        }
        long directory = out.size();
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i); var bytes = contents.get(i); var crc = new CRC32(); crc.update(bytes);
            out.writeBytes(fields(46).putInt(0x02014b50).putShort((short) 45).putShort((short) 45)
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) crc.getValue())
                .putInt(offsetsOnly ? bytes.length : -1).putInt(offsetsOnly ? bytes.length : -1)
                .putShort((short) name.length()).putShort((short) (offsetsOnly ? 12 : 28)).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt(-1).array());
            out.writeBytes(name.getBytes(StandardCharsets.UTF_8));
            if (offsetsOnly) out.writeBytes(fields(12).putShort((short) 1).putShort((short) 8).putLong(offsets.get(i)).array());
            else out.writeBytes(fields(28).putShort((short) 1).putShort((short) 24).putLong(bytes.length)
                .putLong(bytes.length).putLong(offsets.get(i)).array());
        }
        long directorySize = out.size() - directory, record = out.size();
        out.writeBytes(fields(56).putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45)
            .putInt(0).putInt(0).putLong(7).putLong(7).putLong(directorySize).putLong(directory).array());
        out.writeBytes(fields(20).putInt(0x07064b50).putInt(0).putLong(record).putInt(1).array());
        out.writeBytes(fields(22).putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
            .putShort((short) -1).putShort((short) -1).putInt(-1).putInt(-1).putShort((short) 0).array());
        return out.toByteArray();
    }
}
