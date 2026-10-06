// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Native indexing of the pinned GHC 9.14.1 interface format. Bodies remain
 * encoded: this reader alone cannot lower or execute Core. Serialization is
 * defined by GHC.Iface.Binary, GHC.Utils.Binary and GHC.Unit.Module.ModIface. */
final class CoreHiReader {
    record ModuleId(String unit, String name) {}
    record ExternalName(ModuleId module, int namespace, String fieldParent, String occurrence) {}
    /** Absolute file offsets, excluding the lazy skip pointer itself. */
    record Section(int start, int end) {}

    private final byte[] bytes;
    private final String source;
    final List<String> strings;
    private final List<Section> stringBytes;
    final List<ExternalName> names;
    final List<Section> sharedTypes;
    final ModuleId module;
    final ModuleId signatureOf;
    final int sourceKind;
    final Section dependencies, publicInterface, topEnvironment, docs, selfRecomp, simplifiedCore;
    final Map<String, Section> publicSections;
    final Map<String, Section> extensions;

    static CoreHiReader read(Path path) throws IOException {
        long length = Files.size(path);
        if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("GHC interface exceeds JVM file size: " + path);
        return new CoreHiReader(Files.readAllBytes(path), path.toString());
    }

    CoreHiReader(byte[] input, String source) {
        bytes = input.clone();
        this.source = source;
        var header = new Cursor(0, bytes.length, "header");
        long magic = header.fixed32();
        header.require(magic == 0x1face64L, "unsupported magic/word size (requires 64-bit GHC)");
        String version = header.string();
        header.require(version.equals("9141"), "unsupported interface version " + version + " (requires GHC 9.14.1)");
        String way = header.string();
        header.require(way.isEmpty(), "unsupported interface way " + way + " (requires vanilla)");
        int ext = header.relative(), fs = header.relative(), ns = header.relative(), ts = header.relative();
        int payload = header.position;
        header.require(payload < ts && ts < ns && ns < fs && fs < ext && ext < bytes.length,
                "invalid interface table order or extent");

        var dictionary = new Cursor(fs, ext, "FastString table");
        int count = dictionary.count(1);
        var strings = new ArrayList<String>(count);
        var stringBytes = new ArrayList<Section>(count);
        for (int i = 0; i < count; i++) {
            int length = dictionary.count(1), start = dictionary.position();
            strings.add(dictionary.utf8(length));
            stringBytes.add(new Section(start, dictionary.position()));
        }
        dictionary.expectEnd();
        this.strings = List.copyOf(strings);
        this.stringBytes = List.copyOf(stringBytes);

        var symbols = new Cursor(ns, fs, "name table");
        count = symbols.count(5);
        var names = new ArrayList<ExternalName>(count);
        for (int i = 0; i < count; i++) {
            ModuleId owner = module(symbols);
            int namespace = symbols.byteValue();
            symbols.require(namespace <= 4, "unknown name namespace " + namespace);
            String parent = namespace == 4 ? fastString(symbols) : null;
            names.add(new ExternalName(owner, namespace, parent, fastString(symbols)));
        }
        symbols.expectEnd();
        this.names = List.copyOf(names);

        var types = new Cursor(ts, ns, "shared type table");
        int countOffset = types.relative();
        var typeCount = new Cursor(countOffset, ns, "shared type count");
        count = typeCount.count(0);
        typeCount.expectEnd();
        types.require(count <= (countOffset - types.position) / 4, "shared type count exceeds table extent");
        var sharedTypes = new ArrayList<Section>(count);
        // GHC's generic type table has a forward count, followed by lazy entries.
        var entries = new Cursor(types.position, countOffset, "shared type entries");
        for (int i = 0; i < count; i++) sharedTypes.add(entries.lazy());
        entries.expectEnd();
        this.sharedTypes = List.copyOf(sharedTypes);

        var iface = new Cursor(payload, ts, "ModIface");
        module = module(iface);
        signatureOf = iface.optional() ? module(iface) : null;
        sourceKind = iface.byteValue();
        iface.require(sourceKind <= 2, "unknown HscSource " + sourceKind);
        iface.unsigned(64); iface.unsigned(64); // interface fingerprint
        dependencies = iface.lazy();
        publicInterface = iface.lazy();
        topEnvironment = iface.lazy();
        docs = iface.optionalLazy();
        selfRecomp = iface.optionalLazy();
        simplifiedCore = iface.optionalLazy();
        iface.expectEnd();

        var pub = cursor(publicInterface, "IfacePublic");
        var sections = new LinkedHashMap<String, Section>();
        for (String name : List.of("exports", "declarations", "fixities", "warnings", "annotations",
                "defaults", "instances", "familyInstances", "rules", "trust", "trustPackage",
                "completeMatches", "abiHashes")) sections.put(name, pub.lazy());
        pub.expectEnd();
        publicSections = Collections.unmodifiableMap(sections);
        extensions = readExtensions(ext);
    }

