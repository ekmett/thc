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
                if (provided != null) {
                    boolean valid = Objects.equals(module.get("unit"), "dependency-closure") && Objects.equals(module.get("module"), "THC.InterfaceClosure") && Objects.equals(module.get("boundary"), "actual-interface-unfoldings") && provided instanceof List<?>;
                    if (valid) for (Object item : (List<?>) provided) {
                        if (!(item instanceof String) || !availableModules.contains(item)) { valid = false; break; }
                    }
                    require(valid, "Invalid or missing provided-module interface closure");
                }
            }
        } catch (Throwable failure) { consumerSources.forEach(CoreJsonIndex::close); sources.close(); throw failure; }
        demand = new CoreDemandBindings(this::contains, this::binding, this::constructor, this::prepare, !Objects.equals(input.get("instrument"), false), id -> consumerBindings.containsKey(id) || sources.containsSymbol(id));
        // Cold summaries select a calling convention, not an admission verdict.
        boolean delimited = false;
        for (var module : directory.getModules()) if (module.containsDelimitedControl()) { delimited = true; break; }
        if (!delimited) for (var binding : consumerBindings.values()) {
            Object body = binding.get("expr");
            if (body instanceof CoreBindingBody lazy ? lazy.getHeader().getContainsDelimitedControl() : DelimitedControl.INSTANCE.contains(body)) { delimited = true; break; }
        }
        captureDelimited = delimited;
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static String requiredText(Object value, String message) { if (value instanceof String text) return text; throw new IllegalStateException(message); }
    @Override public boolean getAsynchronousExceptions() { return async; }
    @Override public boolean getHasBytecode() { return backend.equals("bytecode"); }
    @Override public String bytecodeDump() {
        var dumps = new ArrayList<String>();
        for (var program : demand.preparedPrograms()) dumps.add(program.bytecodeDump());
        return String.join("\n\n", dumps);
    }
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
                CoreForeignArtifacts.validateArchive(module); CoreModules.admission(module, null); CoreForeignExceptionBridge.read(module);
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
    private ForeignBitcode capiProvenance(String unit, String name) {
        var candidates = new ArrayList<CoreUnitDirectory.ModuleRecord>();
        for (var module : directory.getModules()) if (module.unit().equals(unit) && module.name().equals(name)) candidates.add(module);
        var loose = new ArrayList<Map<String,Object>>();
        for (var module : consumers) if (Objects.equals(module.get("unit"), unit) && Objects.equals(module.get("module"), name)) loose.add(module);
        require(candidates.size() + loose.size() == 1, "Missing or ambiguous CAPI declaration: " + unit + ":" + name);
        return (loose.isEmpty() ? admission(candidates.getFirst()) : consumerAdmission(loose.getFirst())).getForeignLink();
    }
    private List<PackageScalarAdmission> packageProvenance(String unit) {
        var provenance = packageProvenance.get(unit);
        if (provenance == null) {
            provenance = new ArrayList<>();
            for (var module : directory.getModules()) if (module.unit().equals(unit) && module.packageScalarDeclarations()) {
                var link = admission(module).getPackageLink(); if (link != null) provenance.add(link);
            }
            for (var module : consumers) if (Objects.equals(module.get("unit"), unit) && PackageFinalizers.hasDeclarations(module)) {
                var link = consumerAdmission(module).getPackageLink(); if (link != null) provenance.add(link);
            }
            packageProvenance.put(unit, provenance);
        }
        return provenance;
    }
    private Map<String,Object> bridge() {
        if (selectedBridge != null) return selectedBridge;
        String unit = directory.getForeignExceptionBridgeUnit();
        var candidates = new ArrayList<CoreUnitDirectory.ModuleRecord>();
        for (var module : directory.getModules()) if (module.name().equals("THC.Internal.Exception") && (unit == null || module.unit().equals(unit))) candidates.add(module);
        var loose = new ArrayList<Map<String,Object>>();
        for (var module : consumers) if (Objects.equals(module.get("module"), "THC.Internal.Exception") && (unit == null || Objects.equals(module.get("unit"), unit))) loose.add(module);
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
        var provenance = CoreCapiProvenance.supplement(merger.finish(), binding, this::capiProvenance);
        var linked = new LinkedHashMap<>(CoreModules.demanded(provenance, id, demand, this::bridge));
        linked.put("instrument", !Objects.equals(input.get("instrument"), false)); linked.put("diagnosticUnsupported", Objects.equals(input.get("diagnosticUnsupported"), true));
        linked.put("sourceNotesEnabled", !Objects.equals(input.get("sourceNotesEnabled"), false)); linked.put("demandBindings", demand); linked.put("captureDelimited", captureDelimited);
        if (directory.getTargetLayout() != null) linked.put("targetLayout", directory.getTargetLayout());
        // Another binding may demand a new original CAPI owner. The context
        // registry checks exact identity and links the component only once.
        for (var link : (List<ForeignBitcode>) linked.get("foreignLinks")) owner.cbits().link(link);
        if (linkedAdmissions.add(admitted)) {
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
        var programs = new ArrayList<Map<String,Object>>();
        for (var program : demand.preparedPrograms()) programs.add(program.diagnostics());
        var result = new LinkedHashMap<>(programs.getFirst());
        for (String field : List.of("loweredRootCount", "bytecodeRootCount", "sourceRootCount", "hostEntryRootCount", "initializedBindingCount")) {
            boolean present = false;
            for (var program : programs) if (program.containsKey(field)) { present = true; break; }
            if (present) {
                long total = 0;
                for (var program : programs) total += program.get(field) instanceof Number value ? value.longValue() : 0L;
                result.put(field, total);
            }
        }
        var counters = new ArrayList<CoreJsonSymbols.Statistics>();
        for (var counter : totals) counters.add(counter.statistics());
        result.put("unsupportedPolicy", "reject-at-binding-admission");
        var compact = new ArrayList<CoreCompactFile.Statistics>();
        for (var counter : compactTotals) compact.add(counter.statistics());
        long coreUnitSourceOpens = 0;
        for (var item : counters) coreUnitSourceOpens += item.sourceOpens();
        result.put("coreUnitSourceOpens", coreUnitSourceOpens);
        long coreUnitDirectoryOpens = 0;
        for (var item : counters) coreUnitDirectoryOpens += item.directoryOpens();
        result.put("coreUnitDirectoryOpens", coreUnitDirectoryOpens);
        long coreUnitSourceMappedBytes = 0;
        for (var item : counters) coreUnitSourceMappedBytes += item.sourceMappedBytes();
        result.put("coreUnitSourceMappedBytes", coreUnitSourceMappedBytes);
        long coreUnitDirectoryMappedBytes = 0;
        for (var item : counters) coreUnitDirectoryMappedBytes += item.directoryMappedBytes();
        result.put("coreUnitDirectoryMappedBytes", coreUnitDirectoryMappedBytes);
        long coreUnitSourceByteReads = 0;
        for (var item : counters) coreUnitSourceByteReads += item.sourceByteReads();
        result.put("coreUnitSourceByteReads", coreUnitSourceByteReads);
        long coreUnitDirectoryByteReads = 0;
        for (var item : counters) coreUnitDirectoryByteReads += item.directoryByteReads();
        result.put("coreUnitDirectoryByteReads", coreUnitDirectoryByteReads);
        long coreUnitHashBytesScanned = 0;
        for (var item : counters) coreUnitHashBytesScanned += item.hashBytesScanned();
        result.put("coreUnitHashBytesScanned", coreUnitHashBytesScanned);
        long coreUnitDecodedBindings = 0;
        for (var item : counters) coreUnitDecodedBindings += item.decodedBindings();
        result.put("coreUnitDecodedBindings", coreUnitDecodedBindings);
        long coreUnitDecodedModules = 0;
        for (var item : counters) coreUnitDecodedModules += item.decodedModules();
        result.put("coreUnitDecodedModules", coreUnitDecodedModules);
        long coreUnitDecodedBytes = 0;
        for (var item : counters) coreUnitDecodedBytes += item.decodedBytes();
        result.put("coreUnitDecodedBytes", coreUnitDecodedBytes);
        long coreUnitMetadataBytes = 0;
        for (var item : counters) coreUnitMetadataBytes += item.metadataBytes();
        result.put("coreUnitMetadataBytes", coreUnitMetadataBytes);
        long coreUnitVerifiedModuleBytes = 0;
        for (var item : counters) coreUnitVerifiedModuleBytes += item.verifiedModuleBytes();
        result.put("coreUnitVerifiedModuleBytes", coreUnitVerifiedModuleBytes);
        long coreUnitPhysicalMappingOpens = 0;
        for (var item : counters) coreUnitPhysicalMappingOpens += item.physicalMappingOpens();
        result.put("coreUnitPhysicalMappingOpens", coreUnitPhysicalMappingOpens);
        long coreUnitMappingCacheHits = 0;
        for (var item : counters) coreUnitMappingCacheHits += item.mappingCacheHits();
        result.put("coreUnitMappingCacheHits", coreUnitMappingCacheHits);
        long coreCompactModuleOpens = 0;
        for (var item : compact) coreCompactModuleOpens += item.acquisitions();
        result.put("coreCompactModuleOpens", coreCompactModuleOpens);
        long coreCompactMappedBytes = 0;
        for (var item : compact) coreCompactMappedBytes += item.mappedBytes();
        result.put("coreCompactMappedBytes", coreCompactMappedBytes);
        long coreCompactDirectoryBytesRead = 0;
        for (var item : compact) coreCompactDirectoryBytesRead += item.directoryBytesRead();
        result.put("coreCompactDirectoryBytesRead", coreCompactDirectoryBytesRead);
        long coreCompactMemberInflations = 0;
        for (var item : compact) coreCompactMemberInflations += item.memberInflations();
        result.put("coreCompactMemberInflations", coreCompactMemberInflations);
        long coreCompactInflatedBytes = 0;
        for (var item : compact) coreCompactInflatedBytes += item.inflatedBytes();
        result.put("coreCompactInflatedBytes", coreCompactInflatedBytes);
        long coreCompactCompressedBytesRead = 0;
        for (var item : compact) coreCompactCompressedBytesRead += item.compressedBytesRead();
        result.put("coreCompactCompressedBytesRead", coreCompactCompressedBytesRead);
        long coreCompactSlabCacheHits = 0;
        for (var item : compact) coreCompactSlabCacheHits += item.slabCacheHits();
        result.put("coreCompactSlabCacheHits", coreCompactSlabCacheHits);
        long coreCompactVerifiedStoredBytes = 0;
        for (var item : compact) coreCompactVerifiedStoredBytes += item.verifiedStoredBytes();
        result.put("coreCompactVerifiedStoredBytes", coreCompactVerifiedStoredBytes);
        long coreCompactHeaderBytesRead = 0;
        for (var item : compact) coreCompactHeaderBytesRead += item.headerBytesRead();
        result.put("coreCompactHeaderBytesRead", coreCompactHeaderBytesRead);
        long coreCompactLookupBytesRead = 0;
        for (var item : compact) coreCompactLookupBytesRead += item.lookupBytesRead();
        result.put("coreCompactLookupBytesRead", coreCompactLookupBytesRead);
        long coreCompactDataBytesRead = 0;
        for (var item : compact) coreCompactDataBytesRead += item.dataBytesRead();
        result.put("coreCompactDataBytesRead", coreCompactDataBytesRead);
        long coreCompactStringBytesRead = 0;
        for (var item : compact) coreCompactStringBytesRead += item.stringBytesRead();
        result.put("coreCompactStringBytesRead", coreCompactStringBytesRead);
        long coreCompactDebugBytesRead = 0;
        for (var item : compact) coreCompactDebugBytesRead += item.debugBytesRead();
        result.put("coreCompactDebugBytesRead", coreCompactDebugBytesRead);
        long coreCompactHashBytesScanned = 0;
        for (var item : compact) coreCompactHashBytesScanned += item.hashBytesRead();
        result.put("coreCompactHashBytesScanned", coreCompactHashBytesScanned);
        long coreCompactDecodedBindings = 0;
        for (var item : compact) coreCompactDecodedBindings += item.decodedBindings();
        result.put("coreCompactDecodedBindings", coreCompactDecodedBindings);
        long coreCompactDecodedModules = 0;
        for (var item : compact) coreCompactDecodedModules += item.decodedModules();
        result.put("coreCompactDecodedModules", coreCompactDecodedModules);
        long coreCompactPhysicalMappingOpens = 0;
        for (var item : compact) coreCompactPhysicalMappingOpens += item.physicalOpens();
        result.put("coreCompactPhysicalMappingOpens", coreCompactPhysicalMappingOpens);
        long coreCompactMappingCacheHits = 0;
        for (var item : compact) coreCompactMappingCacheHits += item.cacheHits();
        result.put("coreCompactMappingCacheHits", coreCompactMappingCacheHits);
        result.put("looseConsumerBindingHeaders", consumerBindings.size()); result.putAll(consumerStatistics.get()); return result;
    }
    @Override public void close() { try { sources.close(); } finally { consumerSources.forEach(CoreJsonIndex::close); } }
}
