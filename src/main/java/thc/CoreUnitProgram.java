// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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
    private final List<CoreCompactFile.Counters> compactTotals = new ArrayList<>();
    private final CoreUnitDirectory.Sources sources;
    private final Map<String,Map<String,Object>> constructors = new HashMap<>();
    private final Map<CoreUnitDirectory.ModuleRecord,Map<String,Object>> admittedModules = new HashMap<>();
    private final Map<CoreUnitDirectory.ModuleRecord,CoreModuleAdmission> admissions = new HashMap<>();
    private final Map<String,PackageScalarLink> components = new HashMap<>();
    private final Map<String,List<PackageScalarAdmission>> packageProvenance = new HashMap<>();
    private final Map<String,List<CoreBoxedForeignDeclarations>> boxedProvenance = new HashMap<>();
    private final Map<String,PackageScalarLink> startupLinks = new LinkedHashMap<>();
    private final List<Map<String,Object>> consumers = new ArrayList<>();
    private final Map<String,Map<String,Object>> consumerBindings = new HashMap<>(), consumerOwners = new HashMap<>();
    private final IdentityHashMap<Map<String,Object>,CoreModuleAdmission> consumerAdmissions = new IdentityHashMap<>();
    private final IdentityHashMap<CoreModuleAdmission,CoreSources> moduleSources = new IdentityHashMap<>();
    private final Set<String> availableModules = new HashSet<>();
    private Map<String,Object> selectedBridge;
    private final CoreDemandBindings demand;
    private final boolean captureDelimited;
    public CoreUnitProgram(Language language, CoreUnitDirectory directory, Map<String,Object> input, String entry, String backend, boolean async, Language.State owner) {
        this.language = language; this.directory = directory; this.input = input; this.entry = entry;
        this.backend = backend; this.async = async; this.owner = owner;
        sources = directory.open(Objects.equals(input.get("verifyArtifacts"), true), !Objects.equals(input.get("sourceNotesEnabled"), false), compactTotals::add);
        var modules = new HashSet<String>();
        for (var module : directory.getModules()) {
            String id = module.unit() + ":" + module.name();
            modules.add(id);
            availableModules.add(id);
        }
        try {
            CoreModules.visitUnitConsumers(input, sources, rawModule -> {
                var module = (Map<String,Object>) rawModule;
                String unit = requiredText(module.get("unit"), "Missing loose consumer unit"), name = requiredText(module.get("module"), "Missing loose consumer module");
                boolean fragment = unit.equals("dependency-closure") && name.equals("THC.InterfaceClosure") && Objects.equals(module.get("boundary"), "actual-interface-unfoldings");
                if (!fragment) require(modules.add(unit + ":" + name), "Duplicate GHC module: " + unit + ":" + name);
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
        } catch (Throwable failure) { sources.close(); throw failure; }
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
    @Override public boolean getCapturesContinuations() { return true; }
    public TargetLayout getTargetLayout() { return sources.getTargetLayout(); }
    // Member discovery snapshots cold admissions, not guest executable state.
    @Override @TruffleBoundary public boolean getHasBytecode() { return demand.preparedPrograms().stream().anyMatch(ExecutableProgram::getHasBytecode); }
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
            admission = new CoreModuleAdmission(projected, this::binding, components); consumerAdmissions.put(module, admission);
        }
        return admission;
    }
    private CoreModuleAdmission admission(CoreUnitDirectory.ModuleRecord module) {
        var admission = admissions.get(module);
        if (admission == null) { sources.verifyModule(module); admission = new CoreModuleAdmission(metadata(module), this::binding, components); admissions.put(module, admission); }
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
            var boxed = new ArrayList<CoreBoxedForeignDeclarations>();
            for (var module : directory.getModules()) if (module.unit().equals(unit) && module.packageScalarDeclarations()) {
                var admitted = admission(module); var link = admitted.getPackageLink(); if (link != null) provenance.add(link);
                if (admitted.getBoxedImports() != null) boxed.add(admitted.getBoxedImports());
            }
            for (var module : consumers) if (Objects.equals(module.get("unit"), unit) && PackageFinalizers.hasDeclarations(module)) {
                var admitted = consumerAdmission(module); var link = admitted.getPackageLink(); if (link != null) provenance.add(link);
                if (admitted.getBoxedImports() != null) boxed.add(admitted.getBoxedImports());
            }
            boxedProvenance.put(unit, List.copyOf(boxed));
            packageProvenance.put(unit, provenance);
        }
        return provenance;
    }
    private static void addressLabels(Object value, Set<String> labels) {
        if (value instanceof List<?> fields) {
            if (fields.size() >= 3 && Objects.equals(fields.getFirst(), "lit") &&
                    (Objects.equals(fields.get(1), "function-addr") || Objects.equals(fields.get(1), "data-addr")) && fields.get(2) instanceof String symbol)
                labels.add(symbol);
            else for (Object field : fields) addressLabels(field, labels);
        } else if (value instanceof Map<?,?> fields) for (Object field : fields.values()) addressLabels(field, labels);
    }
    private static void addressOwners(Map<String,Object> module, Set<String> labels, Map<String,String> owners) {
        if (!(module.get("packageNativeLink") instanceof Map<?,?> link) || !(link.get("abi") instanceof List<?> abi)) return;
        var addresses = new HashSet<Object>();
        if (link.get("dataSymbols") instanceof List<?> entries) addresses.addAll(entries);
        if (link.get("finalizers") instanceof List<?> entries) addresses.addAll(entries);
        for (Object raw : abi) if (raw instanceof Map<?,?> signature && addresses.contains(signature.get("entry")) &&
                signature.get("symbol") instanceof String symbol && labels.contains(symbol)) {
            String unit = requiredText(module.get("unit"), "Missing native label owner");
            var previous = owners.putIfAbsent(symbol, unit);
            require(previous == null || previous.equals(unit), "Ambiguous native address declaration: " + symbol);
        }
    }
    private Set<String> addressProvenance(String unit, Object binding) {
        var labels = new HashSet<String>(); addressLabels(binding, labels);
        if (labels.isEmpty()) return Set.of();
        // GHC inlines CLabels without retaining their source unit in the
        // literal. Consult only declared dependency metadata, never unrelated
        // units or the original Haskell wrapper's body. Full component/type
        // admission below remains authoritative; this only selects candidates.
        var dependencies = new HashSet<String>(); var pending = new ArrayDeque<String>(); pending.add(unit);
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (dependencies.add(next)) for (var record : directory.getUnits())
                if (record.id().equals(next)) pending.addAll(record.depends());
        }
        var owners = new LinkedHashMap<String,String>();
        for (var module : directory.getModules()) if (dependencies.contains(module.unit()) && module.packageScalarDeclarations())
            addressOwners(metadata(module), labels, owners);
        for (var module : consumers) if (dependencies.contains(module.get("unit"))) addressOwners(module, labels, owners);
        return new LinkedHashSet<>(owners.values());
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
        // Optimized Core can inline an import into another package's binding.
        // Resolve that package's compiled component from cold metadata, without
        // demanding the source Haskell wrapper or any unrelated binding body.
        var foreignUnits = new LinkedHashSet<String>();
        if (admitted.getPackageLink() != null) foreignUnits.add((String) admitted.getModule().get("unit"));
        for (var call : CoreCallInventory.mapCalls(binding)) {
            if (call.get("target") instanceof Map<?, ?> target && "static".equals(target.get("kind"))
                    && target.get("unit") instanceof String unit) foreignUnits.add(unit);
        }
        foreignUnits.addAll(addressProvenance((String) admitted.getModule().get("unit"), binding));
        for (String unit : foreignUnits) {
            packageProvenance(unit).forEach(merger::addPackageProvenance);
            boxedProvenance.get(unit).forEach(merger::addBoxedProvenance);
        }
        var provenance = CoreCapiProvenance.supplement(merger.finish(), binding, this::capiProvenance);
        var linked = new LinkedHashMap<>(CoreModules.demanded(provenance, id, demand, this::bridge,
            () -> directory.runtimeUnit(sources::metadata, consumers)));
        linked.put("instrument", !Objects.equals(input.get("instrument"), false)); linked.put("diagnosticUnsupported", Objects.equals(input.get("diagnosticUnsupported"), true));
        linked.put("sourceNotesEnabled", !Objects.equals(input.get("sourceNotesEnabled"), false)); linked.put("demandBindings", demand); linked.put("captureDelimited", captureDelimited);
        if (getTargetLayout() != null) linked.put("targetLayout", getTargetLayout());
        // Every demanded binding keeps the same immutable source notes for its
        // admitted module, rather than retaining another complete source table.
        synchronized (moduleSources) {
            linked.put("preparedSources", moduleSources.computeIfAbsent(admitted, ignored -> new CoreSources(linked)));
        }
        String selectedBackend = CoreModules.backend(admitted.getModule(), id, backend);
        // Native constructors and Truffle initialization handshakes run in the
        // demanded cell's execution stage, after lowering ownership ends.
        return selectedBackend.equals("ast") ? new Program(language, linked, async, false) : new BytecodeProgram(language, linked, async);
    }
    public List<ManagedExportAdmission> registerStartup() {
        var registrations = new ArrayList<ManagedExportAdmission>(); var pending = new ArrayList<CoreModuleAdmission>();
        for (var module : directory.getModules()) if (module.registrationObligations()) pending.add(admission(module));
        for (var module : consumers) if (CoreForeignArtifacts.hasRegistrationObligations(module)) pending.add(consumerAdmission(module));
        for (var admitted : pending) {
            var merger = new CoreModules.Merger(availableModules); merger.addSelected(admitted, List.of());
            if (admitted.getPackageLink() != null) packageProvenance((String) admitted.getModule().get("unit")).forEach(merger::addPackageProvenance);
            for (var link : (List<PackageScalarLink>) merger.finish().get("packageScalarLinks")) {
                owner.getPackageCbits().declare(link);
                synchronized (startupLinks) { startupLinks.put(link.getUnit(), link); }
            }
            var exports = admitted.getExports();
            if (exports != null) registrations.add(exports);
        }
        return registrations;
    }
    /** Called after callback publication, before any root preparation. */
    public void linkStartup() {
        List<PackageScalarLink> links;
        synchronized (startupLinks) { links = new ArrayList<>(startupLinks.values()); }
        for (var link : links) owner.getPackageCbits().link(link);
    }
    /** Selected binding first, followed by unique bindings on its alias/PAP
     * spine in discovery order. Never follows unrelated body references. */
    public List<Map<String,Object>> signatureBindings(String id) {
        try (var ownership = demand.getPreparationLock().acquire()) {
            var selected = new LinkedHashMap<String,Map<String,Object>>(); var binding = binding(id); selected.put(id, binding);
            follow((List<Object>) binding.get("expr"), selected); return new ArrayList<>(selected.values());
        }
    }
    private void follow(List<Object> expression, Map<String,Map<String,Object>> selected) {
        if (expression.isEmpty()) return;
        if (Objects.equals(expression.getFirst(), "var")) {
            String next = (String) expression.get(1);
            if (!selected.containsKey(next)) { var binding = binding(next); selected.put(next, binding); follow((List<Object>) binding.get("expr"), selected); }
        } else if (Objects.equals(expression.getFirst(), "app")) follow((List<Object>) expression.get(1), selected);
    }
    private ExecutableProgram hostProgram() {
        // A native constructor can call an export before the public entry is
        // prepared. Host roots/layouts use the same shared demand state.
        var programs = demand.preparedPrograms();
        if (!programs.isEmpty()) return programs.getFirst();
        if (entry != null) return demand.program(entry);
        throw new IllegalStateException("No admitted managed export");
    }
    @Override public RootCallTarget hostEntryTarget(int arity) { return hostProgram().hostEntryTarget(arity); }
    @Override public Object entryValue(String name) { var cell = demand.cell(name); if (cell == null) throw new UnsupportedCore("Unresolved external binding " + name); return cell.read(); }
    @Override public RootCallTarget entryTarget(String name) { return demand.program(name).entryTarget(name); }
    @Override public DataLayout constructorLayout(String id) { return hostProgram().constructorLayout(id); }
    @Override public Map<String,Object> rootCounts() {
        var result = new LinkedHashMap<String,Object>();
        for (var program : demand.preparedPrograms()) for (var count : program.rootCounts().entrySet())
            result.put(count.getKey(), ((Number) result.getOrDefault(count.getKey(), 0L)).longValue() + ((Number) count.getValue()).longValue());
        return result;
    }
    @Override public Map<String,Object> diagnostics() {
        // All demanded programs share Metrics; copy its label snapshot once.
        var prepared = demand.preparedPrograms();
        var result = new LinkedHashMap<>(prepared.getFirst().diagnostics());
        boolean ast = false, bytecode = false;
        for (var program : prepared) {
            if (program.getHasBytecode()) bytecode = true; else ast = true;
        }
        result.put("backend", ast && bytecode ? "mixed" : bytecode ? "bytecode" : "ast");
        result.putAll(rootCounts());
        result.put("unsupportedPolicy", "reject-at-binding-admission");
        var compact = new ArrayList<CoreCompactFile.Statistics>();
        for (var counter : compactTotals) compact.add(counter.statistics());
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
        result.put("coreInterfaceModuleConversions", sources.interfaceConversions());
        result.put("looseConsumerBindingHeaders", consumerBindings.size()); return result;
    }
    @Override public void close() { sources.close(); }
}
