// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

class StdioHostAbiTest {
    private Map<?, ?> document() throws Exception {
        try (var input = Objects.requireNonNull(StdioHostAbi.class.getResourceAsStream("/thc/native/stdio-host-abi.json"))) {
            return (Map<?, ?>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private final String system = System.getProperty("os.name").startsWith("Mac") ? "Darwin" : System.getProperty("os.name");
    private final String arch = System.getProperty("os.arch");
    private StdioHostAbi parse(Object value) { return StdioHostAbi.parse(value, system, arch); }
    @Test void actualProbeLoadsWithoutNativeAccessAndMapsPrivateCategoriesToCValues() throws Exception {
        var document = document(); var raw = (Map<?, ?>) document.get("errno");
        var source = Files.readAllBytes(Path.of(System.getProperty("thc.projectRoot"), "src/main/c/stdio-abi-probe.c"));
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(source)), document.get("sourceSha256"));
        var abi = parse(document);
        var names = List.of("ENOENT", "EACCES", "EEXIST", "EBADF", "EINVAL", "EIO", "ENOTSUP", "EBUSY", "EISDIR", "EMFILE");
        for (int i = 0; i < names.size(); i++) {
            long kind = i + 1L; assertEquals(((Number) raw.get(names.get(i))).longValue(), abi.error(kind));
            assertEquals(kind, abi.privateErrorKind(abi.error(kind)));
        }
        assertEquals(7L, abi.privateErrorKind(abi.notSeekable()));
        assertEquals(((Number) raw.get("ENOTTY")).longValue(), abi.notTerminal());
        assertEquals(abi.error(6), abi.error(0)); assertEquals(abi.error(6), abi.error(Long.MAX_VALUE));
        var seek = (Map<?, ?>) document.get("seek");
        var operations = List.of(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END);
        for (int mode = 0; mode < operations.size(); mode++) {
            var operation = operations.get(mode); long constant = ((Number) seek.get(operation.name())).longValue();
            assertEquals(constant, abi.seekConstant(operation)); assertEquals((long) mode, abi.seekMode(constant));
        }
    }
    @Test void seekConstantsAreIndependentSignedCIntsNotPrivateModes() throws Exception {
        var values = Map.of("SEEK_SET", (long) Integer.MIN_VALUE, "SEEK_CUR", 71L, "SEEK_END", -9L);
        var abi = parse(with(document(), "seek", values));
        var operations = List.of(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END);
        for (int mode = 0; mode < operations.size(); mode++) {
            var operation = operations.get(mode);
            assertEquals(values.get(operation.name()), abi.seekConstant(operation));
            assertEquals((long) mode, abi.seekMode(values.get(operation.name())));
        }
        for (long unknown : new long[] {0, 1, 2, Long.MIN_VALUE, Long.MAX_VALUE}) assertNull(abi.seekMode(unknown));
        assertThrows(RuntimeFault.class, () -> abi.seekConstant(OriginalStdioOp.ERRNO));
    }
    @Test void openConstantsPreserveModeWidthAndAccessBitsWithoutInventingFlags() throws Exception {
        var original = document(); var raw = (Map<?, ?>) original.get("open"); var abi = parse(original);
        long read = ((Number) raw.get("O_RDONLY")).longValue(), write = ((Number) raw.get("O_WRONLY")).longValue();
        long both = ((Number) raw.get("O_RDWR")).longValue(), append = ((Number) raw.get("O_APPEND")).longValue();
        assertTrue(abi.openReadable(read)); assertFalse(abi.openWritable(read));
        assertFalse(abi.openReadable(write)); assertTrue(abi.openWritable(write));
        assertTrue(abi.openReadable(both)); assertTrue(abi.openWritable(both));
        assertTrue(abi.openAppend(write | append)); assertFalse(abi.openAppend(write));
        for (var operation : List.of(OriginalStdioOp.O_APPEND, OriginalStdioOp.O_CREAT, OriginalStdioOp.O_NOCTTY,
            OriginalStdioOp.O_NONBLOCK, OriginalStdioOp.O_RDONLY, OriginalStdioOp.O_RDWR, OriginalStdioOp.O_WRONLY,
            OriginalStdioOp.O_EXCL, OriginalStdioOp.O_BINARY, OriginalStdioOp.O_TRUNC,
            OriginalStdioOp.F_GETFL, OriginalStdioOp.F_SETFL, OriginalStdioOp.F_SETFD, OriginalStdioOp.FD_CLOEXEC))
            assertEquals(((Number) raw.get(operation.name())).longValue(), abi.flagConstant(operation));
        assertThrows(RuntimeFault.class, () -> abi.flagConstant(OriginalStdioOp.ERRNO));
        assertThrows(RuntimeFault.class, () -> parse(with(original, "open", with(raw, "F_SETFL", raw.get("F_GETFL")))));
        if (system.equals("Linux")) assertDoesNotThrow(abi::requireOpenAbi);
        for (var field : raw.keySet()) {
            for (var wrong : list(null, true, false, 1.0, "0", -1, 1L << 31))
                assertThrows(RuntimeFault.class, () -> parse(with(original, "open", with(raw, field, wrong))));
            assertThrows(RuntimeFault.class, () -> parse(with(original, "open", without(raw, field))));
        }
        for (var wrong : list(null, List.of(), with(raw, "extra", 0)))
            assertThrows(RuntimeFault.class, () -> parse(with(original, "open", wrong)));
        for (var wrong : List.of(list("modeBytes", 8L), list("O_ACCMODE", 0L), list("O_APPEND", 0L), list("O_RDONLY", both), list("O_APPEND", read | write | both)))
            assertThrows(RuntimeFault.class, () -> parse(with(original, "open", with(raw, wrong.get(0), wrong.get(1)))));
    }
    @Test void seekProbeRejectsMissingExtraDuplicateAndNonCIntFields() throws Exception {
        var original = document(); var seek = (Map<?, ?>) original.get("seek");
        for (var field : seek.keySet()) {
            for (var wrong : list(null, true, false, 1.0, "0", (long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1))
                assertThrows(RuntimeFault.class, () -> parse(with(original, "seek", with(seek, field, wrong))));
            assertThrows(RuntimeFault.class, () -> parse(with(original, "seek", without(seek, field))));
            for (var other : seek.keySet()) if (!Objects.equals(other, field))
                assertThrows(RuntimeFault.class, () -> parse(with(original, "seek", with(seek, field, seek.get(other)))));
        }
        for (var wrong : list(null, List.of(), Map.of(), with(seek, "extra", 0)))
            assertThrows(RuntimeFault.class, () -> parse(with(original, "seek", wrong)));
        assertThrows(RuntimeFault.class, () -> parse(without(original, "seek")));
    }
    @Test void atConstantsComeFromTheHostAndRejectMalformedOrGuestDescriptorValues() throws Exception {
        var original = document(); var at = (Map<?, ?>) original.get("at"); var abi = parse(original);
        assertEquals(at.get("AT_FDCWD"), abi.getAtFdcwd()); assertEquals(at.get("AT_REMOVEDIR"), abi.getAtRemoveDir());
        assertEquals(at.get("AT_SYMLINK_NOFOLLOW"), abi.getAtSymlinkNoFollow()); assertEquals(at.get("AT_EMPTY_PATH"), abi.getAtEmptyPath());
        assertThrows(RuntimeFault.class, () -> parse(with(original, "at", with(at, "AT_SYMLINK_NOFOLLOW", 0))));
        if (system.equals("Linux")) assertThrows(RuntimeFault.class, () -> parse(with(original, "at", with(at, "AT_EMPTY_PATH", 0))));
        assertEquals(-71L, parse(with(original, "at", with(at, "AT_FDCWD", -71L))).getAtFdcwd());
        for (var field : at.keySet()) {
            for (var wrong : list(null, true, 1.0, "0", (long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1))
                assertThrows(RuntimeFault.class, () -> parse(with(original, "at", with(at, field, wrong))));
            assertThrows(RuntimeFault.class, () -> parse(with(original, "at", without(at, field))));
        }
        for (var wrong : List.of(list("AT_FDCWD", 0), list("AT_FDCWD", 3), list("AT_REMOVEDIR", 0), list("AT_REMOVEDIR", -1)))
            assertThrows(RuntimeFault.class, () -> parse(with(original, "at", with(at, wrong.get(0), wrong.get(1)))));
        assertThrows(RuntimeFault.class, () -> parse(without(original, "at")));
        assertThrows(RuntimeFault.class, () -> parse(with(original, "at", with(at, "extra", 0))));
    }
    @Test void signalRecordSizeRequiresPositiveExactNativeSize() throws Exception {
        var original = document(); assertEquals(((Number) original.get("siginfoBytes")).longValue(), parse(original).getSiginfoBytes());
        for (var wrong : list(null, true, false, 128.0, "128", 0, -1, 1L << 31))
            assertThrows(RuntimeFault.class, () -> parse(with(original, "siginfoBytes", wrong)));
        assertThrows(RuntimeFault.class, () -> parse(without(original, "siginfoBytes")));
    }
    @Test void mismatchedPlatformWidthsAndMalformedErrnoReject() throws Exception {
        var original = document();
        for (var wrong : List.of(list("schema", true), list("schema", 1.0), list("schema", 2), list("system", "Windows"),
            list("architecture", "riscv64"), list("target", "x86_64-unknown-linux-gnux32"), list("target", "aarch64"), list("target", null)))
            assertThrows(RuntimeFault.class, () -> parse(with(original, wrong.get(0), wrong.get(1))));
        var widths = (Map<?, ?>) original.get("widths");
        for (var wrong : List.of(list("charBits", 16), list("int", 8), list("bool", 4), list("pointer", 4), list("size", 4), list("ssize", 4),
            list("int", 4.0), list("pointer", null)))
            assertThrows(RuntimeFault.class, () -> parse(with(original, "widths", with(widths, wrong.get(0), wrong.get(1)))));
        var errors = (Map<?, ?>) original.get("errno");
        for (var wrong : list(null, true, 9.0, 0, -1, (long) Integer.MAX_VALUE + 1))
            assertThrows(RuntimeFault.class, () -> parse(with(original, "errno", with(errors, "EBADF", wrong))));
        assertThrows(RuntimeFault.class, () -> parse(with(original, "errno", without(errors, "EBADF"))));
        assertThrows(RuntimeFault.class, () -> parse(with(original, "errno", with(errors, "extra", 1))));
    }
    @Test void everyProbeFieldRequiresAnExactIntegerAndClosedFieldSet() throws Exception {
        var original = document();
        for (var section : List.of("widths", "errno")) {
            var fields = (Map<?, ?>) original.get(section);
            for (var name : fields.keySet()) {
                for (var wrong : list(null, true, false, 4.0, "8", -1, 0, 1L << 31))
                    assertThrows(RuntimeFault.class, () -> parse(with(original, section, with(fields, name, wrong))), section + "/" + name + "/" + wrong);
                assertThrows(RuntimeFault.class, () -> parse(with(original, section, without(fields, name))));
            }
            for (var wrong : list(null, List.of(), Map.of(), with(fields, "extra", 1)))
                assertThrows(RuntimeFault.class, () -> parse(with(original, section, wrong)));
        }
        for (var wrong : list(null, List.of(), Map.of())) assertThrows(RuntimeFault.class, () -> parse(wrong));
    }
    @Test void compilerTargetsMustMatchTheRuntimePlatformWithoutX32OrMuslAliases() throws Exception {
        for (var target : List.of(List.of("Linux", "amd64", "x86_64-unknown-linux-gnu"), List.of("Linux", "arm64", "aarch64-unknown-linux-gnu"),
            List.of("Darwin", "x86_64", "x86_64-apple-darwin25"), List.of("Darwin", "aarch64", "arm64-apple-darwin25"))) {
            var system = target.get(0); var architecture = target.get(1);
            var value = with(document(), "system", system, "architecture", architecture, "target", target.get(2));
            // A synthetic target also needs that target’s AT_EMPTY_PATH availability.
            var consistent = with(value, "at", with((Map<?, ?>) value.get("at"), "AT_EMPTY_PATH", system.equals("Linux") ? 4096L : 0L));
            assertDoesNotThrow(() -> StdioHostAbi.parse(consistent, system, architecture));
            for (var wrong : List.of("x86_64-unknown-linux-gnux32", "x86_64-unknown-linux-musl", "aarch64", "wasm32-wasi"))
                assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(with(consistent, "target", wrong), system, architecture));
            assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(consistent, "Windows", architecture));
            assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(consistent, system, "riscv64"));
        }
    }
}
