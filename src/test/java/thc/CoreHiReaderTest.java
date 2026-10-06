// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreHiReaderTest {
    private static final Path FIXTURE = Path.of("build/native-hi-reader");
    private static Path path(String mode) { return FIXTURE.resolve(mode).resolve("NativeHiFixture.hi"); }

    @Test void indexesActualCompilerInterfaces() throws Exception {
        for (String mode : List.of("normal", "safe", "max", "thin")) {
            var file = CoreHiReader.read(path(mode));
            assertEquals(new CoreHiReader.ModuleId("thc-native-hi-fixture", "NativeHiFixture"), file.module);
            file.requireIdentity("thc-native-hi-fixture", "NativeHiFixture");
            assertNull(file.signatureOf);
            assertEquals(0, file.sourceKind);
            assertTrue(file.strings.containsAll(List.of("entry", "Record", "field", "NativeHiFixture")));
            assertTrue(file.names.stream().anyMatch(name -> name.module().equals(file.module) && name.occurrence().equals("entry") && name.namespace() == 0));
            assertTrue(file.names.stream().anyMatch(name -> name.module().equals(file.module) && name.occurrence().equals("Record") && name.namespace() == 3));
            assertTrue(file.cursor(file.publicSections.get("declarations"), "declarations").count(1) > 0);
            assertNotNull(file.selfRecomp);
            assertTrue(file.extensions.isEmpty());
            if (!mode.equals("thin")) {
                assertTrue(file.strings.contains("privateLoop"));
                assertTrue(file.cursor(file.requireRetainedCore(), "retained Core").count(1) > 0);
                assertThrows(ReadOnlyBufferException.class, () -> file.bytes(file.simplifiedCore).put(0, (byte) 0));
            }
            if (mode.equals("normal")) assertTrue(file.sharedTypes.isEmpty());
            if (mode.equals("max")) assertFalse(file.sharedTypes.isEmpty());
            for (var entry : file.sharedTypes) assertTrue(file.bytes(entry).hasRemaining());
            // Cursors are independent and preserve exactly the selected extent.
            var first = file.cursor(file.publicSections.get("declarations"), "first");
            var second = file.cursor(file.publicSections.get("declarations"), "second");
            int byteValue = first.byteValue();
            assertEquals(byteValue, second.byteValue());
            assertEquals(first.position(), second.position());
        }
    }

    @Test void rejectsThinCoreDemandAndWrongIdentity() throws Exception {
        var file = CoreHiReader.read(path("thin"));
        assertNull(file.simplifiedCore);
        var thin = assertThrows(IllegalArgumentException.class, file::requireRetainedCore);
        assertTrue(thin.getMessage().contains("-fwrite-if-simplified-core"));
        assertTrue(thin.getMessage().contains("thc-native-hi-fixture:NativeHiFixture"));
        var mismatch = assertThrows(IllegalArgumentException.class, () -> file.requireIdentity("other", "NativeHiFixture"));
        assertTrue(mismatch.getMessage().contains("identity mismatch"));
    }

    @Test void rejectsTruncatedAndCorruptEnvelopes() throws Exception {
        byte[] bytes = Files.readAllBytes(path("max"));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            var failure = assertThrows(IllegalArgumentException.class, () -> new CoreHiReader(truncated, "truncated.hi"), "length " + length);
            assertTrue(failure.getMessage().contains("truncated.hi"));
        }
        var corruptions = new ArrayList<byte[]>();
        byte[] magic = bytes.clone(); magic[0] = 0; corruptions.add(magic);
        byte[] version = bytes.clone(); version[5] = '8'; corruptions.add(version);
        byte[] way = bytes.clone(); way[9] = 1; way[10] = 'p'; corruptions.add(way);
        // Four envelope pointers: extension, dictionary, names, shared types.
        for (int offset : new int[]{10, 14, 18, 22}) {
            byte[] pointer = bytes.clone(); Arrays.fill(pointer, offset, offset + 4, (byte) 255); corruptions.add(pointer);
        }
        var envelope = ByteBuffer.wrap(bytes);
        int fs = 14 + envelope.getInt(14), ns = 18 + envelope.getInt(18), ts = 22 + envelope.getInt(22);
        byte[] negativeCount = bytes.clone(); negativeCount[fs] = 0x7f; corruptions.add(negativeCount);
        byte[] badUnit = bytes.clone(); badUnit[ns + 1] = 1; corruptions.add(badUnit);
        byte[] badUtf8 = bytes.clone(); badUtf8[fs + 2] = (byte) 255; corruptions.add(badUtf8);
        byte[] badTypeEnd = bytes.clone(); Arrays.fill(badTypeEnd, ts + 4, ts + 8, (byte) 255); corruptions.add(badTypeEnd);
        var file = CoreHiReader.read(path("max"));
        byte[] badPublic = bytes.clone(); Arrays.fill(badPublic, file.publicInterface.start(), file.publicInterface.start() + 4, (byte) 255); corruptions.add(badPublic);
        for (int index = 0; index < corruptions.size(); index++) {
            byte[] corrupt = corruptions.get(index);
            var failure = assertThrows(IllegalArgumentException.class, () -> new CoreHiReader(corrupt, "corrupt.hi"), "corruption " + index);
            assertTrue(failure.getMessage().contains("corrupt.hi"));
            assertTrue(failure.getMessage().contains("byte"));
        }
    }
}
