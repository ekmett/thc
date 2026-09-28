// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import thc.CoreCompactFormat.Segment;
import thc.runtime.CoreSourceLocation;
import thc.runtime.CoreSources;

/** Display-only selected lookups. No table index or source tree is built at load. */
public final class CoreCompactDebug {
    private final CoreCompactFile file;
    public CoreCompactDebug(CoreCompactFile file) { this.file = file; }
    private record Directory(long start, long count) {}
    private record Selection(Directory directory, long payload) {}
    private record Notes(int primary, List<Map<String,Object>> values) {}
    private Directory directory(Segment segment, int width) throws Throwable {
        long length = file.header().get(segment).length();
        if (length == 0) return null;
        require(length >= 16, "Truncated compact Core debug directory");
        return file.debugAt(segment, length - 16, 16, cursor -> {
            long start = cursor.offset(), count = cursor.offset();
            require(start <= length - 16 && (length - 16 - start) % width == 0 && count == (length - 16 - start) / width,
                    "Invalid compact Core debug directory extent");
            return new Directory(start, count);
        });
    }
    private Selection source(Segment segment, long offset) throws Throwable {
        var directory = directory(segment, 24);
        if (directory == null) return null;
        long low = 0, high = directory.count;
        while (low < high) {
            long mid = low + (high - low) / 2;
            long start = file.debugAt(segment, directory.start + mid * 24, 8, CoreCompactCursor::offset);
            if (start <= offset) low = mid + 1; else high = mid;
        }
        if (low == 0) return null;
        return file.debugAt(segment, directory.start + (low - 1) * 24, 24, cursor -> {
            long start = cursor.offset(), end = cursor.offset(), payload = cursor.offset();
            require(start < end && end <= file.header().get(Segment.DATA).length() && payload < directory.start,
                    "Invalid compact Core source interval");
            return offset >= end ? null : new Selection(directory, payload);
        });
    }
    private <T> T payload(Segment segment, Selection selection, CoreCompactFile.Decoder<T> read) throws Throwable {
        if (selection == null) return null;
        return file.debugAt(segment, selection.payload, selection.directory.start - selection.payload, cursor -> {
            int tag = cursor.readByte();
            return switch (tag) {
                case 0 -> null; case 1 -> read.decode(cursor);
                default -> throw new IllegalStateException("Invalid compact Core source payload tag: " + tag);
            };
        });
    }
    private String text(CoreCompactCursor cursor) throws Throwable { return cursor.text(cursor.count()); }
    private String common(CoreCompactCursor cursor) throws Throwable { return file.string(cursor.unsigned(), cursor.unsigned()); }
    @FunctionalInterface private interface Reader<T> { T read() throws Throwable; }
    private <T> T optional(CoreCompactCursor cursor, Reader<T> read) throws Throwable {
        int tag = cursor.readByte();
        return switch (tag) {
            case 0, 1 -> null; case 2 -> read.read();
            default -> throw new IllegalStateException("Invalid compact Core debug presence tag: " + tag);
        };
    }
    /** Null is explicit absence, never permission to search into another binding. */
    public CoreSourceLocation location(long offset) {
        try {
            require(offset >= 0 && offset < file.header().get(Segment.DATA).length(), "Invalid compact Core source position");
            List<Map<String,Object>> files = payload(Segment.FILENAMES, source(Segment.FILENAMES, offset), cursor -> {
                int count = cursor.count();
                var result = new ArrayList<Map<String,Object>>(count);
                for (int i = 0; i < count; i++) {
                    var entry = new LinkedHashMap<String,Object>();
                    entry.put("id", common(cursor)); entry.put("path", common(cursor));
                    entry.put("content", optional(cursor, () -> common(cursor)));
                    result.add(entry);
                }
                return result;
            });
            Notes notes = payload(Segment.LINE_COLUMNS, source(Segment.LINE_COLUMNS, offset), cursor -> {
                long primary = cursor.unsigned();
                int count = cursor.count();
                var values = new ArrayList<Map<String,Object>>(count);
                for (int i = 0; i < count; i++) {
                    var note = new LinkedHashMap<String,Object>();
                    note.put("id", text(cursor)); note.put("label", optional(cursor, () -> text(cursor)));
                    for (String field : List.of("startLine", "startColumn", "endLine", "endColumn")) note.put(field, cursor.unsigned());
                    for (String field : List.of("charIndex", "charLength")) note.put(field, optional(cursor, cursor::unsigned));
                    values.add(note);
                }
                require(primary < values.size(), "Invalid compact Core primary source note");
                return new Notes((int) primary, values);
            });
            if (files == null && notes == null) return null;
            require(files != null && notes != null && files.size() == notes.values.size(), "Mismatched compact Core source tables");
            var distinctFiles = new LinkedHashMap<String,Map<String,Object>>();
            var ids = new HashSet<String>();
            var spans = new ArrayList<Map<String,Object>>();
            for (int i = 0; i < notes.values.size(); i++) {
                var note = notes.values.get(i);
                require(ids.add((String) note.get("id")), "Duplicate compact Core source note");
                var sourceFile = files.get(i);
                String id = (String) sourceFile.get("id");
                var previous = distinctFiles.putIfAbsent(id, sourceFile);
                require(previous == null || previous.equals(sourceFile), "Inconsistent compact Core source file");
                var span = new LinkedHashMap<>(note);
                span.put("file", id);
                spans.add(span);
            }
            var sources = new CoreSources(Map.of("sourceFiles", new ArrayList<>(distinctFiles.values()), "sourceSpans", spans));
            return sources.binding(Map.of("source", spans.get(notes.primary).get("id"), "sourceNotes", spans.stream().map(span -> span.get("id")).toList()), null);
        } catch (Throwable failure) { return rethrow(failure); }
    }
    public String name(long bindingOffset, long slot) {
        try {
            require(bindingOffset >= 0 && bindingOffset < file.header().get(Segment.DATA).length() && slot >= 0, "Invalid compact Core display-name key");
            return lookupName(bindingOffset, slot);
        } catch (Throwable failure) { return rethrow(failure); }
    }
    public String constructorName(int index) {
        require(index >= 0, "Invalid compact Core constructor display index");
        try { return lookupName(-1L, (long) index + 1); }
        catch (Throwable failure) { return rethrow(failure); }
    }
    private String lookupName(long bindingOffset, long slot) throws Throwable {
        var directory = directory(Segment.NAMES, 32);
        if (directory == null) return null;
        long low = 0, high = directory.count;
        while (low < high) {
            long mid = low + (high - low) / 2;
            long[] key = file.debugAt(Segment.NAMES, directory.start + mid * 32, 16, cursor -> new long[] {cursor.fixedBits(), cursor.offset()});
            int comparison = Long.compareUnsigned(key[0], bindingOffset);
            if (comparison == 0) comparison = Long.compare(key[1], slot);
            if (comparison < 0) low = mid + 1;
            else if (comparison > 0) high = mid;
            else return file.debugAt(Segment.NAMES, directory.start + mid * 32 + 16, 16, cursor -> {
                long start = cursor.offset(), length = cursor.offset();
                require(start <= directory.start && length <= directory.start - start && length <= Integer.MAX_VALUE,
                        "Invalid compact Core display-name span");
                return file.debugAt(Segment.NAMES, start, length, selected -> selected.text((int) length));
            });
        }
        return null;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
