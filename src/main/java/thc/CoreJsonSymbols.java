// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Selected on-disk symbol directory, using UTF-8 rows or fixed MD5/offset records.
 * Binary search touches only candidate records. JSON mapping starts after a hit,
 * and only selected objects are decoded. Mappings pin files, not pathnames;
 * callers must not mutate opened files in place. */
public final class CoreJsonSymbols implements AutoCloseable {
    public enum Format { TEXT, MD5_UTF8_U64LE }
    public static final String MD5_FORMAT = "md5-utf8-u64le-v1";
    public record Statistics(long directoryOpens, long sourceOpens, long directoryMappedBytes, long sourceMappedBytes, long directoryByteReads, long sourceByteReads, long hashBytesScanned, long lookupComparisons, long decodedBindings, long decodedBytes, long decodedModules, long metadataBytes, long verifiedModuleBytes, long physicalMappingOpens, long mappingCacheHits) {
        public long getDirectoryOpens() { return directoryOpens; }
        public long getSourceOpens() { return sourceOpens; }
        public long getDirectoryMappedBytes() { return directoryMappedBytes; }
        public long getSourceMappedBytes() { return sourceMappedBytes; }
        public long getDirectoryByteReads() { return directoryByteReads; }
        public long getSourceByteReads() { return sourceByteReads; }
        public long getHashBytesScanned() { return hashBytesScanned; }
        public long getLookupComparisons() { return lookupComparisons; }
        public long getDecodedBindings() { return decodedBindings; }
        public long getDecodedBytes() { return decodedBytes; }
        public long getDecodedModules() { return decodedModules; }
        public long getMetadataBytes() { return metadataBytes; }
        public long getVerifiedModuleBytes() { return verifiedModuleBytes; }
        public long getPhysicalMappingOpens() { return physicalMappingOpens; }
        public long getMappingCacheHits() { return mappingCacheHits; }
    }
    public static final class Counters {
        public long directoryOpens, sourceOpens, directoryMappedBytes, sourceMappedBytes, directoryByteReads, sourceByteReads, hashBytesScanned, lookupComparisons, decodedBindings, decodedBytes, decodedModules, metadataBytes, verifiedModuleBytes, physicalMappingOpens, mappingCacheHits;
        public synchronized Statistics statistics() { return new Statistics(directoryOpens, sourceOpens, directoryMappedBytes, sourceMappedBytes, directoryByteReads, sourceByteReads, hashBytesScanned, lookupComparisons, decodedBindings, decodedBytes, decodedModules, metadataBytes, verifiedModuleBytes, physicalMappingOpens, mappingCacheHits); }
    }

    public record ModuleSpan(long start, long end, long bindingsStart, long bindingsEnd) {
        public long getStart() { return start; } public long getEnd() { return end; }
        public long getBindingsStart() { return bindingsStart; } public long getBindingsEnd() { return bindingsEnd; }
    }
    public record ValueSpan(long start, long end) {
        public long getStart() { return start; } public long getEnd() { return end; }
    }
    private record Selected(long start, long end, Map<String,Object> binding) {}
    private record Result<T>(T value, Exception failure) {
        T get() { return failure == null ? value : rethrow(failure); }
    }
    private final Path sourcePath, directoryPath;
    private final boolean verifyArtifacts;
    private final String sourceSha256, directorySha256;
    private final CoreFileMappings mappings;
    private final Format format;
    private final Counters counters = new Counters();
    private boolean closed;
    private Mapping directory, source;
    private MessageDigest symbolDigest;
    private final Map<String,Result<Selected>> selected = new HashMap<>();
    private final Map<ModuleSpan,Result<Map<String,Object>>> modules = new HashMap<>();
    private final Map<ValueSpan,Result<Map<String,Object>>> metadata = new HashMap<>();

