// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import thc.runtime.TargetLayout;

/** Package directory owns module names, never binding names. CBD files open on demand. */
public final class CoreUnitDirectory {
    // GHC keeps this CLI wrapper identity even with a non-Main -main-is module.
    static final String MAIN_ALIAS = "main::Main.main";
    public record Artifact(Path path, String sha256) {
        public Path getPath() { return path; } public String getSha256() { return sha256; }
    }
    public record UnitRecord(String id, List<String> depends, List<ModuleRecord> modules) {
        public UnitRecord { depends = List.copyOf(depends); modules = List.copyOf(modules); }
        public String getId() { return id; } public List<String> getDepends() { return depends; }
        public List<ModuleRecord> getModules() { return modules; }
    }
    public record ModuleRecord(String unit, String name, String sha256, Artifact artifact, CoreInterfaceSource interfaceSource,
            boolean containsDelimitedControl, boolean registrationObligations, boolean mainAlias, boolean packageScalarDeclarations) {
        public String getUnit() { return unit; } public String getName() { return name; } public String getSha256() { return sha256; }
        public Artifact getArtifact() { return artifact; }
        public boolean getContainsDelimitedControl() { return containsDelimitedControl; }
        public boolean getRegistrationObligations() { return registrationObligations; }
        public boolean getMainAlias() { return mainAlias; }
        public boolean getPackageScalarDeclarations() { return packageScalarDeclarations; }
        public String getPrefix() { return unit + ":" + name + "."; }
    }
    private final List<UnitRecord> units;
    private final String foreignExceptionBridgeUnit;
    private final TargetLayout targetLayout;
    private final List<ModuleRecord> modules;
    private final Map<String,ModuleRecord> owners = new LinkedHashMap<>();
    private final Map<String,List<ModuleRecord>> aliases = new LinkedHashMap<>();
    private CoreUnitDirectory(List<UnitRecord> units, String foreignExceptionBridgeUnit, TargetLayout targetLayout) {
        this.units = List.copyOf(units); this.foreignExceptionBridgeUnit = foreignExceptionBridgeUnit; this.targetLayout = targetLayout;
        modules = this.units.stream().flatMap(unit -> unit.modules.stream()).toList();
        for (var module : modules) {
            owners.put(module.getPrefix(), module);
            if (module.mainAlias) aliases.computeIfAbsent(MAIN_ALIAS, ignored -> new ArrayList<>()).add(module);
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
    public Sources open(boolean verifyArtifacts, boolean sourceNotes) { return open(verifyArtifacts, sourceNotes, ignored -> {}); }
    public Sources open(boolean verifyArtifacts, boolean sourceNotes,
            Consumer<CoreCompactFile.Counters> compactAdmitted) { return new Sources(this, verifyArtifacts, compactAdmitted); }
    public static final class Sources implements AutoCloseable {
        private final CoreUnitDirectory directory;
        private TargetLayout targetLayout;
        private final boolean verifyArtifacts;
        private final Consumer<CoreCompactFile.Counters> compactAdmitted;
        private boolean closed;
        private Path interfaceDirectory;
        private long interfaceConversions;
        private final Map<ModuleRecord,CoreCompactModule> compactReaders = new HashMap<>();
        private final List<CoreCompactFile> consumerReaders = new ArrayList<>();
        private final Set<ModuleRecord> verified = new HashSet<>();
        private final Map<String,String> blobs = new HashMap<>();
        public Sources(CoreUnitDirectory directory, boolean verifyArtifacts, Consumer<CoreCompactFile.Counters> compactAdmitted) {
            this.directory = directory; this.verifyArtifacts = verifyArtifacts;
            targetLayout = directory.targetLayout;
            this.compactAdmitted = compactAdmitted;
        }
        private CoreCompactModule compact(ModuleRecord module) {
            return compactReaders.computeIfAbsent(module, ignored -> {
                var artifact = module.artifact();
                if (module.interfaceSource() != null) {
                    try {
                        var environment = CoreInterfaceSource.processEnvironment();
                        if (interfaceDirectory == null) interfaceDirectory = java.nio.file.Files.createTempDirectory("thc-interface-");
                        artifact = module.interfaceSource().convert(module, interfaceDirectory, environment);
                        interfaceConversions++;
                    } catch (Throwable failure) { return rethrow(failure); }
                }
                var reader = new CoreCompactModule(module, directory.targetLayout, verifyArtifacts, blobs, artifact);
                compactAdmitted.accept(reader.getCounters());
                return reader;
            });
        }
        /** Explicit loose inputs retain their readers in this context, just like package modules. */
        public synchronized Map<String,Object> consumer(Path path, String sha256) {
            check(!closed, "Core unit sources are closed");
            var file = new CoreCompactFile(path, sha256, verifyArtifacts);
            consumerReaders.add(file);
            compactAdmitted.accept(file.getCounters());
            try {
                var records = new CoreCompactRecords(file, sha256.isEmpty() ? path.toString() : sha256, blobs);
                var result = new LinkedHashMap<>(records.header());
                if (result.get("targetLayout") != null) {
                    var candidate = TargetLayout.fromDocument(result.get("targetLayout"));
                    require(targetLayout == null || targetLayout.equals(candidate), "Conflicting GHC target layouts");
                    targetLayout = candidate;
                }
                var bindings = new ArrayList<Map<String,Object>>();
                file.visitBindingOffsets(offset -> bindings.add(records.binding(offset)));
                result.put("bindings", bindings);
                return result;
            } catch (Throwable failure) { return rethrow(failure); }
        }
        public TargetLayout getTargetLayout() { return targetLayout; }
        public synchronized Map<String,Object> metadata(ModuleRecord module) {
            check(!closed, "Core unit sources are closed");
            verifyModule(module);
            return compact(module).metadata();
        }
        public synchronized Map<String,Object> binding(String id) {
            check(!closed, "Core unit sources are closed");
            var module = directory.owner(id);
            if (module == null) return null;
            return compact(module).binding(id);
        }
        public synchronized boolean containsSymbol(String id) {
            check(!closed, "Core unit sources are closed");
            var module = directory.owner(id);
            if (module == null) return false;
            return compact(module).containsSymbol(id);
        }
        public synchronized void verifyModule(ModuleRecord module) {
            check(!closed, "Core unit sources are closed");
            if (!verifyArtifacts || verified.contains(module)) return;
            compact(module).verify();
            verified.add(module);
        }
        public synchronized long interfaceConversions() { return interfaceConversions; }
        public synchronized List<CoreCompactFile.Counters> compactCounters() {
            var counters = new ArrayList<CoreCompactFile.Counters>();
            for (var reader : compactReaders.values()) counters.add(reader.getCounters());
            for (var reader : consumerReaders) counters.add(reader.getCounters());
            return Collections.unmodifiableList(counters);
        }
        public synchronized void close() {
            if (closed) return;
            closed = true; verified.clear();
            try { compactReaders.values().forEach(CoreCompactModule::close); }
            finally {
                compactReaders.clear();
                try { for (var reader : consumerReaders) reader.close(); }
                catch (Exception failure) { rethrow(failure); }
                finally {
                    consumerReaders.clear(); blobs.clear();
                    if (interfaceDirectory != null) CoreInterfaceSource.removeTemporary(interfaceDirectory);
                }
            }
        }
    }
    private static final String BOUNDARY = "optimized-Core-after-Tidy-before-CorePrep";
    public static CoreUnitDirectory read(Map<?,?> document) {
        var rawUnits = list(document.get("units"), "Missing package units");
        var interfaceInputs = CoreInterfaceSource.snapshot(document.get("interfaceInputs"));
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
            var interfaceSource = unit.containsKey("interfaceSource") ? CoreInterfaceSource.read(unit.get("interfaceSource"), interfaceInputs) : null;
            require(!unit.containsKey("bundle") && !unit.containsKey("json") && !unit.containsKey("symbols"),
                    "Core runtime inputs must be declared CBD modules: " + id);
            var names = new HashSet<String>();
            var modules = new ArrayList<ModuleRecord>();
            for (Object item : rawModules) {
                var module = record(item, "Invalid module record");
                String name = text(module.get("name"), "Missing module name");
                require(!name.isEmpty() && names.add(name) && Objects.equals(module.get("boundary"), BOUNDARY) &&
                        module.get("sha256") instanceof String hash && hash.matches("[0-9a-f]{64}"), "Invalid module directory record: " + id + ":" + name);
                require(Collections.disjoint(module.keySet(), Set.of("start", "end", "bindingsStart", "bindingsEnd",
                        "metadataStart", "metadataEnd", "sourceMetadataStart", "sourceMetadataEnd", "index")),
                        "CBD module contains JSON storage extents");
                require(!module.containsKey("compact") || !module.containsKey("interface"), "Conflicting Core module source");
                var artifact = interfaceSource == null ? artifact(module.get("compact"), artifactPaths) :
                        interfaceSource.artifact(module.get("interface"), artifactPaths);
                if (interfaceSource != null) require(module.get("sha256").equals(artifact.sha256()),
                        "Interface module hash differs from its artifact: " + id + ":" + name);
                if (interfaceSource != null) require(Boolean.FALSE.equals(module.get("containsDelimitedControl")) && Boolean.FALSE.equals(module.get("registrationObligations")) &&
                  Boolean.FALSE.equals(module.get("mainAlias")) && Boolean.FALSE.equals(module.get("packageScalarDeclarations")),
                  "Demand interfaces require checked false startup summaries");
                var record = new ModuleRecord(id, name, (String) module.get("sha256"), artifact, interfaceSource,
                        flag(module, "containsDelimitedControl", "Missing delimited-control summary"),
                        flag(module, "registrationObligations", "Missing registration summary"),
                        flag(module, "mainAlias", "Missing main-alias summary"),
                        flag(module, "packageScalarDeclarations", "Missing package declaration summary"));
                modules.add(record);
            }
            if (unit.get("targetLayout") != null) {
                var candidate = TargetLayout.fromDocument(unit.get("targetLayout"));
                require(layout == null || layout.equals(candidate), "Conflicting GHC target layouts");
                layout = candidate;
            }
            units.add(new UnitRecord(id, dependencies, modules));
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
    private static Artifact artifact(Object raw, Set<Path> paths) {
        require(raw instanceof Map<?,?>, "Missing Core artifact");
        var record = (Map<?,?>) raw;
        var path = Path.of(text(record.get("path"), "Missing unit artifact path"));
        String hash = text(record.get("sha256"), "Missing unit artifact identity");
        require(record.keySet().equals(Set.of("path", "sha256", "format")) && Objects.equals(record.get("format"), CoreCompactFormat.NAME) &&
                path.isAbsolute() && hash.matches("[0-9a-f]{64}") && paths.add(path.normalize()), "Invalid or duplicate unit artifact reference");
        return new Artifact(path.normalize(), hash);
    }
    private static boolean flag(Map<?,?> module, String field, String message) { if (!(module.get(field) instanceof Boolean value)) throw error(message); return value; }
    private static Map<?,?> record(Object value, String message) { if (!(value instanceof Map<?,?> result)) throw error(message); return result; }
    private static List<?> list(Object value, String message) { if (!(value instanceof List<?> result)) throw error(message); return result; }
    private static String text(Object value, String message) { if (!(value instanceof String result)) throw error(message); return result; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void check(boolean condition, String message) { if (!condition) throw error(message); }
    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
