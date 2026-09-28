// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Independent standard ZIP writer; payload bytes remain the existing test models. */
final class CoreCbdTestSupport {
    private CoreCbdTestSupport() {}
    static byte[] header(byte[] facts, long count, int summaries, int debug) {
        return ByteBuffer.allocate(32 + facts.length).order(ByteOrder.LITTLE_ENDIAN)
            .put("THCCBD1\0".getBytes(StandardCharsets.UTF_8)).putShort((short) 1).putShort((short) 0)
            .putInt(summaries).putLong(count).putInt(debug).putInt(0).put(facts).array();
    }
    static byte[] header() { return header(new byte[0], 0, 0, 0); }
    static byte[] archive(byte[] header, List<byte[]> segments, Set<String> deflated, boolean reverse) throws IOException {
        var names = List.of("header", "data", "strings", "names", "filenames", "line-columns", "symbols");
        var contents = new ArrayList<byte[]>(); contents.add(header); contents.addAll(segments);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (int index = 0; index < Math.min(names.size(), contents.size()); index++) {
                int i = reverse ? Math.min(names.size(), contents.size()) - index - 1 : index;
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
        var names = List.of("header", "data", "strings", "names", "filenames", "line-columns", "symbols");
        var contents = List.of(header(), new byte[]{42}, new byte[0], new byte[0], new byte[0], new byte[0], new byte[0]);
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
