// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import thc.Json;
import thc.Language;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SuppressWarnings("unchecked")
class WindowsStdioHostAbiTest {
    @BeforeEach void windows() { assumeTrue(WindowsDirectoryStreams.supportedHost()); }

    private Map<String, Object> document() throws Exception {
        try (var input = getClass().getResourceAsStream("/thc/native/stdio-host-abi.json")) {
            assertNotNull(input);
            return (Map<String, Object>) Json.INSTANCE.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private StdioHostAbi parse(Object value) { return StdioHostAbi.parse(value, "Windows", "amd64"); }
    private <K, V> Map<K, V> changed(Map<K, V> value, K key, V replacement) {
        var result = new LinkedHashMap<>(value);
        result.put(key, replacement);
        return result;
    }

    @Test void receiptComesFromTheWindowsProbeAndRetainsCrtWidths() throws Exception {
        var value = document();
        var source = Path.of(System.getProperty("thc.projectRoot"), "src/main/c/windows-stdio-abi-probe.c");
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        assertEquals(hash, value.get("sourceSha256"));
        assertEquals(Map.of("charBits", 8L, "pointer", 8L, "int", 4L, "long", 4L,
            "bool", 1L, "size", 8L, "crtReadResult", 4L, "crtReadCount", 4L), value.get("widths"));
        var abi = parse(value);
        assertEquals(WindowsCodePages.Abi.getErrno().get("EBADF"), abi.error(4));
        assertEquals(WindowsCodePages.Abi.getErrno().get("EINVAL"), abi.error(5));
        var modes = List.of(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END);
        for (int i = 0; i < modes.size(); i++) assertEquals((long) i, abi.seekMode(abi.seekConstant(modes.get(i))));
    }

    @Test void windowsReceiptCannotSupplyPosixCapabilities() throws Exception {
        var value = document();
        var abi = parse(value);
        for (Executable action : List.<Executable>of(abi::requireOpenAbi, () -> abi.openReadable(0),
            () -> abi.openWritable(0), () -> abi.openAppend(0), () -> abi.flagConstant(OriginalStdioOp.F_GETFL),
            abi::getAtFdcwd, abi::getAtRemoveDir, abi::getAtSymlinkNoFollow, abi::getAtEmptyPath, abi::getSiginfoBytes))
            assertThrows(RuntimeFault.class, action);
        for (var field : List.of("open", "at", "siginfoBytes"))
            assertThrows(RuntimeFault.class, () -> parse(changed(value, field, 0L)));
        for (var system : List.of("Linux", "Darwin"))
            assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(value, system, "x86_64"));
    }

    @Test void wrongWidthsPlatformsProfilesAndTargetsFailClosed() throws Exception {
        var value = document();
        var widths = (Map<String, Object>) value.get("widths");
        for (var field : widths.keySet()) {
            for (var wrong : Arrays.asList(null, true, 0, -1, 8.0, "4", 16L))
                assertThrows(RuntimeFault.class, () -> parse(changed(value, "widths", changed(widths, field, wrong))));
            var missing = new LinkedHashMap<>(widths);
            missing.remove(field);
            assertThrows(RuntimeFault.class, () -> parse(changed(value, "widths", missing)));
        }
        for (var field : List.of("long", "crtReadResult", "crtReadCount"))
            assertThrows(RuntimeFault.class, () -> parse(changed(value, "widths", changed(widths, field, 8L))));
        for (var wrong : List.of(Map.entry("schema", 1L), Map.entry("profile", "posix"), Map.entry("system", "Linux"),
            Map.entry("architecture", "aarch64"), Map.entry("target", "x86_64-pc-windows-msvc19.33.0"),
            Map.entry("target", "x86_64-unknown-linux-gnu")))
            assertThrows(RuntimeFault.class, () -> parse(changed(value, wrong.getKey(), wrong.getValue())));
    }

    @Test void malformedErrnoAndSeekConstantsReject() throws Exception {
        var value = document();
        for (var section : List.of("errno", "seek")) {
            var fields = (Map<String, Object>) value.get(section);
            for (var field : fields.keySet()) {
                for (var wrong : Arrays.asList(null, true, "9", 9.0, (long) Integer.MAX_VALUE + 1))
                    assertThrows(RuntimeFault.class, () -> parse(changed(value, section, changed(fields, field, wrong))));
                var missing = new LinkedHashMap<>(fields);
                missing.remove(field);
                assertThrows(RuntimeFault.class, () -> parse(changed(value, section, missing)));
            }
            assertThrows(RuntimeFault.class, () -> parse(changed(value, section, changed(fields, "extra", 9L))));
        }
        var seek = (Map<String, Object>) value.get("seek");
        assertThrows(RuntimeFault.class, () -> parse(changed(value, "seek", changed(seek, "SEEK_CUR", seek.get("SEEK_SET")))));
    }

    @Test void contextDescriptorsKeepOffsetsEofAndStickyErrnoWithoutHostFileAuthority() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("thc").in(new ByteArrayInputStream(new byte[] {3, 7})).out(output).build()) {
            context.initialize("thc");
            context.enter();
            try {
                var stdio = Language.currentState(null).getStdio$org_intelligence_thc();
                var bytes = new byte[6];
                Arrays.fill(bytes, (byte) 91);
                var alias = ManagedAddress.fromByteArray(bytes).plus(2);
                assertEquals(-1L, stdio.close(-1));
                assertEquals(parse(document()).error(4), stdio.errno());
                var error = stdio.errno();
                assertEquals(2L, stdio.read(0, alias, 3));
                assertEquals(0L, stdio.read(0, alias, 3));
                assertEquals(2L, stdio.write(1, alias, 2));
                assertArrayEquals(new byte[] {91, 91, 3, 7, 91, 91}, bytes);
                assertArrayEquals(new byte[] {3, 7}, output.toByteArray());
                assertEquals(error, stdio.errno());
                assertThrows(RuntimeFault.class, () -> stdio.write(1L << 32, alias, 1));
            } finally { context.leave(); }
        }
    }
}