    Section requireRetainedCore() {
        if (simplifiedCore == null) throw new IllegalArgumentException(source + ": missing retained Core for " + module.unit() + ":" +
                module.name() + "; rebuild with -fwrite-if-simplified-core");
        return simplifiedCore;
    }

    void requireIdentity(String unit, String name) {
        if (!module.equals(new ModuleId(unit, name))) throw new IllegalArgumentException(source + ": interface identity mismatch: expected " +
                unit + ":" + name + ", got " + module.unit() + ":" + module.name());
    }

    /** Independent read-only view; callers cannot mutate the reader's bytes. */
    ByteBuffer bytes(Section section) {
        var cursor = cursor(section, "section");
        return ByteBuffer.wrap(bytes, cursor.position, cursor.end - cursor.position).slice().asReadOnlyBuffer();
    }

    Cursor cursor(Section section, String label) { return new Cursor(section.start(), section.end(), label); }

    String fastString(Cursor cursor) {
        long index = cursor.unsigned(32);
        cursor.require(index < strings.size(), "FastString index out of range: " + index);
        return strings.get((int) index);
    }

    /** Preserve GHC Char boundaries, including two surrogate Chars versus one
     * supplementary Char. Java String cannot represent that distinction. */
    List<Integer> fastStringCodePoints(Cursor cursor) {
        long index = cursor.unsigned(32);
        cursor.require(index < stringBytes.size(), "FastString index out of range: " + index);
        var section = stringBytes.get((int) index);
        var encoded = cursor(section, "Symbol Modified UTF-8");
        var points = new ArrayList<Integer>();
        encoded.decodeUtf8(section.end() - section.start(), points::add);
        return List.copyOf(points);
    }

    static String modifiedUtf8(byte[] encoded) {
        var text = new StringBuilder(encoded.length);
        decodeUtf8(encoded, 0, encoded.length, text::appendCodePoint);
        return text.toString();
    }
    private static void decodeUtf8(byte[] encoded, int start, int end, java.util.function.IntConsumer output) {
        int position = start;
        while (position < end) {
            int offset = position, first = encoded[position++] & 255;
            if (first < 128) { output.accept(first); continue; }
            int extra = first >= 0xc0 && first <= 0xdf ? 1 : first >= 0xe0 && first <= 0xef ? 2 :
                first >= 0xf0 && first <= 0xf4 ? 3 : -1;
            if (extra < 0) throw invalidUtf8(offset, "invalid leading byte");
            int cp = first & (127 >>> (extra + 1));
            for (int i = 0; i < extra; i++) {
                if (position == end) throw invalidUtf8(position, "truncated input");
                int next = encoded[position++] & 255;
                if ((next & 0xc0) != 0x80) throw invalidUtf8(position - 1, "invalid continuation byte");
                cp = (cp << 6) | (next & 63);
            }
            int minimum = extra == 1 ? 0x80 : extra == 2 ? 0x800 : 0x10000;
            if (!(cp >= minimum || extra == 1 && cp == 0) || cp > Character.MAX_CODE_POINT)
                throw invalidUtf8(offset, "invalid code point or overlong encoding");
            output.accept(cp);
        }
    }
    private static IllegalArgumentException invalidUtf8(int offset, String message) {
        return new IllegalArgumentException("Modified UTF-8 at byte " + offset + ": " + message);
    }

    ModuleId module(Cursor cursor) { return new ModuleId(unit(cursor),fastString(cursor)); }
    String unit(Cursor cursor) {
        int tag=cursor.byteValue();
        cursor.require(tag==0,"unsupported unit tag "+tag+" (virtual/hole units require Backpack decoding)");
        return fastString(cursor);
    }

