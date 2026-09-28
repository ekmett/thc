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

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class TermiosAbiTest {
    private Map<?, ?> document() throws Exception {
        try (var input = Objects.requireNonNull(TermiosImage.class.getResourceAsStream("/thc/native/termios-abi.json"))) {
            return (Map<?, ?>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private TermiosImage parse(Object value) { return TermiosImage.parse(value, System.getProperty("os.name"), System.getProperty("os.arch")); }
    @Test void completeImagePreflightPointerCellsAndAliasLifetime() throws Exception {
        var doc = document();
        var source = Files.readAllBytes(Path.of(System.getProperty("thc.projectRoot"), "src/main/c/termios-abi-probe.c"));
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(source)), doc.get("sourceSha256"));
        var abi = parse(doc); var bytes = new byte[(int) abi.getSize() + 2]; Arrays.fill(bytes, (byte) 90);
        var address = ManagedAddress.fromByteArray(bytes).plus(1);
        for (long pattern : new long[] {0, 1, 0x80000000L, 0xffffffffL, 0xdeadbeefL}) {
            abi.poke(address, pattern); assertEquals(pattern, abi.lflag(address));
            assertEquals((byte) 90, bytes[0]); assertEquals((byte) 90, bytes[bytes.length - 1]);
        }
        var cc = abi.cc(address); cc.writeWord8(0, 171);
        int offset = ((Number) ((Map<?, ?>) doc.get("termios")).get("ccOffset")).intValue();
        assertEquals((byte) 171, bytes[1 + offset]); var saved = bytes.clone();
        for (long bad : new long[] {-1, 1L << 32, Long.MIN_VALUE, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> abi.poke(address, bad));
        for (var bad : List.of(ManagedAddress.nullAddress(), address.plus(2), ManagedAddress.fromByteArray(new byte[(int) abi.getSize() - 1]))) {
            assertThrows(RuntimeFault.class, () -> abi.lflag(bad)); assertThrows(RuntimeFault.class, () -> abi.poke(bad, 7));
            assertThrows(RuntimeFault.class, () -> abi.cc(bad));
        }
        assertArrayEquals(saved, bytes);
        var pinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(abi.getSize(), 8));
        pinned.writeAddressElementIndex(0, address);
        assertThrows(RuntimeFault.class, () -> abi.lflag(pinned)); assertThrows(RuntimeFault.class, () -> abi.poke(pinned, 7));
        assertThrows(RuntimeFault.class, () -> abi.cc(pinned)); assertSame(address, pinned.readAddressElementIndex(0));
        var readonly = ManagedAddress.fromHex("00".repeat((int) abi.getSize()));
        assertThrows(RuntimeFault.class, () -> abi.poke(readonly, 7)); assertEquals(0L, abi.lflag(readonly));
    }
    @Test void closedProbeSchemaBoundsAndWidthsReject() throws Exception {
        var doc = document(); parse(doc);
        for (var bad : List.of(list("schema", true), list("schema", 1.0), list("system", "Darwin"), list("architecture", "aarch64"),
            list("target", "x86_64-unknown-linux-gnux32")))
            assertThrows(RuntimeFault.class, () -> parse(with(doc, bad.get(0), bad.get(1))));
        var layout = (Map<?, ?>) doc.get("termios");
        for (var key : layout.keySet()) {
            assertThrows(RuntimeFault.class, () -> parse(with(doc, "termios", without(layout, key))));
            for (var bad : list(null, true, 1.0, "1", Long.MAX_VALUE))
                assertThrows(RuntimeFault.class, () -> parse(with(doc, "termios", with(layout, key, bad))));
        }
        for (var bad : List.of(list("extra", 0L), list("size", 0L), list("lflagOffset", -1L), list("ccOffset", -1L),
            list("ccCount", 0L), list("lflagBytes", 8L), list("ccBytes", 4L), list("alignment", 8L),
            list("ccOffset", layout.get("lflagOffset")), list("vmin", layout.get("vtime")), list("sigsetSize", 0L),
            list("sigsetSize", 4097L), list("sigttou", (long) Integer.MIN_VALUE - 1)))
            assertThrows(RuntimeFault.class, () -> parse(with(doc, "termios", with(layout, bad.get(0), bad.get(1)))));
    }
    @Test void signalHeaderFallbackZeroIsValidWithoutChangingTheSignalMask() throws Exception {
        var doc = document(); var layout = (Map<?, ?>) doc.get("termios");
        var abi = parse(with(doc, "termios", with(layout, "sigttou", 0L, "sigBlock", 0L, "sigSetmask", 0L)));
        for (var operation : List.of(OriginalStdioOp.SIGTTOU, OriginalStdioOp.SIG_BLOCK, OriginalStdioOp.SIG_SETMASK))
            assertEquals(0L, abi.constant(operation));
        assertEquals(layout.get("sigsetSize"), abi.constant(OriginalStdioOp.SIZEOF_SIGSET));
    }
}
