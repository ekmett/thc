// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs(value = OS.LINUX, disabledReason = "Only original Linux stat scalar declarations have native/Core proof")
class PosixStatAbiTest {
    private Map<?, ?> document() throws Exception {
        try (var input = Objects.requireNonNull(PosixStat.class.getResourceAsStream("/thc/native/posix-stat-abi.json"))) {
            return (Map<?, ?>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private PosixStat parse(Object value) { return PosixStat.parse(value, System.getProperty("os.name"), System.getProperty("os.arch")); }
    @Test void nativeLayoutRangesMutableReadsAndPointerCellsAreChecked() throws Exception {
        var document = document();
        var source = Files.readAllBytes(Path.of(System.getProperty("thc.projectRoot"), "src/main/c/posix-stat-abi-probe.c"));
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(source)), document.get("sourceSha256"));
        var abi = parse(document); var fields = (Map<?, ?>) ((Map<?, ?>) document.get("stat")).get("fields");
        var bytes = new byte[(int) abi.getSize()]; var address = ManagedAddress.fromByteArray(bytes);
        for (var entry : fields.entrySet()) {
            var name = (String) entry.getKey(); var field = (Map<?, ?>) entry.getValue();
            int offset = ((Number) field.get("offset")).intValue(), width = ((Number) field.get("width")).intValue();
            Arrays.fill(bytes, (byte) 0); assertEquals(0L, abi.field(name, address));
            for (int i = 0; i < width; i++) bytes[offset + i] = -1;
            assertEquals(width == 8 ? -1L : 0xffffffffL, abi.field(name, address));
            assertThrows(RuntimeFault.class, () -> abi.field(name, ManagedAddress.fromByteArray(Arrays.copyOf(bytes, offset + width - 1))));
            assertThrows(RuntimeFault.class, () -> abi.field(name, ManagedAddress.nullAddress()));
        }
        var pinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(abi.getSize(), 8));
        var dev = (Map<?, ?>) fields.get("st_dev"); long offset = ((Number) dev.get("offset")).longValue();
        pinned.writeAddressElementIndex(offset / 8, address);
        assertThrows(RuntimeFault.class, () -> abi.field("st_dev", pinned));
        for (long mode : new long[] {-1L, 1L << 32, Long.MIN_VALUE, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> abi.isType("regular", mode));
    }
    @Test void malformedHostLayoutAndMaskReceiptsFailClosed() throws Exception {
        var document = document(); parse(document);
        for (var wrong : List.of(list("schema", true), list("schema", 1.0), list("system", "Darwin"), list("architecture", "riscv64"),
            list("target", "x86_64-unknown-linux-gnux32"), list("target", "x86_64-unknown-linux-musl")))
            assertThrows(RuntimeFault.class, () -> parse(with(document, wrong.get(0), wrong.get(1))));
        var stat = (Map<?, ?>) document.get("stat");
        for (var wrong : List.of(list("size", 0L), list("size", Long.MAX_VALUE), list("alignment", 3L), list("alignment", 8.0), list("extra", 1L)))
            assertThrows(RuntimeFault.class, () -> parse(with(document, "stat", with(stat, wrong.get(0), wrong.get(1)))));
        var fields = (Map<?, ?>) stat.get("fields");
        for (var entry : fields.entrySet()) for (var wrong : List.of(list("width", 1L), list("width", true), list("offset", -1L),
            list("offset", Long.MAX_VALUE), list("offset", 0.0), list("extra", 0L))) {
            var mutated = with(fields, entry.getKey(), with((Map<?, ?>) entry.getValue(), wrong.get(0), wrong.get(1)));
            assertThrows(RuntimeFault.class, () -> parse(with(document, "stat", with(stat, "fields", mutated))));
        }
        var types = (Map<?, ?>) stat.get("types");
        for (var name : types.keySet()) for (var wrong : list(null, true, 1.0, 0L, -1L, 1L << 32))
            assertThrows(RuntimeFault.class, () -> parse(with(document, "stat", with(stat, "types", with(types, name, wrong)))));
        for (var section : List.of("fields", "types")) {
            var values = (Map<?, ?>) stat.get(section);
            for (var key : values.keySet()) assertThrows(RuntimeFault.class, () -> parse(with(document, "stat", with(stat, section, without(values, key)))));
        }
    }
}