    private Map<String, Section> readExtensions(int start) {
        var cursor = new Cursor(start, bytes.length, "extension fields");
        int count = cursor.count(5);
        var pointers = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < count; i++) {
            String key = cursor.string();
            cursor.require(!pointers.containsKey(key), "duplicate extension field " + key);
            pointers.put(key, cursor.relative());
        }
        var result = new LinkedHashMap<String, Section>();
        for (var entry : pointers.entrySet()) {
            cursor.require(entry.getValue() == cursor.position, "invalid extension payload pointer");
            int length = cursor.count(1), begin = cursor.position;
            cursor.skip(length);
            result.put(entry.getKey(), new Section(begin, cursor.position));
        }
        cursor.expectEnd();
        return Collections.unmodifiableMap(result);
    }

    /** GHC binary primitives. Relative pointers use the pointer's own absolute
     * position as anchor. Counts are checked before allocating JVM carriers. */
    final class Cursor {
        private int position;
        private final int end;
        private final String label;
        Cursor(int start, int end, String label) {
            this.label = label;
            this.end = end;
            position = start;
            require(start >= 0 && end >= start && end <= bytes.length, "invalid section extent");
        }
        int position() { return position; }
        int remaining() { return end - position; }
        void require(boolean valid, String message) {
            if (!valid) throw new IllegalArgumentException(source + ": " + label + " at byte " + position + ": " + message);
        }
        void skip(int length) { require(length >= 0 && length <= remaining(), "truncated input"); position += length; }
        int byteValue() { require(position < end, "truncated input"); return bytes[position++] & 255; }
        long fixed32() {
            return ((long) byteValue() << 24) | ((long) byteValue() << 16) | ((long) byteValue() << 8) | byteValue();
        }
        int relative() {
            int anchor = position;
            long target = anchor + fixed32();
            require(target >= position && target <= end, "relative pointer outside section");
            return (int) target;
        }
        long unsigned(int bits) {
            long value = 0;
            for (int shift = 0; shift < bits; shift += 7) {
                int b = byteValue(), payload = b & 127;
                require(shift + 7 <= bits || payload < (1 << (bits - shift)), "overflowing unsigned LEB128");
                value |= (long) payload << shift;
                if (b < 128) {
                    require(shift == 0 || payload != 0, "noncanonical unsigned LEB128");
                    return value;
                }
            }
            throw new IllegalArgumentException(source + ": " + label + " at byte " + position + ": unterminated unsigned LEB128");
        }
        long signed() {
            long value = 0;
            int previous = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int b = byteValue(), payload = b & 127;
                require(shift != 63 || payload == 0 || payload == 127, "overflowing signed LEB128");
                value |= (long) payload << shift;
                if (b < 128) {
                    require(shift == 0 || !(payload == 0 && (previous & 64) == 0 || payload == 127 && (previous & 64) != 0),
                            "noncanonical signed LEB128");
                    if (shift < 57 && (b & 64) != 0) value |= -1L << (shift + 7);
                    return value;
                }
                previous = payload;
            }
            throw new IllegalArgumentException(source + ": " + label + " at byte " + position + ": unterminated signed LEB128");
        }
        int count(int minimumBytes) {
            long count = signed();
            require(count >= 0 && count <= Integer.MAX_VALUE && (minimumBytes == 0 || count <= remaining() / minimumBytes),
                    "count exceeds section extent");
            return (int) count;
        }
        String string() {
            int count = count(1);
            var text = new StringBuilder(count);
            for (int i = 0; i < count; i++) {
                long cp = unsigned(32);
                require(cp <= Character.MAX_CODE_POINT, "invalid character");
                text.appendCodePoint((int) cp);
            }
            return text.toString();
        }
        /** GHC.Internal.Encoding.UTF8 encodes NUL as C0 80, permits surrogate
         * Char values and uses four bytes for supplementary code points. */
        String utf8(int length) {
            var text = new StringBuilder(length);
            decodeUtf8(length, text::appendCodePoint);
            return text.toString();
        }
        private void decodeUtf8(int length, java.util.function.IntConsumer output) {
            int start = position;
            skip(length);
            try { CoreHiReader.decodeUtf8(bytes, start, position, output); }
            catch (IllegalArgumentException malformed) { require(false, malformed.getMessage()); }
        }
        boolean optional() {
            int tag = byteValue();
            require(tag <= 1, "invalid Maybe tag " + tag);
            return tag == 1;
        }
        Section lazy() {
            int target = relative(), start = position;
            position = target;
            return new Section(start, target);
        }
        Section optionalLazy() { return optional() ? lazy() : null; }
        void expectEnd() { require(position == end, "trailing bytes in section"); }
    }
}