    public CoreJsonSymbols(Path sourcePath, Path directoryPath) { this(sourcePath, directoryPath, false, null, null, CoreFileMappings.shared, Format.TEXT); }
    public CoreJsonSymbols(Path sourcePath, Path directoryPath, boolean verifyArtifacts, String sourceSha256, String directorySha256) {
        this(sourcePath, directoryPath, verifyArtifacts, sourceSha256, directorySha256, CoreFileMappings.shared, Format.TEXT);
    }
    public CoreJsonSymbols(Path sourcePath, Path directoryPath, boolean verifyArtifacts, String sourceSha256, String directorySha256, CoreFileMappings mappings) {
        this(sourcePath, directoryPath, verifyArtifacts, sourceSha256, directorySha256, mappings, Format.TEXT);
    }
    public CoreJsonSymbols(Path sourcePath, Path directoryPath, boolean verifyArtifacts, String sourceSha256, String directorySha256,
            CoreFileMappings mappings, Format format) {
        this.sourcePath = sourcePath; this.directoryPath = directoryPath; this.verifyArtifacts = verifyArtifacts;
        this.sourceSha256 = sourceSha256; this.directorySha256 = directorySha256; this.mappings = mappings; this.format = format;
    }
    public Counters getCounters() { return counters; }
    public Statistics statistics() { return counters.statistics(); }

