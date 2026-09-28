// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import thc.CoreCompactRecords;

/** Parsing-only metadata: constructing locations neither reads files nor adds executable nodes. */
@SuppressWarnings("unchecked")
public final class CoreSources {
    private record FileSources(Source source, Source locationOnly) {}
    private final boolean enabled;
    private final Map<String, CoreSourceNote> notes;

    public CoreSources(Map<String, ?> moduleData) {
        enabled = !Boolean.FALSE.equals(moduleData.get("sourceNotesEnabled"));
        if (!enabled) { notes = Map.of(); return; }
        var files = new LinkedHashMap<String, FileSources>();
        var sourceFiles = moduleData.get("sourceFiles") instanceof List<?> list ? list : List.of();
        for (var item : sourceFiles) {
            var file = (Map<String, Object>) item;
            if (!(file.get("id") instanceof String id)) throw new RuntimeFault("Source file lacks an id");
            if (!(file.get("path") instanceof String path)) throw new RuntimeFault("Source file lacks a path");
            String content = file.get("content") instanceof String text ? text : null;
            var locationOnly = Source.newBuilder("thc", "", path).content(Source.CONTENT_NONE).build();
            var source = content == null ? locationOnly : Source.newBuilder("thc", content, path).build();
            files.put(id, new FileSources(source, locationOnly));
        }
        notes = new LinkedHashMap<>();
        var sourceSpans = moduleData.get("sourceSpans") instanceof List<?> list ? list : List.of();
        for (var item : sourceSpans) {
            var span = (Map<String, Object>) item;
            if (!(span.get("id") instanceof String id)) throw new RuntimeFault("Source span lacks an id");
            var file = files.get(span.get("file") instanceof String name ? name : null);
            if (file == null) throw new RuntimeFault("Source span references an unknown file");
            var source = file.source();
            int startLine = coordinate(span, "startLine"), startColumn = coordinate(span, "startColumn");
            int endLine = coordinate(span, "endLine"), endColumn = coordinate(span, "endColumn");
            if (endLine < startLine || endLine == startLine && endColumn < startColumn)
                throw new RuntimeFault("Reversed source span");
            Integer index = offset(span, "charIndex"), length = offset(span, "charLength");
            SourceSection section;
            if (source.hasCharacters() && index != null && length != null) {
                if (index < 0 || length < 0 || (long) index + length > source.getLength())
                    throw new RuntimeFault("Source span character range is out of bounds");
                section = source.createSection(index, length);
            } else {
                // GHC tab-expanded/remapped columns are not character offsets. An
                // exclusive next-line column 1 leaves only the start point known.
                if (endColumn == 1 && endLine > startLine)
                    section = file.locationOnly().createSection(startLine, startColumn, startLine, startColumn);
                else section = file.locationOnly().createSection(startLine, startColumn, endLine,
                    endLine == startLine && endColumn == startColumn ? startColumn : endColumn - 1);
            }
            notes.put(id, new CoreSourceNote(id, section, span.get("label") instanceof String label ? label : null,
                startLine, startColumn, endLine, endColumn));
        }
    }
    private static int coordinate(Map<String, Object> span, String name) {
        if (!(span.get(name) instanceof Number number)) throw new RuntimeFault("Source span lacks " + name);
        int value = number.intValue();
        if (value < 1 || (double) value != number.doubleValue()) throw new RuntimeFault("Invalid source span " + name);
        return value;
    }
    private static Integer offset(Map<String, Object> span, String name) {
        Object raw = span.get(name);
        if (raw == null) return null;
        if (!(raw instanceof Number number)) throw new RuntimeFault("Invalid source span " + name);
        int value = number.intValue();
        if (value < 0 || (double) value != number.doubleValue()) throw new RuntimeFault("Invalid source span " + name);
        return value;
    }
    public boolean getEnabled() { return enabled; }
    public int getSpanCount() { return notes.size(); }
    public CoreSourceLocation expression(List<?> expression) { return expression(expression, null); }
    public CoreSourceLocation expression(List<?> expression, CoreSourceLocation inherited) {
        return location(CoreRepresentations.metadata(expression), inherited);
    }
    public CoreSourceLocation binding(Map<String, ?> binding) { return binding(binding, null); }
    public CoreSourceLocation binding(Map<String, ?> binding, CoreSourceLocation inherited) { return location(binding, inherited); }
    private CoreSourceLocation location(Map<String, ?> metadata, CoreSourceLocation inherited) {
        if (metadata != null && metadata.get("compactOrigin") instanceof CoreCompactRecords.Origin origin)
            return CoreSourceLocation.compact(origin, enabled);
        if (!enabled) return null;
        String primary = metadata != null && metadata.get("source") instanceof String id ? id : null;
        List<String> ids = metadata != null && metadata.get("sourceNotes") instanceof List<?> list ? (List<String>) list : List.of();
        if (primary == null && ids.isEmpty()) return inherited;
        String selectedId = primary != null ? primary : ids.isEmpty() ? null : ids.getLast();
        CoreSourceNote selected = null;
        if (selectedId != null) {
            selected = notes.get(selectedId);
            if (selected == null) throw new RuntimeFault("Unknown source span " + selectedId);
        }
        var provenance = new LinkedHashMap<String, CoreSourceNote>();
        if (inherited != null) for (var note : inherited.getNotes()) provenance.putIfAbsent(note.id(), note);
        for (String id : ids) {
            var note = notes.get(id);
            if (note == null) throw new RuntimeFault("Unknown source note " + id);
            provenance.putIfAbsent(note.id(), note);
        }
        if (selected != null && !ids.contains(selected.id())) provenance.putIfAbsent(selected.id(), selected);
        SourceSection section = selected != null ? selected.section() : inherited != null ? inherited.getSection() : null;
        return section == null ? null : new CoreSourceLocation(section, new ArrayList<>(provenance.values()));
    }
}
