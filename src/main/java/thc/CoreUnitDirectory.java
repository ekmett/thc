// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import thc.runtime.TargetLayout;

/** Small package directory owns module names, never binding names. Unit files
 * remain unopened until a binding or the module's metadata is requested. */
public final class CoreUnitDirectory {
    public record Artifact(Path path, String sha256) {
        public Path getPath() { return path; } public String getSha256() { return sha256; }
    }
    public record UnitRecord(String id, List<String> depends, Artifact json, Artifact symbols,
            List<ModuleRecord> modules, Artifact legacyBundle, CoreJsonSymbols.Format symbolsFormat) {
        public UnitRecord(String id, List<String> depends, Artifact json, Artifact symbols, List<ModuleRecord> modules) {
            this(id, depends, json, symbols, modules, null, CoreJsonSymbols.Format.TEXT);
        }
        public String getId() { return id; } public List<String> getDepends() { return depends; }
        public Artifact getJson() { return json; } public Artifact getSymbols() { return symbols; }
        public List<ModuleRecord> getModules() { return modules; } public Artifact getLegacyBundle() { return legacyBundle; }
        public CoreJsonSymbols.Format getSymbolsFormat() { return symbolsFormat; }
    }
    public sealed interface Storage permits JsonStorage, CompactStorage {}
    public record JsonStorage(CoreJsonSymbols.ModuleSpan span, CoreJsonSymbols.ValueSpan metadata,
            CoreJsonSymbols.ValueSpan sourceMetadata) implements Storage {
        public CoreJsonSymbols.ModuleSpan getSpan() { return span; }
        public CoreJsonSymbols.ValueSpan getMetadata() { return metadata; }
        public CoreJsonSymbols.ValueSpan getSourceMetadata() { return sourceMetadata; }
    }
    public record CompactStorage(Artifact artifact) implements Storage { public Artifact getArtifact() { return artifact; } }
    public record ModuleRecord(String unit, String name, String sha256, Storage storage,
            boolean containsDelimitedControl, boolean registrationObligations, boolean mainAlias, boolean packageScalarDeclarations) {
        public String getUnit() { return unit; } public String getName() { return name; } public String getSha256() { return sha256; }
        public Storage getStorage() { return storage; }
        public boolean getContainsDelimitedControl() { return containsDelimitedControl; }
        public boolean getRegistrationObligations() { return registrationObligations; }
        public boolean getMainAlias() { return mainAlias; }
        public boolean getPackageScalarDeclarations() { return packageScalarDeclarations; }
        public String getPrefix() { return unit + ":" + name + "."; }
        public CoreJsonSymbols.ModuleSpan getSpan() { return ((JsonStorage) storage).span; }
        public CoreJsonSymbols.ValueSpan getMetadata() { return ((JsonStorage) storage).metadata; }
        public CoreJsonSymbols.ValueSpan getSourceMetadata() { return ((JsonStorage) storage).sourceMetadata; }
    }
    private final List<UnitRecord> units;
    private final String foreignExceptionBridgeUnit;
    private final TargetLayout targetLayout;
    private final List<ModuleRecord> modules;
    private final Map<String,ModuleRecord> owners = new LinkedHashMap<>();
    private final Map<String,List<ModuleRecord>> aliases = new LinkedHashMap<>();
    private CoreUnitDirectory(List<UnitRecord> units, String foreignExceptionBridgeUnit, TargetLayout targetLayout) {
        this.units = units; this.foreignExceptionBridgeUnit = foreignExceptionBridgeUnit; this.targetLayout = targetLayout;
        modules = new ArrayList<>();
        for (var unit : units) for (var module : unit.modules) {
            modules.add(module); owners.put(module.getPrefix(), module);
            if (module.mainAlias) aliases.computeIfAbsent("main::" + module.name + ".main", ignored -> new ArrayList<>()).add(module);
        }
    }
    public List<UnitRecord> getUnits() { return units; }
    public String getForeignExceptionBridgeUnit() { return foreignExceptionBridgeUnit; }
    public TargetLayout getTargetLayout() { return targetLayout; }
    public List<ModuleRecord> getModules() { return modules; }
    public ModuleRecord owner(String id) {
        var matches = aliases.get(id);
        if (matches != null) { require(matches.size() == 1, "Ambiguous Core entry alias: " + id); return matches.getFirst(); }
        int end = id.lastIndexOf('.');
        while (end >= 0) {
            var owner = owners.get(id.substring(0, end + 1));
            if (owner != null && end + 1 < id.length()) return owner;
            end = id.lastIndexOf('.', end - 1);
        }
        return null;
    }
    public Sources open(boolean verifyArtifacts) { return open(verifyArtifacts, true); }
    public Sources open(boolean verifyArtifacts, boolean sourceNotes) { return open(verifyArtifacts, sourceNotes, ignored -> {}, ignored -> {}); }
    public Sources open(boolean verifyArtifacts, boolean sourceNotes, Consumer<CoreJsonSymbols.Counters> admitted,
            Consumer<CoreCompactFile.Counters> compactAdmitted) { return new Sources(this, verifyArtifacts, sourceNotes, admitted, compactAdmitted); }
    public static final class Sources implements AutoCloseable {
        private final CoreUnitDirectory directory;
        private final boolean verifyArtifacts, sourceNotes;
        private final Consumer<CoreJsonSymbols.Counters> admitted;
        private final Consumer<CoreCompactFile.Counters> compactAdmitted;
        private boolean closed;
        private final Map<String,CoreJsonSymbols> readers = new HashMap<>();
        private final Map<ModuleRecord,CoreCompactModule> compactReaders = new HashMap<>();
        private final Map<ModuleRecord,Map<String,Object>> metadata = new HashMap<>();
        private final Set<ModuleRecord> verified = new HashSet<>();
        public Sources(CoreUnitDirectory directory, boolean verifyArtifacts, boolean sourceNotes,
                Consumer<CoreJsonSymbols.Counters> admitted, Consumer<CoreCompactFile.Counters> compactAdmitted) {
            this.directory = directory; this.verifyArtifacts = verifyArtifacts; this.sourceNotes = sourceNotes;
            this.admitted = admitted; this.compactAdmitted = compactAdmitted;
        }
        private CoreCompactModule compact(ModuleRecord module) {
            return compactReaders.computeIfAbsent(module, ignored -> {
                var reader = new CoreCompactModule(module, directory.targetLayout, verifyArtifacts);
                compactAdmitted.accept(reader.getCounters());
                return reader;
            });
        }
        private CoreJsonSymbols reader(String unit) {
            check(!closed, "Core unit sources are closed");
            return readers.computeIfAbsent(unit, ignored -> {
                var matches = new ArrayList<UnitRecord>();
                for (var record : directory.units) if (record.id.equals(unit)) matches.add(record);
                if (matches.size() != 1) throw error("Expected one unit: " + unit);
                var record = matches.getFirst();
                if (record.json == null) throw error("Moduleless unit has no JSON bindings");
                if (record.symbols == null) throw error("Moduleless unit has no symbol directory");
                var reader = new CoreJsonSymbols(record.json.path, record.symbols.path, verifyArtifacts,
                        record.json.sha256, record.symbols.sha256, CoreFileMappings.shared, record.symbolsFormat);
                admitted.accept(reader.getCounters());
                return reader;
            });
        }
        public synchronized Map<String,Object> metadata(ModuleRecord module) {
            check(!closed, "Core unit sources are closed");
            verifyModule(module);
            if (module.storage instanceof CompactStorage) return compact(module).metadata();
            return metadata.computeIfAbsent(module, ignored -> {
                var reader = reader(module.unit);
                var required = reader.metadata(module.getMetadata(), true);
                require(METADATA_FIELDS.containsAll(required.keySet()), "Unexpected Core admission metadata field");
                Map<String,Object> notes = Map.of();
                if (sourceNotes && module.getSourceMetadata() != null) {
                    notes = reader.metadata(module.getSourceMetadata(), false);
                    require(Set.of("sourceFiles", "sourceSpans").containsAll(notes.keySet()), "Unexpected Core source metadata field");
                }
                var result = new LinkedHashMap<>(required);
                result.putAll(notes); result.put("bindings", List.of());
                require(Objects.equals(result.get("unit"), module.unit) && Objects.equals(result.get("module"), module.name) &&
                        Objects.equals(result.get("ghc"), "9.14.1") && Objects.equals(result.get("boundary"), BOUNDARY),
                        "Core module identity differs from package directory: " + module.getPrefix());
                return result;
            });
        }
        public synchronized Map<String,Object> binding(String id) {
            check(!closed, "Core unit sources are closed");
            var module = directory.owner(id);
            if (module == null) return null;
            return module.storage instanceof CompactStorage ? compact(module).binding(id) : reader(module.unit).binding(id, module.getSpan());
        }
        public synchronized boolean containsSymbol(String id) {
            check(!closed, "Core unit sources are closed");
            var module = directory.owner(id);
            if (module == null) return false;
            return module.storage instanceof CompactStorage ? compact(module).containsSymbol(id) : reader(module.unit).containsSymbol(id);
        }
        public synchronized void verifyModule(ModuleRecord module) {
            check(!closed, "Core unit sources are closed");
            if (!verifyArtifacts || verified.contains(module)) return;
            if (module.storage instanceof CompactStorage) { compact(module).verify(); verified.add(module); return; }
            var reader = reader(module.unit);
            reader.verifyModule(module.getSpan(), module.sha256, original -> {
                var selected = new LinkedHashMap<>(original);
                selected.keySet().retainAll(METADATA_FIELDS);
                require(selected.equals(reader.metadata(module.getMetadata(), true)), "Core admission metadata differs from original module");
                var notes = new LinkedHashMap<>(original);
                notes.keySet().retainAll(Set.of("sourceFiles", "sourceSpans"));
                require(notes.equals(module.getSourceMetadata() == null ? Map.of() : reader.metadata(module.getSourceMetadata(), false)),
                        "Core source metadata differs from original module");
                CoreForeignArtifacts.INSTANCE.validateArchive(original, true);
                CoreModules.admission(original, null);
                thc.runtime.CoreForeignExceptionBridge.INSTANCE.read(original);
            });
            verified.add(module);
        }
        public synchronized List<CoreJsonSymbols.Counters> counters() {
            var counters = new ArrayList<CoreJsonSymbols.Counters>();
            for (var reader : readers.values()) counters.add(reader.getCounters());
            return Collections.unmodifiableList(counters);
        }
        public synchronized List<CoreCompactFile.Counters> compactCounters() {
            var counters = new ArrayList<CoreCompactFile.Counters>();
            for (var reader : compactReaders.values()) counters.add(reader.getCounters());
            return Collections.unmodifiableList(counters);
        }
        public synchronized void close() {
            if (closed) return;
            closed = true; metadata.clear(); verified.clear();
            try { readers.values().forEach(CoreJsonSymbols::close); }
            finally {
                readers.clear();
                try { compactReaders.values().forEach(CoreCompactModule::close); } finally { compactReaders.clear(); }
            }
        }
    }
    private static final String BOUNDARY = "optimized-Core-after-Tidy-before-CorePrep";
    private static final Set<String> METADATA_FIELDS = Set.of("schema", "ghc", "unit", "module", "boundary", "providedModules", "constructors",
            "foreign", "foreignLink", "staticForeignImportStubs", "staticForeignImports", "staticForeignExports",
            "staticForeignExportRegistration", "packageScalarLink", "packageNativeLink", "packageNativeArchive",
            "foreignExceptionBridge", "foreignExceptionBridgeUnit");
    /** Null retains legacy package loading; never discovers or converts sidecars. */
    public static CoreUnitDirectory read(Map<?,?> document) {
        var rawUnits = list(document.get("units"), "Missing package units");
        boolean direct = false;
        unitScan: for (Object raw : rawUnits) if (raw instanceof Map<?,?> unit) {
            if (unit.containsKey("json") || unit.containsKey("symbols")) { direct = true; break; }
            if (unit.get("modules") instanceof List<?> modules) for (Object value : modules)
                if (value instanceof Map<?,?> module && module.containsKey("compact")) { direct = true; break unitScan; }
        }
        if (!direct) return null;
        require(Objects.equals(document.get("format"), "thc-core-packages") && Objects.equals(document.get("schema"), 1L) &&
                Objects.equals(document.get("ghc"), "9.14.1"), "Core package manifest requires schema 1 / GHC 9.14.1");
        var unitIds = new HashSet<String>();
        var artifactPaths = new HashSet<Path>();
        TargetLayout layout = null;
        var units = new ArrayList<UnitRecord>();
        for (Object raw : rawUnits) {
            var unit = record(raw, "Invalid package unit");
            String id = text(unit.get("id"), "Missing GHC unit ID");
            require(!id.isEmpty() && unitIds.add(id), "Invalid or duplicate GHC unit ID: " + id);
            var depends = list(unit.get("depends"), "Missing GHC unit dependencies");
            boolean validDepends = true;
            for (Object value : depends) if (!(value instanceof String dependency) || dependency.isEmpty()) { validDepends = false; break; }
            require(validDepends &&
                    new HashSet<>(depends).size() == depends.size(), "Invalid dependencies for GHC unit " + id);
            var dependencyNames = new ArrayList<String>();
            for (Object value : depends) dependencyNames.add((String) value);
            List<String> dependencies = Collections.unmodifiableList(dependencyNames);
            var rawModules = list(unit.get("modules"), "Missing unit modules");
            if (rawModules.isEmpty() && !unit.containsKey("json") && !unit.containsKey("symbols")) {
                units.add(new UnitRecord(id, dependencies, null, null, List.of(),
                        unit.containsKey("bundle") ? artifact(unit.get("bundle"), false, false, artifactPaths) : null, CoreJsonSymbols.Format.TEXT));
                continue;
            }
            require(!unit.containsKey("bundle"), "Only moduleless legacy units may accompany direct unit pairs: " + id);
            boolean compact = false;
            for (Object value : rawModules) if (value instanceof Map<?,?> module && module.containsKey("compact")) { compact = true; break; }
            boolean consistent = !compact || !unit.containsKey("json") && !unit.containsKey("symbols");
            if (compact && consistent) for (Object value : rawModules)
                if (!(value instanceof Map<?,?> module) || !module.containsKey("compact")) { consistent = false; break; }
            require(consistent, "Mixed compact and JSON storage within GHC unit: " + id);
            var names = new HashSet<String>();
            long previousEnd = 0;
            var modules = new ArrayList<ModuleRecord>();
            for (Object item : rawModules) {
                var module = record(item, "Invalid module record");
                String name = text(module.get("name"), "Missing module name");
                require(!name.isEmpty() && names.add(name) && Objects.equals(module.get("boundary"), BOUNDARY) &&
                        module.get("sha256") instanceof String hash && hash.matches("[0-9a-f]{64}"), "Invalid module directory record: " + id + ":" + name);
                Storage storage;
                if (compact) {
                    var jsonFields = Set.of("start", "end", "bindingsStart", "bindingsEnd", "metadataStart", "metadataEnd", "sourceMetadataStart", "sourceMetadataEnd", "index");
                    boolean jsonExtent = false;
                    for (Object key : module.keySet()) if (jsonFields.contains(key)) { jsonExtent = true; break; }
                    require(!jsonExtent, "Compact module contains JSON storage extents");
                    storage = new CompactStorage(artifact(module.get("compact"), false, true, artifactPaths));
                } else {
                    var span = new CoreJsonSymbols.ModuleSpan(offset(module, "start"), offset(module, "end"), offset(module, "bindingsStart"), offset(module, "bindingsEnd"));
                    require(span.start() >= previousEnd && span.start() < span.bindingsStart() && span.bindingsStart() < span.bindingsEnd() && span.bindingsEnd() < span.end(),
                            "Invalid module extents");
                    var metadata = new CoreJsonSymbols.ValueSpan(offset(module, "metadataStart"), offset(module, "metadataEnd"));
                    require(metadata.start() >= span.end() && metadata.end() > metadata.start(), "Invalid Core admission metadata extent");
                    require(module.containsKey("sourceMetadataStart") == module.containsKey("sourceMetadataEnd"), "Incomplete Core source metadata extent");
                    CoreJsonSymbols.ValueSpan notes = null;
                    if (module.containsKey("sourceMetadataStart")) {
                        notes = new CoreJsonSymbols.ValueSpan(offset(module, "sourceMetadataStart"), offset(module, "sourceMetadataEnd"));
                        require(notes.start() >= metadata.end() && notes.end() > notes.start(), "Invalid Core source metadata extent");
                    }
                    previousEnd = notes == null ? metadata.end() : notes.end();
                    storage = new JsonStorage(span, metadata, notes);
                }
                modules.add(new ModuleRecord(id, name, (String) module.get("sha256"), storage,
                        flag(module, "containsDelimitedControl", "Missing delimited-control summary"),
                        flag(module, "registrationObligations", "Missing registration summary"),
                        flag(module, "mainAlias", "Missing main-alias summary"),
                        flag(module, "packageScalarDeclarations", "Missing package declaration summary")));
            }
            if (unit.get("targetLayout") != null) {
                var candidate = TargetLayout.fromDocument(unit.get("targetLayout"));
                require(layout == null || layout.equals(candidate), "Conflicting GHC target layouts");
                layout = candidate;
            }
            units.add(new UnitRecord(id, dependencies, compact ? null : artifact(unit.get("json"), false, false, artifactPaths),
                    compact ? null : artifact(unit.get("symbols"), true, false, artifactPaths), modules, null,
                    !compact && ((Map<?,?>) unit.get("symbols")).containsKey("format") ? CoreJsonSymbols.Format.MD5_UTF8_U64LE : CoreJsonSymbols.Format.TEXT));
        }
        Object bridge = document.get("foreignExceptionBridgeUnit");
        require(bridge == null || bridge instanceof String name && !blank(name), "Invalid foreign exception bridge unit");
        return new CoreUnitDirectory(units, (String) bridge, layout);
    }
    private static boolean blank(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }
    private static Artifact artifact(Object raw, boolean symbols, boolean compact, Set<Path> paths) {
        var record = record(raw, "Missing unit artifact");
        var path = Path.of(text(record.get("path"), "Missing unit artifact path"));
        String hash = text(record.get("sha256"), "Missing unit artifact identity");
        require((!compact && record.keySet().equals(Set.of("path", "sha256")) ||
                record.keySet().equals(Set.of("path", "sha256", "format")) &&
                        (symbols && Objects.equals(record.get("format"), CoreJsonSymbols.MD5_FORMAT) || compact && Objects.equals(record.get("format"), CoreCompactFormat.NAME))) &&
                path.isAbsolute() && hash.matches("[0-9a-f]{64}") && paths.add(path.normalize()), "Invalid or duplicate unit artifact reference");
        return new Artifact(path.normalize(), hash);
    }
    private static long offset(Map<?,?> module, String field) {
        if (!(module.get(field) instanceof Long value)) throw error("Missing exact module byte offset: " + field);
        require(value >= 0, "Negative module byte offset: " + field);
        return value;
    }
    private static boolean flag(Map<?,?> module, String field, String message) { if (!(module.get(field) instanceof Boolean value)) throw error(message); return value; }
    private static Map<?,?> record(Object value, String message) { if (!(value instanceof Map<?,?> result)) throw error(message); return result; }
    private static List<?> list(Object value, String message) { if (!(value instanceof List<?> result)) throw error(message); return result; }
    private static String text(Object value, String message) { if (!(value instanceof String result)) throw error(message); return result; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void check(boolean condition, String message) { if (!condition) throw error(message); }
    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
}