    private static final class Mapping implements AutoCloseable {
        final CoreFileMappings.Lease lease;
        final MemorySegment bytes;
        Mapping(CoreFileMappings.Lease lease) { this.lease = lease; bytes = lease.getBytes(); }
        long size() { return bytes.byteSize(); }
        public void close() { lease.close(); }
    }
    private Mapping open(Path path, String expected, boolean sourceFile) {
        if (sourceFile) counters.sourceOpens++; else counters.directoryOpens++;
        try {
            var lease = verifyArtifacts || expected == null ? mappings.acquireUncached(path) : mappings.acquire(path, expected);
            if (lease.getOpened()) counters.physicalMappingOpens++; else counters.mappingCacheHits++;
            var mapping = new Mapping(lease);
            if (sourceFile) counters.sourceMappedBytes += mapping.size(); else counters.directoryMappedBytes += mapping.size();
            try {
                if (verifyArtifacts) {
                    require(expected != null && expected.matches("[0-9a-f]{64}"), "Explicit symbol verification requires SHA-256 for " + path);
                    var digest = digest("SHA-256");
                    long at = 0;
                    while (at < mapping.size()) {
                        long length = Math.min(8192, mapping.size() - at);
                        digest.update(mapping.bytes.asSlice(at, length).asByteBuffer());
                        at += length;
                    }
                    counters.hashBytesScanned += mapping.size();
                    require(HexFormat.of().formatHex(digest.digest()).equals(expected), "Core symbol artifact hash mismatch: " + path);
                }
                return mapping;
            } catch (Throwable failure) { mapping.close(); return rethrow(failure); }
        } catch (java.io.IOException failure) { return rethrow(failure); }
    }
    private Mapping directory() { if (directory == null) directory = open(directoryPath, directorySha256, false); return directory; }
    private Mapping source() { if (source == null) source = open(sourcePath, sourceSha256, true); return source; }
    private int directoryByte(Mapping mapping, long at) { counters.directoryByteReads++; return mapping.bytes.get(ValueLayout.JAVA_BYTE, at) & 255; }
    private int sourceByte(Mapping mapping, long at) { counters.sourceByteReads++; return mapping.bytes.get(ValueLayout.JAVA_BYTE, at) & 255; }
    public boolean containsSymbol(String id) {
        synchronized (counters) {
            check(!closed, "Core unit sources are closed");
            return (format != Format.TEXT || !id.isEmpty() && id.indexOf('\n') < 0 && id.indexOf('\r') < 0) && lookup(id) != null;
        }
    }
    private Long lookup(String id) {
        if (format == Format.MD5_UTF8_U64LE) return lookupDigest(id);
        require(!id.isEmpty() && id.indexOf('\n') < 0 && id.indexOf('\r') < 0, "Invalid line-based Core symbol");
        byte[] key = id.getBytes(StandardCharsets.UTF_8);
        var mapped = directory();
        long low = 0, high = mapped.size();
        while (low < high) {
            long start = low + (high - low) / 2;
            while (start > low && directoryByte(mapped, start - 1) != 10) start--;
            long end = start;
            while (end < mapped.size() && directoryByte(mapped, end) != 10) end++;
            require(end < mapped.size(), "Unterminated Core symbol directory row");
            long separator = end - 1;
            while (separator > start && directoryByte(mapped, separator) != 32) separator--;
            require(separator > start && directoryByte(mapped, separator) == 32 && separator + 1 < end, "Malformed Core symbol directory row");
            counters.lookupComparisons++;
            int compared = 0, index = 0;
            while (index < key.length && start + index < separator) {
                compared = directoryByte(mapped, start + index) - (key[index] & 255);
                if (compared != 0) break;
                index++;
            }
            if (compared == 0) compared = Long.compare(separator - start, key.length);
            if (compared < 0) low = end + 1;
            else if (compared > 0) high = start;
            else {
                long offset = 0, position = separator + 1;
                while (position < end) {
                    int digit = directoryByte(mapped, position++) - 48;
                    require(digit >= 0 && digit <= 9, "Invalid Core symbol byte offset");
                    offset = Math.addExact(Math.multiplyExact(offset, 10), digit);
                }
                return offset;
            }
        }
        return null;
    }
    private Long lookupDigest(String id) {
        if (symbolDigest == null) symbolDigest = digest("MD5");
        byte[] key = symbolDigest.digest(id.getBytes(StandardCharsets.UTF_8));
        var mapped = directory();
        require(mapped.size() % 24 == 0, "Incomplete fixed-width Core symbol record");
        long low = 0, high = mapped.size() / 24;
        while (low < high) {
            long middle = low + (high - low) / 2, start = middle * 24;
            counters.lookupComparisons++;
            int compared = 0;
            for (int index = 0; index < 16; index++) {
                compared = directoryByte(mapped, start + index) - (key[index] & 255);
                if (compared != 0) break;
            }
            if (compared < 0) low = middle + 1;
            else if (compared > 0) high = middle;
            else {
                counters.directoryByteReads += 8;
                long offset = mapped.bytes.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), start + 16);
                require(offset >= 0, "Core symbol offset exceeds JVM address range");
                return offset;
            }
        }
        return null;
    }
    private Selected decode(String id, long start, Long end) {
        var mapped = source();
        long limit = end == null ? mapped.size() : end;
        require(end == null || end <= mapped.size(), "Core binding extent exceeds source");
        require(start >= 0 && start < mapped.size() && sourceByte(mapped, start) == 123, "Core symbol does not point to a binding object: " + id + " at " + start);
        long at = start, depth = 0;
        boolean quoted = false, escaped = false;
        do {
            require(at < limit, "Unterminated Core binding: " + id + " at " + start);
            int c = sourceByte(mapped, at++);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == 92) escaped = true;
                else if (c == 34) quoted = false;
            } else switch (c) {
                case 34 -> quoted = true; case 123, 91 -> depth++; case 125, 93 -> depth--;
            }
        } while (depth > 0);
        int length = Math.toIntExact(at - start);
        byte[] bytes = mapped.bytes.asSlice(start, length).toArray(ValueLayout.JAVA_BYTE);
        counters.sourceByteReads += length;
        counters.decodedBindings++; counters.decodedBytes += length;
        var binding = parseObject(bytes);
        if (format == Format.TEXT) require(Objects.equals(binding.get("id"), id), "Core symbol directory identity mismatch: " + id);
        return new Selected(start, at, binding);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> parseObject(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Object result = Json.parse(text);
            if (!(result instanceof Map<?,?>)) throw new IllegalStateException("Expected Core object");
            return (Map<String,Object>) result;
        } catch (CharacterCodingException failure) { return rethrow(failure); }
    }
    public Map<String,Object> metadata(ValueSpan span, boolean module) {
        synchronized (counters) {
            check(!closed, "Core symbol source is closed");
            return metadata.computeIfAbsent(span, ignored -> cacheable(() -> {
                var mapped = source();
                require(span.start >= 0 && span.start < span.end && span.end <= mapped.size() &&
                        sourceByte(mapped, span.start) == 123 && sourceByte(mapped, span.end - 1) == 125, "Invalid Core metadata extent");
                int length = Math.toIntExact(span.end - span.start);
                byte[] bytes = mapped.bytes.asSlice(span.start, length).toArray(ValueLayout.JAVA_BYTE);
                counters.sourceByteReads += length; counters.metadataBytes += length;
                if (module) counters.decodedModules++;
                return parseObject(bytes);
            })).get();
        }
    }
    public void verifyModule(ModuleSpan span, String sha256, Consumer<Map<String,Object>> verify) {
        synchronized (counters) {
            check(!closed && verifyArtifacts, "Complete module checks require explicit artifact verification");
            var mapped = source();
            require(span.start >= 0 && span.end > span.start && span.end <= mapped.size(), "Invalid original module extent");
            int length = Math.toIntExact(span.end - span.start);
            byte[] bytes = mapped.bytes.asSlice(span.start, length).toArray(ValueLayout.JAVA_BYTE);
            counters.sourceByteReads += length; counters.verifiedModuleBytes += length; counters.hashBytesScanned += length;
            require(HexFormat.of().formatHex(digest("SHA-256").digest(bytes)).equals(sha256), "Original Core module hash mismatch");
            verify.accept(parseObject(bytes));
        }
    }
    public Map<String,Object> moduleMetadata(ModuleSpan span) {
        synchronized (counters) {
            check(!closed, "Core symbol source is closed");
            var previous = modules.get(span);
            if (previous != null) return previous.get();
            var result = cacheable(() -> {
                require(0 <= span.start && span.start < span.bindingsStart && span.bindingsStart < span.bindingsEnd && span.bindingsEnd < span.end,
                        "Invalid Core module byte ranges");
                var mapped = source();
                require(span.end <= mapped.size() && sourceByte(mapped, span.start) == 123 && sourceByte(mapped, span.end - 1) == 125 &&
                        sourceByte(mapped, span.bindingsStart) == 91 && sourceByte(mapped, span.bindingsEnd - 1) == 93, "Invalid Core module extent");
                int prefixLength = Math.toIntExact(span.bindingsStart - span.start), suffixLength = Math.toIntExact(span.end - span.bindingsEnd);
                byte[] bytes = new byte[Math.addExact(Math.addExact(prefixLength, suffixLength), 2)];
                MemorySegment.copy(mapped.bytes, ValueLayout.JAVA_BYTE, span.start, bytes, 0, prefixLength);
                bytes[prefixLength] = 91; bytes[prefixLength + 1] = 93;
                MemorySegment.copy(mapped.bytes, ValueLayout.JAVA_BYTE, span.bindingsEnd, bytes, prefixLength + 2, suffixLength);
                long read = (long) prefixLength + suffixLength;
                counters.sourceByteReads += read; counters.metadataBytes += read; counters.decodedModules++;
                var parsed = parseObject(bytes);
                require(parsed.get("bindings") instanceof List<?> bindings && bindings.isEmpty(), "Core module locator must replace the binding array");
                return parsed;
            });
            modules.put(span, result);
            return result.get();
        }
    }
    @FunctionalInterface private interface Action<T> { T get() throws Exception; }
    private static <T> Result<T> cacheable(Action<T> action) {
        try { return new Result<>(action.get(), null); }
        catch (CancellationException failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); return rethrow(failure); }
        catch (Exception failure) { return new Result<>(null, failure); }
    }
    public Map<String,Object> binding(String id) { return binding(id, null); }
    public Map<String,Object> binding(String id, ModuleSpan module) {
        synchronized (counters) {
            check(!closed, "Core symbol source is closed");
            var result = selected.computeIfAbsent(id, ignored -> cacheable(() -> {
                Long offset = lookup(id);
                if (offset == null) return null;
                if (module != null) require(offset > module.bindingsStart && offset < module.bindingsEnd, "Core symbol lies outside its declared module: " + id);
                return decode(id, offset, module == null ? null : module.bindingsEnd);
            })).get();
            if (module != null && result != null) require(result.start > module.bindingsStart && result.end < module.bindingsEnd, "Core symbol lies outside its declared module: " + id);
            return result == null ? null : result.binding;
        }
    }
    @Override public void close() {
        synchronized (counters) {
            if (closed) return;
            closed = true;
            selected.clear(); modules.clear(); metadata.clear();
            try { if (source != null) source.close(); }
            finally { source = null; if (directory != null) directory.close(); directory = null; }
        }
    }
    private static MessageDigest digest(String name) {
        try { return MessageDigest.getInstance(name); } catch (NoSuchAlgorithmException failure) { return rethrow(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
