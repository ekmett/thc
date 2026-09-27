// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.*;

/** Parser controls usable on Windows; these model receipts are not native ABI evidence. */
@SuppressWarnings("unchecked")
class PosixStdioHostAbiModelTest {
    private static Map<String, Object> document(String system, String arch) {
        var errors = new LinkedHashMap<String, Object>();
        for (var name : List.of("ENOENT", "EACCES", "EEXIST", "EBADF", "EINVAL", "EIO", "ENOTSUP",
                "EBUSY", "EISDIR", "ENOTTY", "ESPIPE", "EMFILE")) errors.put(name, errors.size() + 1000);
        return Map.of("schema", 1, "system", system, "architecture", arch,
            "target", system.equals("Linux") ? arch + "-unknown-linux-gnu" : arch + "-apple-darwin25",
            "widths", Map.of("charBits", 8, "pointer", 8, "int", 4, "bool", 1, "size", 8, "ssize", 8),
            "errno", errors, "seek", Map.of("SEEK_SET", Integer.MIN_VALUE, "SEEK_CUR", 701, "SEEK_END", -901),
            "open", Map.ofEntries(entry("modeBytes", system.equals("Linux") ? 4 : 2),
                entry("O_ACCMODE", 3), entry("O_RDONLY", 0), entry("O_WRONLY", 1), entry("O_RDWR", 2), entry("O_APPEND", 8),
                entry("O_CREAT", 16), entry("O_EXCL", 32), entry("O_BINARY", 0), entry("O_TRUNC", 64), entry("O_NOCTTY", 128),
                entry("O_NONBLOCK", 256), entry("F_GETFL", 3), entry("F_SETFL", 4), entry("F_SETFD", 2), entry("FD_CLOEXEC", 1)),
            "at", Map.of("AT_FDCWD", -1000, "AT_REMOVEDIR", 512, "AT_SYMLINK_NOFOLLOW", 256,
                "AT_EMPTY_PATH", system.equals("Linux") ? 4096 : 0), "siginfoBytes", 128);
    }
    private static Map<String, Object> with(Map<String, Object> value, String key, Object replacement) {
        var result = new HashMap<>(value); result.put(key, replacement); return result;
    }
    private static Map<String, Object> without(Map<String, Object> value, String key) {
        var result = new HashMap<>(value); result.remove(key); return result;
    }
    private static void reject(Object value, String system) {
        assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(value, system, "amd64"));
    }

    @Test void posixModelsPreserveSignedValuesFlagsAndPrivateCategories() {
        for (var system : List.of("Linux", "Darwin")) for (var arch : List.of("x86_64", "aarch64")) {
            var value = document(system, arch);
            var abi = StdioHostAbi.parse(value, system, arch.equals("x86_64") ? "amd64" : "arm64");
            assertEquals(-1000L, abi.getAtFdcwd()); assertEquals(512L, abi.getAtRemoveDir());
            assertEquals(256L, abi.getAtSymlinkNoFollow()); assertEquals(128L, abi.getSiginfoBytes());
            assertEquals(system.equals("Linux") ? 4096L : 0L, abi.getAtEmptyPath());
            if (system.equals("Linux")) assertDoesNotThrow(abi::requireOpenAbi);
            else assertThrows(RuntimeFault.class, abi::requireOpenAbi);
            for (long flags : List.of(0L, 1L, 2L)) {
                assertEquals(flags != 1, abi.openReadable(flags));
                assertEquals(flags != 0, abi.openWritable(flags));
                assertFalse(abi.openAppend(flags)); assertTrue(abi.openAppend(flags | 8));
            }
            assertEquals(256L, abi.flagConstant(OriginalStdioOp.O_NONBLOCK));
            assertThrows(RuntimeFault.class, () -> abi.flagConstant(OriginalStdioOp.ERRNO));
            var operations = List.of(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END);
            for (int mode = 0; mode < operations.size(); mode++)
                assertEquals((long) mode, abi.seekMode(abi.seekConstant(operations.get(mode))));
            assertEquals((long) Integer.MIN_VALUE, abi.seekConstant(OriginalStdioOp.SEEK_SET));
            assertNull(abi.seekMode(0)); assertNull(abi.seekMode(Long.MAX_VALUE));
            assertThrows(RuntimeFault.class, () -> abi.seekConstant(OriginalStdioOp.ERRNO));
            for (long kind = 1; kind <= 10; kind++) assertEquals(kind, abi.privateErrorKind(abi.error(kind)));
            assertEquals(7L, abi.privateErrorKind(abi.notSeekable()));
            assertEquals(6L, abi.privateErrorKind(-1));
            for (long kind : List.of(Long.MIN_VALUE, -1L, 0L, 11L, Long.MAX_VALUE, (1L << 32) + 1))
                assertEquals(abi.error(6), abi.error(kind));
        }
    }

    @Test void posixModelsRejectWrongTypesFieldSetsRangesAndAliasedFlags() {
        for (var system : List.of("Linux", "Darwin")) {
            var value = document(system, "x86_64");
            for (var section : List.of("widths", "errno", "seek", "open", "at")) {
                var fields = (Map<String, Object>) value.get(section);
                for (var field : fields.keySet()) {
                    for (Object wrong : Arrays.asList(null, true, 1.0, "1", (short) 1, (byte) 1, Long.MAX_VALUE, Long.MIN_VALUE))
                        reject(with(value, section, with(fields, field, wrong)), system);
                    reject(with(value, section, without(fields, field)), system);
                }
                reject(with(value, section, with(fields, "extra", 1)), system);
                reject(without(value, section), system);
            }
            for (Object wrong : Arrays.asList(null, false, 128.0, 0, -1, Long.MAX_VALUE)) reject(with(value, "siginfoBytes", wrong), system);
            var open = (Map<String, Object>) value.get("open");
            for (var wrong : List.of(entry("modeBytes", 8), entry("O_ACCMODE", 0), entry("O_RDONLY", 2),
                    entry("O_APPEND", 0), entry("O_APPEND", 1), entry("F_SETFL", 3)))
                reject(with(value, "open", with(open, wrong.getKey(), wrong.getValue())), system);
            var at = (Map<String, Object>) value.get("at");
            for (var field : List.of("AT_FDCWD", "AT_REMOVEDIR", "AT_SYMLINK_NOFOLLOW")) reject(with(value, "at", with(at, field, 0)), system);
            if (system.equals("Linux")) reject(with(value, "at", with(at, "AT_EMPTY_PATH", 0)), system);
            var seek = (Map<String, Object>) value.get("seek");
            reject(with(value, "seek", with(seek, "SEEK_END", seek.get("SEEK_CUR"))), system);
            for (Object wrong : Arrays.asList(null, List.of(), Map.of())) reject(wrong, system);
        }
    }

    @Test void posixModelsRejectOtherPlatformsAndCompilerTargets() {
        for (var system : List.of("Linux", "Darwin")) {
            var value = document(system, "x86_64");
            for (var target : List.of("x86_64-unknown-linux-musl", "x86_64-unknown-linux-gnux32", "wasm32-wasi", "aarch64"))
                reject(with(value, "target", target), system);
            for (var wrong : List.of(entry("schema", 2), entry("architecture", "aarch64"), entry("system", "Windows")))
                reject(with(value, wrong.getKey(), wrong.getValue()), system);
            assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(value, "Windows", "amd64"));
            assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(value, system, "riscv64"));
        }
    }
}
