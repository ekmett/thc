// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreCbdFixtures;
import thc.Language;
import thc.NativeIO;
import thc.PackageScalarLink;
import thc.PackageScalarLinks;
import thc.PackageScalarSignature;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit selected-provider probe. Its input is genuine acquired CBD; it
 * does not fabricate Core, native signatures or descriptor identities. */
@EnabledOnOs(OS.WINDOWS)
class WindowsPackageIoTest {
    @TempDir Path directory;
    private static PackageScalarSignature signature(PackageScalarLink link, String symbol, String safety) {
        var matches = link.getAbi().stream().filter(value -> value.symbol().equals(symbol)
            && value.safety().equals(safety) && !link.getDataSymbols().contains(value.entry())).toList();
        assertEquals(1, matches.size(), "one captured ABI for " + symbol);
        return matches.getFirst();
    }

    private static final class Call extends RootNode {
        @Child private PackageScalarAccess access;
        Call(Language language, PackageScalarLink link, PackageScalarSignature signature) {
            super(language);
            access = new PackageScalarAccess(new PackageScalarCall(link, signature));
        }
        @Override public Object execute(VirtualFrame frame) {
            return access.executeInt(frame.getArguments(), Unit.INSTANCE);
        }
    }

    @Test void selectedCapiReadUsesItsActualCrtDescriptorAndCallerStorage() throws Exception {
        String input = System.getenv("THC_WINDOWS_NATIVE_IO_CBD");
        assertNotNull(input, "prepare the selected GHC.Internal.IO.FD CBD before this explicit probe");
        var module = CoreCbdFixtures.read(Path.of(input));
        var admission = PackageScalarLinks.read(module);
        assertNotNull(admission);
        var link = admission.getLink();
        assertEquals("GHC.Internal.IO.FD", module.get("module"));
        assertEquals("ghc-internal", link.getUnit());
        assertTrue(link.getTarget().startsWith("x86_64-pc-windows-msvc"));
        var open = signature(link, "__hscore_open", "unsafe");
        var read = signature(link,
            "ghczuwrapperZC18ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCzuread", "safe");
        var close = signature(link,
            "ghczuwrapperZC13ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCzuclose", "unsafe");
        assertEquals(List.of("AddrRep", "Int32Rep", "Word16Rep"), open.arguments());
        assertEquals(List.of("Int32Rep", "AddrRep", "Word32Rep"), read.arguments());
        var file = Files.write(directory.resolve("native-file.bin"), new byte[]{37, 91, 122});
        var name = (file.toAbsolutePath() + "\0").getBytes(StandardCharsets.UTF_16LE);
        var write = signature(link,
            "ghczuwrapperZC16ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCzuwrite", "safe");
        try (var context = NativeIO.createContext()) {
            context.enter();
            try {
                var owner = Language.currentState(null);
                var threads = owner.getThreads();
                threads.enterCurrent(null, false, true, null);
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    owner.getPackageCbits().declare(link);
                    var opened = new Call(language, link, open).getCallTarget();
                    var reader = new Call(language, link, read).getCallTarget();
                    var writer = new Call(language, link, write).getCallTarget();
                    var closer = new Call(language, link, close).getCallTarget();
                    var path = PinnedMemory.allocate(name.length, 2);
                    var buffer = PinnedMemory.allocate(8, 8);
                    var pathAddress = ManagedAddress.fromAllocation(path);
                    pathAddress.copyFromByteArray(name, 0, name.length);
                    var address = ManagedAddress.fromAllocation(buffer).plus(3);
                    buffer.writeByte(3, 91);
                    int descriptor = (int) opened.call(pathAddress, 0, (short) 0);
                    assertTrue(descriptor >= 0, "native descriptor=" + descriptor + ", captured CRT errno=" + owner.getStdio().errno());
                    try {
                        assertEquals(0, reader.call(descriptor, address, 0));
                        assertEquals(91, buffer.readByte(3), "zero length leaves caller storage untouched");
                        assertEquals(3, reader.call(descriptor, address, 5), "real native partial read");
                        assertEquals(37, buffer.readByte(3));
                        assertEquals(91, buffer.readByte(4));
                        assertEquals(122, buffer.readByte(5));
                        assertEquals(0, reader.call(descriptor, address, 1), "real native EOF");
                        assertEquals(37, buffer.readByte(3), "EOF leaves caller storage untouched");
                        assertEquals(-1, writer.call(descriptor, address, 1), "real read-only descriptor rejects write");
                        assertEquals(StdioHostAbi.load().error(4), owner.getStdio().errno(), "same-origin CRT EBADF");
                    } finally {
                        assertEquals(0, closer.call(descriptor));
                    }
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
}
