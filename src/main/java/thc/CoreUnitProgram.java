// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import java.util.*;
import thc.runtime.*;

/** One context's admitted definitions. A compiled reference to another module
 * holds a cold GlobalBinding, not that module's parsed body or header. */
@SuppressWarnings("unchecked")
public final class CoreUnitProgram implements ExecutableProgram, AutoCloseable {
    private final Language language;
    private final CoreUnitDirectory directory;
    private final Map<String,Object> input;
    private final String entry, backend;
    private final boolean async;
    private final Language.State owner;
    private final List<CoreJsonSymbols.Counters> totals = new ArrayList<>();
    private final List<CoreCompactFile.Counters> compactTotals = new ArrayList<>();
    private final CoreUnitDirectory.Sources sources;
    private final Map<String,Map<String,Object>> constructors = new HashMap<>();
    private final Map<CoreUnitDirectory.ModuleRecord,Map<String,Object>> admittedModules = new HashMap<>();
    private final Map<CoreUnitDirectory.ModuleRecord,CoreModuleAdmission> admissions = new HashMap<>();
    private final Map<String,List<PackageScalarAdmission>> packageProvenance = new HashMap<>();
    private final Set<CoreModuleAdmission> linkedAdmissions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<CoreJsonIndex> consumerSources = new ArrayList<>();
    private final CoreJsonLoadingStatistics consumerStatistics = new CoreJsonLoadingStatistics();
    private final List<Map<String,Object>> consumers = new ArrayList<>();
    private final Map<String,Map<String,Object>> consumerBindings = new HashMap<>(), consumerOwners = new HashMap<>();
    private final IdentityHashMap<Map<String,Object>,CoreModuleAdmission> consumerAdmissions = new IdentityHashMap<>();
    private final Set<String> availableModules = new HashSet<>();
    private Map<String,Object> selectedBridge;
    private final CoreDemandBindings demand;
    private final boolean captureDelimited;
    public CoreUnitProgram(Language language, CoreUnitDirectory directory, Map<String,Object> input, String entry, String backend, boolean async, Language.State owner) {
        this.language = language; this.directory = directory; this.input = input; this.entry = entry;
        this.backend = backend; this.async = async; this.owner = owner;
        sources = directory.open(Objects.equals(input.get("verifyArtifacts"), true), !Objects.equals(input.get("sourceNotesEnabled"), false), totals::add, compactTotals::add);
        for (var module : directory.getModules()) availableModules.add(module.unit() + ":" + module.name());
        var modules = new HashSet<String>();
        try {
            CoreModules.visitUnitConsumers(input, (source, adapter) -> {
                consumerSources.add(source); consumerStatistics.include(source, adapter);
            }, rawModule -> {
                var module = (Map<String,Object>) rawModule;
                String unit = requiredText(module.get("unit"), "Missing loose consumer unit"), name = requiredText(module.get("module"), "Missing loose consumer module");
                boolean fragment = unit.equals("dependency-closure") && name.equals("THC.InterfaceClosure") && Objects.equals(module.get("boundary"), "actual-interface-unfoldings");
                if (!fragment) require(modules.add(unit + ":" + name) && !availableModules.contains(unit + ":" + name), "Duplicate GHC module: " + unit + ":" + name);
                if (Objects.equals(module.get("boundary"), "optimized-Core-after-Tidy-before-CorePrep")) availableModules.add(unit + ":" + name);
                for (var binding : (List<Map<String,Object>>) module.get("bindings")) {
                    String id = (String) binding.get("id"); require(consumerBindings.putIfAbsent(id, binding) == null, "Duplicate binding: " + id);
                    consumerOwners.put(id, module);
                }
                addConstructors(module); consumers.add(module);
            });
            for (var module : consumers) {
                Object provided = module.get("providedModules");
                if (provided != null) require(Objects.equals(module.get("unit"), "dependency-closure") && Objects.equals(module.get("module"), "THC.InterfaceClosure") && Objects.equals(module.get("boundary"), "actual-interface-unfoldings") && provided instanceof List<?> list && list.stream().allMatch(item -> item instanceof String && availableModules.contains(item)), "Invalid or missing provided-module interface closure");
            }
        } catch (Throwable failure) { consumerSources.forEach(CoreJsonIndex::close); sources.close(); throw failure; }
        demand = new CoreDemandBindings(this::contains, this::binding, this::constructor, this::prepare, !Objects.equals(input.get("instrument"), false), id -> consumerBindings.containsKey(id) || sources.containsSymbol(id));
        // Cold summaries select a calling convention, not an admission verdict.
        captureDelimited = directory.getModules().stream().anyMatch(CoreUnitDirectory.ModuleRecord::containsDelimitedControl) || consumerBindings.values().stream().anyMatch(binding -> {
            Object body = binding.get("expr"); return body instanceof CoreBindingBody lazy ? lazy.getHeader().getContainsDelimitedControl() : DelimitedControl.INSTANCE.contains(body);
        });
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static String requiredText(Object value, String message) { if (value instanceof String text) return text; throw new IllegalStateException(message); }
    @Override public boolean getAsynchronousExceptions() { return async; }
    @Override public boolean getHasBytecode() { return backend.equals("bytecode"); }
    @Override public String bytecodeDump() { return String.join("\n\n", demand.preparedPrograms().stream().map(ExecutableProgram::bytecodeDump).toList()); }
    public boolean contains(String id) { return consumerBindings.containsKey(id) || directory.owner(id) != null; }
    private static Map<String,Object> withoutType(Map<String,Object> raw) { var copy = new LinkedHashMap<>(raw); copy.remove("type"); return copy; }
    private void addConstructors(Map<String,Object> data) {
        if (!(data.get("constructors") instanceof List<?> entries)) return;
        for (Object value : entries) {
            var raw = (Map<String,Object>) value; String id = requiredText(raw.get("id"), "Missing constructor identity");
            var previous = constructors.putIfAbsent(id, raw);
            require(previous == null || withoutType(previous).equals(withoutType(raw)), "Inconsistent constructor: " + id);
        }
    }
    private Map<String,Object> metadata(CoreUnitDirectory.ModuleRecord module) {
        var data = admittedModules.get(module);
        if (data == null) { data = sources.metadata(module); addConstructors(data); admittedModules.put(module, data); }
        return data;
    }
    private Map<String,Object> constructor(String id) {
        var found = constructors.get(id); if (found != null) return found;
        var module = directory.owner(id); if (module != null) metadata(module); return constructors.get(id);
    }
    public Map<String,Object> binding(String id) {
        var binding = consumerBindings.get(id);
        if (binding != null) { require(directory.owner(id) == null || sources.binding(id) == null, "Duplicate binding: " + id); return binding; }
        binding = sources.binding(id); if (binding == null) throw new UnsupportedCore("Unresolved external binding " + id); return binding;
    }
    private CoreModuleAdmission consumerAdmission(Map<String,Object> module) {
        var admission = consumerAdmissions.get(module);
        if (admission == null) {
            if (Objects.equals(input.get("verifyArtifacts"), true)) {
                CoreForeignArtifacts.validateArchive(module); CoreModules.admission(module, null); CoreForeignExceptionBridge.INSTANCE.read(module);
            }
            var projected = new LinkedHashMap<>(module); projected.put("bindings", List.of());
            admission = new CoreModuleAdmission(projected, this::binding); consumerAdmissions.put(module, admission);
        }
        return admission;
    }
    private CoreModuleAdmission admission(CoreUnitDirectory.ModuleRecord module) {
        var admission = admissions.get(module);
        if (admission == null) { sources.verifyModule(module); admission = new CoreModuleAdmission(metadata(module), this::binding); admissions.put(module, admission); }
        return admission;
    }
    private List<PackageScalarAdmission> packageProvenance(String unit) {
        var provenance = packageProvenance.get(unit);
        if (provenance == null) {
            provenance = new ArrayList<>();
            for (var module : directory.getModules()) if (module.unit().equals(unit) && module.packageScalarDeclarations()) {
                var link = admission(module).getPackageLink(); if (link != null) provenance.add(link);
            }
            for (var module : consumers) if (Objects.equals(module.get("unit"), unit) && module.get("staticForeignImports") instanceof Map<?,?> proof && proof.get("imports") instanceof List<?> imports && !imports.isEmpty()) {
                var link = consumerAdmission(module).getPackageLink(); if (link != null) provenance.add(link);
            }
            packageProvenance.put(unit, provenance);
        }
        return provenance;
    }
    private Map<String,Object> bridge() {
        if (selectedBridge != null) return selectedBridge;
        String unit = directory.getForeignExceptionBridgeUnit();
        var candidates = directory.getModules().stream().filter(module -> module.name().equals("THC.Internal.Exception") && (unit == null || module.unit().equals(unit))).toList();
        var loose = consumers.stream().filter(module -> Objects.equals(module.get("module"), "THC.Internal.Exception") && (unit == null || Objects.equals(module.get("unit"), unit))).toList();
        require(candidates.size() + loose.size() == 1, "Missing or ambiguous foreign exception bridge unit");
        var selected = loose.isEmpty() ? admission(candidates.getFirst()) : consumerAdmission(loose.getFirst());
        if (selected.getBridge() == null) throw new IllegalStateException("Foreign execution requires a genuine THC.Exception runtime bundle");
        selectedBridge = selected.getBridge(); return selectedBridge;
    }
    private ExecutableProgram prepare(String id, Map<String,Object> binding) {
        var consumer = consumerOwners.get(id); CoreModuleAdmission admitted;
        if (consumer != null) admitted = consumerAdmission(consumer);
        else { var module = directory.owner(id); if (module == null) throw new IllegalStateException("Missing Core module owner: " + id); admitted = admission(module); }
        var merger = new CoreModules.Merger(availableModules); merger.addSelected(admitted, List.of(binding));
        if (admitted.getPackageLink() != null) packageProvenance((String) admitted.getModule().get("unit")).forEach(merger::addPackageProvenance);
        var linked = new LinkedHashMap<>(CoreModules.demanded(merger.finish(), id, demand, this::bridge));
        linked.put("instrument", !Objects.equals(input.get("instrument"), false)); linked.put("diagnosticUnsupported", Objects.equals(input.get("diagnosticUnsupported"), true));
        linked.put("sourceNotesEnabled", !Objects.equals(input.get("sourceNotesEnabled"), false)); linked.put("demandBindings", demand); linked.put("captureDelimited", captureDelimited);
        if (directory.getTargetLayout() != null) linked.put("targetLayout", directory.getTargetLayout());
        if (linkedAdmissions.add(admitted)) {
            for (var link : (List<ForeignBitcode>) linked.get("foreignLinks")) owner.cbits().link(link);
            for (var link : (List<PackageScalarLink>) linked.get("packageScalarLinks")) owner.getPackageCbits().link(link);
        }
        return backend.equals("ast") ? new Program(language, linked, async, false) : new BytecodeProgram(language, linked, async);
    }
    public List<ManagedExportAdmission> registerStartup() {
        var registrations = new ArrayList<ManagedExportAdmission>(); var pending = new ArrayList<CoreModuleAdmission>();
        for (var module : directory.getModules()) if (module.registrationObligations()) pending.add(admission(module));
        for (var module : consumers) if (CoreForeignArtifacts.hasRegistrationObligations(module)) pending.add(consumerAdmission(module));
        for (var admitted : pending) {
            var merger = new CoreModules.Merger(availableModules); merger.addSelected(admitted, List.of()); merger.finish();
            var exports = admitted.getExports();
            if (exports != null) { registrations.add(exports); for (var exported : exports.getExports()) entryValue(exported.binder()); }
        }
        return registrations;
    }
    /** Follow only the selected alias/PAP spine, never unrelated body references. */
    public List<Map<String,Object>> signatureBindings(String id) {
        var selected = new LinkedHashMap<String,Map<String,Object>>(); var binding = binding(id); selected.put(id, binding);
        follow((List<Object>) binding.get("expr"), selected); return new ArrayList<>(selected.values());
    }
    private void follow(List<Object> expression, Map<String,Map<String,Object>> selected) {
        if (expression.isEmpty()) return;
        if (Objects.equals(expression.getFirst(), "var")) {
            String next = (String) expression.get(1);
            if (!selected.containsKey(next)) { var binding = binding(next); selected.put(next, binding); follow((List<Object>) binding.get("expr"), selected); }
        } else if (Objects.equals(expression.getFirst(), "app")) follow((List<Object>) expression.get(1), selected);
    }
    private ExecutableProgram hostProgram() {
        if (entry != null) return demand.program(entry);
        var programs = demand.preparedPrograms(); if (programs.isEmpty()) throw new IllegalStateException("No admitted managed export"); return programs.getFirst();
    }
    @Override public RootCallTarget hostEntryTarget(int arity) { return hostProgram().hostEntryTarget(arity); }
    @Override public Object entryValue(String name) { var cell = demand.cell(name); if (cell == null) throw new UnsupportedCore("Unresolved external binding " + name); return cell.read(); }
    @Override public RootCallTarget entryTarget(String name) { return demand.program(name).entryTarget(name); }
    @Override public DataLayout constructorLayout(String id) { return hostProgram().constructorLayout(id); }
    @Override public Map<String,Object> diagnostics() {
        var programs = demand.preparedPrograms().stream().map(ExecutableProgram::diagnostics).toList();
        var result = new LinkedHashMap<>(programs.getFirst());
        for (String field : List.of("loweredRootCount", "bytecodeRootCount", "sourceRootCount", "hostEntryRootCount", "initializedBindingCount")) {
            if (programs.stream().anyMatch(program -> program.containsKey(field))) result.put(field, programs.stream().mapToLong(program -> program.get(field) instanceof Number value ? value.longValue() : 0L).sum());
        }
        var counters = totals.stream().map(CoreJsonSymbols.Counters::statistics).toList();
        result.put("unsupportedPolicy", "reject-at-binding-admission");
        var compact = compactTotals.stream().map(CoreCompactFile.Counters::statistics).toList();
        result.put("coreUnitSourceOpens", counters.stream().mapToLong(CoreJsonSymbols.Statistics::sourceOpens).sum());
        result.put("coreUnitDirectoryOpens", counters.stream().mapToLong(CoreJsonSymbols.Statistics::directoryOpens).sum());
        result.put("coreUnitSourceMappedBytes", counters.stream().mapToLong(CoreJsonSymbols.Statistics::sourceMappedBytes).sum());
        result.put("coreUnitDirectoryMappedBytes", counters.stream().mapToLong(CoreJsonSymbols.Statistics::directoryMappedBytes).sum());
        result.put("coreUnitSourceByteReads", counters.stream().mapToLong(CoreJsonSymbols.Statistics::sourceByteReads).sum());
        result.put("coreUnitDirectoryByteReads", counters.stream().mapToLong(CoreJsonSymbols.Statistics::directoryByteReads).sum());
        result.put("coreUnitHashBytesScanned", counters.stream().mapToLong(CoreJsonSymbols.Statistics::hashBytesScanned).sum());
        result.put("coreUnitDecodedBindings", counters.stream().mapToLong(CoreJsonSymbols.Statistics::decodedBindings).sum());
        result.put("coreUnitDecodedModules", counters.stream().mapToLong(CoreJsonSymbols.Statistics::decodedModules).sum());
        result.put("coreUnitDecodedBytes", counters.stream().mapToLong(CoreJsonSymbols.Statistics::decodedBytes).sum());
        result.put("coreUnitMetadataBytes", counters.stream().mapToLong(CoreJsonSymbols.Statistics::metadataBytes).sum());
        result.put("coreUnitVerifiedModuleBytes", counters.stream().mapToLong(CoreJsonSymbols.Statistics::verifiedModuleBytes).sum());
        result.put("coreUnitPhysicalMappingOpens", counters.stream().mapToLong(CoreJsonSymbols.Statistics::physicalMappingOpens).sum());
        result.put("coreUnitMappingCacheHits", counters.stream().mapToLong(CoreJsonSymbols.Statistics::mappingCacheHits).sum());
        result.put("coreCompactModuleOpens", compact.stream().mapToLong(CoreCompactFile.Statistics::acquisitions).sum());
        result.put("coreCompactMappedBytes", compact.stream().mapToLong(CoreCompactFile.Statistics::mappedBytes).sum());
        result.put("coreCompactDirectoryBytesRead", compact.stream().mapToLong(CoreCompactFile.Statistics::directoryBytesRead).sum());
        result.put("coreCompactMemberInflations", compact.stream().mapToLong(CoreCompactFile.Statistics::memberInflations).sum());
        result.put("coreCompactInflatedBytes", compact.stream().mapToLong(CoreCompactFile.Statistics::inflatedBytes).sum());
        result.put("coreCompactCompressedBytesRead", compact.stream().mapToLong(CoreCompactFile.Statistics::compressedBytesRead).sum());
        result.put("coreCompactSlabCacheHits", compact.stream().mapToLong(CoreCompactFile.Statistics::slabCacheHits).sum());
        result.put("coreCompactVerifiedStoredBytes", compact.stream().mapToLong(CoreCompactFile.Statistics::verifiedStoredBytes).sum());
        result.put("coreCompactHeaderBytesRead", compact.stream().mapToLong(CoreCompactFile.Statistics::headerBytesRead).sum());
        result.put("coreCompactLookupBytesRead", compact.stream().mapToLong(CoreCompactFile.Statistics::lookupBytesRead).sum());
        result.put("coreCompactDataBytesRead", compact.stream().mapToLong(CoreCompactFile.Statistics::dataBytesRead).sum());
        result.put("coreCompactStringBytesRead", compact.stream().mapToLong(CoreCompactFile.Statistics::stringBytesRead).sum());
        result.put("coreCompactDebugBytesRead", compact.stream().mapToLong(CoreCompactFile.Statistics::debugBytesRead).sum());
        result.put("coreCompactHashBytesScanned", compact.stream().mapToLong(CoreCompactFile.Statistics::hashBytesRead).sum());
        result.put("coreCompactDecodedBindings", compact.stream().mapToLong(CoreCompactFile.Statistics::decodedBindings).sum());
        result.put("coreCompactDecodedModules", compact.stream().mapToLong(CoreCompactFile.Statistics::decodedModules).sum());
        result.put("coreCompactPhysicalMappingOpens", compact.stream().mapToLong(CoreCompactFile.Statistics::physicalOpens).sum());
        result.put("coreCompactMappingCacheHits", compact.stream().mapToLong(CoreCompactFile.Statistics::cacheHits).sum());
        result.put("looseConsumerBindingHeaders", consumerBindings.size()); result.putAll(consumerStatistics.get()); return result;
    }
    @Override public void close() { try { sources.close(); } finally { consumerSources.forEach(CoreJsonIndex::close); } }
}
