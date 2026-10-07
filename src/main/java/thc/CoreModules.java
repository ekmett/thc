// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import thc.runtime.*;

/** Internal Core assembly and request serialization. Original unit identities,
 * foreign obligations and strict reachable references remain validated. */
@SuppressWarnings("unchecked")
public final class CoreModules {
    public static final CoreModules INSTANCE = new CoreModules();
    private CoreModules() {}
    // Only the host request builder can authorize deferred host-file reads.
    private static final byte[] PACKAGE_KEY = new byte[32];
    static { new SecureRandom().nextBytes(PACKAGE_KEY); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static String text(Object value, String message) { if (value instanceof String s) return s; throw new IllegalStateException(message); }
    private static String packageCapability(String path, String hash, boolean verify) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(PACKAGE_KEY, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((verify + ":" + path.length() + ":" + path + ":" + hash).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }
    public static ManagedExportAdmission admission(Map<String,Object> module) { return admission(module, null); }
    public static ManagedExportAdmission admission(Map<String,Object> module, Function<String,Map<String,Object>> binding) {
        return (Objects.equals(module.get("schema"), 2L) || Objects.equals(module.get("schema"), 2)) && module.containsKey("staticForeignExportRegistration") && CoreForeignArtifacts.hasRegistrationObligations(module) ? ManagedExportAdmission.read(module, binding) : null;
    }
    public static Map<String,Object> merge(List<Map<String,Object>> modules) { var merger = new Merger(); modules.forEach(merger::add); return merger.finish(); }
    private record ModuleKey(String unit, String name) {}
    private static Map<String,Object> without(Map<String,Object> map, String... keys) { var copy = new LinkedHashMap<>(map); for (String key : keys) copy.remove(key); return copy; }
    private static Map<String,Object> with(Map<String,Object> map, String key, Object value) { var copy = new LinkedHashMap<>(map); copy.put(key, value); return copy; }

    /** Resolve an admitted module's policy only when its binding needs code. */
    static String backend(Map<String,Object> module, String binding, String fallback) {
        if (!module.containsKey("backendPolicy")) return fallback;
        require(module.get("backendPolicy") instanceof Map<?,?>, "Invalid Core backend policy");
        var policy = (Map<?,?>) module.get("backendPolicy");
        require(policy.get("bindings") instanceof Map<?,?>, "Missing Core backend binding policies");
        var bindings = (Map<?,?>) policy.get("bindings");
        Object selected = bindings.containsKey(binding) ? bindings.get(binding) : policy.get("default");
        if (selected == null && !bindings.containsKey(binding) && !policy.containsKey("default")) return fallback;
        require(Objects.equals(selected, "ast") || Objects.equals(selected, "bytecode"), "Invalid Core backend policy for " + binding);
        return (String) selected;
    }

    /** Retain linked definitions, never the complete raw package request. */
    public static final class Merger {
        private final Set<String> availableModules;
        private int count;
        private final List<ManagedExportAdmission> admissions = new ArrayList<>();
        private final List<CoreBoxedForeignDeclarations> boxedImports = new ArrayList<>();
        private final Map<String,Map<String,Object>> bindings = new LinkedHashMap<>(), constructors = new LinkedHashMap<>(), sourceFiles = new LinkedHashMap<>(), sourceSpans = new LinkedHashMap<>();
        private final Map<String,Map<String,String>> bindingOrigins = new LinkedHashMap<>();
        private final Map<ModuleKey,ForeignBitcode> foreignLinks = new LinkedHashMap<>();
        private final Map<String,PackageScalarLink> packageScalarLinks = new LinkedHashMap<>();
        private final Map<String,Set<String>> packageScalarProofs = new LinkedHashMap<>();
        private final Map<String,ManagedCallbackSignature> nativeCallbacks = new LinkedHashMap<>();
        private final Map<String,String> archiveBindings = new LinkedHashMap<>();
        private final Map<String,Map<String,Object>> exceptionBridges = new LinkedHashMap<>();
        private String exceptionBridgeUnit;
        private final Set<ModuleKey> moduleKeys = new HashSet<>();
        private final Set<String> providedModules = new HashSet<>(), completeModules = new HashSet<>();
        public Merger() { this(Set.of()); }
        public Merger(Set<String> availableModules) { this.availableModules = availableModules; }
        public void add(Map<String,Object> module) { add(module, admission(module)); }
        public void add(Map<String,Object> module, ManagedExportAdmission admission) { append(module, admission, null); }
        public void addSelected(CoreModuleAdmission admission, List<Map<String,Object>> bindings) { append(admission.selected(bindings), admission.getExports(), admission); }
        /** Detached bodies remain a counted subset of the unchanged original inventory. */
        void addDetached(Map<String,Object> module) {
            var selected = (List<Map<String,Object>>) module.get("bindings");
            var definitions = new LinkedHashMap<String,Map<String,Object>>();
            for (var binding : selected) require(definitions.putIfAbsent((String) binding.get("id"), binding) == null, "Duplicate detached binding");
            var admission = new CoreModuleAdmission(module, id -> {
                var binding = definitions.get(id); require(binding != null, "Missing detached registration or bridge helper: " + id); return binding;
            });
            addSelected(admission, selected);
            if (admission.getExports() != null) admissions.add(admission.getExports());
        }
        /** Declaration-only modules contribute typed ABI provenance, not roots. */
        public void addPackageProvenance(PackageScalarAdmission admission) { packageProvenance(admission); }
        public void addBoxedProvenance(CoreBoxedForeignDeclarations admission) { boxedImports.add(admission); }
        private void packageProvenance(PackageScalarAdmission admission) {
            var link = admission.link();
            boolean unique = true;
            for (var value : packageScalarLinks.values()) if (!value.getUnit().equals(link.getUnit()) && value.getComponentSha256().equals(link.getComponentSha256())) { unique = false; break; }
            require(unique, "Package C entry namespace belongs to another unit: " + link.getComponentSha256());
            var previous = packageScalarLinks.putIfAbsent(link.getUnit(), link);
            require(previous == null || previous.same(link), "Conflicting package C component: " + link.getUnit());
            packageScalarProofs.computeIfAbsent(link.getUnit(), ignored -> new LinkedHashSet<>()).addAll(admission.proved());
        }
        private void append(Map<String,Object> module, ManagedExportAdmission admission, CoreModuleAdmission prepared) {
            require(admission == null || admission.getModule() == (prepared == null ? module : prepared.getModule()), "Managed export admission belongs to a different Core module");
            count++;
            var proof = prepared == null ? CoreForeignExceptionBridge.read(module) : prepared.getBridge();
            if (proof != null) require(exceptionBridges.putIfAbsent((String) proof.get("unit"), proof) == null, "Duplicate foreign exception bridge unit");
            if (module.get("foreignExceptionBridgeUnit") instanceof String unit) {
                require(exceptionBridgeUnit == null || exceptionBridgeUnit.equals(unit), "Conflicting foreign exception bridge selection"); exceptionBridgeUnit = unit;
            }
            // Direct-unit startup registration belongs to CoreUnitProgram.
            if (admission != null && prepared == null) admissions.add(admission);
            if (prepared == null) CoreForeignArtifacts.validateArchive(module);
            var nativeArchive = prepared == null ? PackageNativeArchives.read(module) : prepared.getArchive();
            var boxed = prepared == null ? CoreBoxedForeignDeclarations.read(module, true) : prepared.getBoxedImports();
            if (boxed != null) boxedImports.add(boxed);
            var packageLink = prepared == null ? PackageScalarLinks.read(module) : prepared.getPackageLink();
            if (packageLink != null) packageProvenance(packageLink);
            var link = prepared == null ? CoreForeignArtifacts.linked(module) : prepared.getForeignLink();
            boolean archiveOnly = Objects.equals(module.get("schema"), 2L) || Objects.equals(module.get("schema"), 2), managedExport = admission != null;
            boolean managedImports = prepared == null ? packageLink == null && ManagedImportAdmission.read(module) != null : prepared.getImports() != null;
            if (module.get("staticForeignImportStubs") instanceof Map<?,?> callbackProof)
                for (var callback : ManagedCallbackMetadata.read(module, callbackProof))
                    require(nativeCallbacks.putIfAbsent(callback.signature().symbol(), callback) == null, "Duplicate native callback helper");
            if (archiveOnly && link == null && packageLink == null && !managedExport && !managedImports && CoreForeignArtifacts.hasRegistrationObligations(module)) CoreForeignArtifacts.requireExecutable(module);
            if (link != null) require(foreignLinks.putIfAbsent(new ModuleKey(link.unit(), link.module()), link) == null, "Duplicate linked foreign module: " + link.unit() + ":" + link.module());
            require(Objects.equals(module.get("ghc"), "9.14.1"), "This adapter requires GHC 9.14.1 exports");
            String unit = module.get("unit") instanceof String s ? s : null, name = module.get("module") instanceof String s ? s : null;
            boolean fragment = Objects.equals(unit, "dependency-closure") && Objects.equals(name, "THC.InterfaceClosure") && Objects.equals(module.get("boundary"), "actual-interface-unfoldings");
            if (unit != null && name != null && !fragment) {
                require(moduleKeys.add(new ModuleKey(unit, name)), "Duplicate GHC module: " + unit + ":" + name);
                if (Objects.equals(module.get("boundary"), "optimized-Core-after-Tidy-before-CorePrep")) completeModules.add(unit + ":" + name);
            }
            Object supplied = module.get("providedModules");
            if (supplied != null) {
                boolean valid = fragment && supplied instanceof List<?>;
                if (valid) for (Object item : (List<?>) supplied) if (!(item instanceof String s) || blank(s)) { valid = false; break; }
                require(valid, "Invalid provided-module interface closure");
                providedModules.addAll((List<String>) supplied);
            }
            for (String key : List.of("sourceFiles", "sourceSpans")) {
                Object records = module.get(key); if (records == null) continue;
                require(records instanceof List<?>, "Invalid " + key + " table"); var table = key.equals("sourceFiles") ? sourceFiles : sourceSpans;
                for (Object record : (List<?>) records) {
                    require(record instanceof Map<?,?>, "Invalid " + key + " record"); var source = (Map<String,Object>) record;
                    String id = text(source.get("id"), "Missing " + key + " identity"); var old = table.putIfAbsent(id, source);
                    require(old == null || old.equals(source), "Inconsistent " + key + " record: " + id);
                }
            }
            for (var binding : (List<Map<String,Object>>) module.get("bindings")) {
                String id = (String) binding.get("id"); require(bindings.putIfAbsent(id, binding) == null, "Duplicate binding: " + id);
                if (nativeArchive != null && nativeArchive.blocks(binding)) archiveBindings.put(id, nativeArchive.getDetail());
                else if (nativeArchive == null && archiveOnly && link == null && packageLink == null && !managedExport && !managedImports) archiveBindings.put(id, module.get("unit") + ":" + module.get("module"));
                if (unit != null && name != null && id.startsWith(unit + ":" + name + ".") && id.length() > (unit + ":" + name + ".").length()) bindingOrigins.put(id, Map.of("unit", unit, "module", name));
            }
            for (var constructor : (List<Map<String,Object>>) module.get("constructors")) {
                String id = (String) constructor.get("id"); var old = constructors.putIfAbsent(id, constructor);
                require(old == null || without(old, "type").equals(without(constructor, "type")), "Inconsistent constructor: " + id);
            }
        }
        public Map<String,Object> finish() {
            require(count != 0, "No Core modules supplied");
            var missing = new HashSet<>(providedModules); missing.removeAll(completeModules); missing.removeAll(availableModules);
            require(missing.isEmpty(), "Interface closure lacks its exact complete provided modules: " + missing);
            for (var entry : packageScalarLinks.entrySet()) {
                var proved = packageScalarProofs.get(entry.getKey());
                var required = new HashSet<String>();
                for (var signature : entry.getValue().getAbi()) required.add(signature.entry());
                require(Objects.equals(proved, required), "Package C ABI lacks complete typed import provenance: " + entry.getKey());
            }
            var result = new LinkedHashMap<String,Object>();
            result.put("schema", 1L); result.put("ghc", "9.14.1"); result.put("module", "THC.Bundle");
            result.put("bindings", new ArrayList<>(bindings.values())); result.put("constructors", new ArrayList<>(constructors.values())); result.put("bindingOrigins", bindingOrigins);
            result.put("foreignExceptionBridges", new ArrayList<>(exceptionBridges.values())); result.put("foreignExceptionBridgeUnit", exceptionBridgeUnit);
            result.put("archiveBindings", archiveBindings); result.put("managedRegistrations", new ArrayList<>(admissions)); result.put("foreignLinks", new ArrayList<>(foreignLinks.values()));
            result.put("boxedForeignDeclarations", List.copyOf(boxedImports));
            result.put("nativeCallbacks", Map.copyOf(nativeCallbacks));
            result.put("packageScalarLinks", new ArrayList<>(packageScalarLinks.values())); result.put("sourceFiles", new ArrayList<>(sourceFiles.values())); result.put("sourceSpans", new ArrayList<>(sourceSpans.values())); return result;
        }
    }
    public static Map<String,Object> reachable(Map<String,Object> module, String entry) { return reachable(module, entry, false); }
    public static Map<String,Object> reachable(Map<String,Object> module, String entry, boolean strictLink) { return reachable(module, List.of(entry), strictLink); }
    public static Map<String,Object> reachable(Map<String,Object> module, List<String> entries) { return reachable(module, entries, false); }
    public static Map<String,Object> reachable(Map<String,Object> module, List<String> entries, boolean strictLink) { return linkedBindings(module, entries, strictLink, null, null, null); }
    public static Map<String,Object> demanded(Map<String,Object> module, String entry, CoreDemandBindings demand, Supplier<Map<String,Object>> bridge, String runtimeUnit) { return linkedBindings(module, List.of(entry), true, demand, bridge, runtimeUnit); }
    private static Map<String,Object> linkedBindings(Map<String,Object> module, List<String> entries, boolean strictLink, CoreDemandBindings demand, Supplier<Map<String,Object>> bridge, String runtimeUnit) {
        if (module.containsKey("archiveBindings")) CoreForeignArtifacts.validateArchive(module); else CoreForeignArtifacts.requireExecutableInput(module);
        var bindings = (List<Map<String,Object>>) module.get("bindings"); var byId = new LinkedHashMap<String,Map<String,Object>>(); bindings.forEach(binding -> byId.put((String) binding.get("id"), binding));
        var constructorIds = new HashSet<String>(); if (module.get("constructors") instanceof List<?> constructors) for (Object raw : constructors) constructorIds.add((String) ((Map<?,?>) raw).get("id"));
        require(!entries.isEmpty() && new HashSet<>(entries).size() == entries.size(), "Missing or duplicate Core entries");
        var reachable = new LinkedHashSet<String>(); var pending = new ArrayDeque<String>();
        var missing = new LinkedHashMap<String,Set<String>>(); var missingConstructors = new LinkedHashMap<String,Set<String>>();
        class Linker {
            String owner = "";
            void reference(String id) {
                if (byId.containsKey(id)) { if (reachable.add(id)) pending.addLast(id); }
                else if (demand != null && demand.contains(id)) demand.cell(id);
                else if (strictLink) missing.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(owner);
            }
        }
        var linker = new Linker();
        var dependencies = new Dependencies(linker::reference,
            id -> { if (strictLink && !constructorIds.contains(id) && (demand == null || !demand.constructors(Map.of()).containsKey(id))) missingConstructors.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(linker.owner); },
            (id, foreign) -> byId.containsKey(id) || demand != null && (foreign ? demand.isDefined(id) : demand.contains(id)),
            () -> bridge != null ? bridge.get() : CoreForeignExceptionBridge.select(module),
            () -> runtimeUnit != null ? runtimeUnit : module.get("foreignExceptionBridgeUnit") instanceof String unit ? unit : null,
            () -> module.get("packageScalarLinks") instanceof List<?> links ? (List<PackageScalarLink>) links : List.of());
        var roots = new LinkedHashSet<>(entries);
        if (module.get("managedRegistrations") instanceof List<?> registrations) for (Object raw : registrations) for (var export : ((ManagedExportAdmission) raw).getExports()) roots.add(export.binder());
        for (String entry : roots) {
            var exact = byId.get(entry); var matches = new ArrayList<Map<String,Object>>();
            if (exact != null) matches.add(exact);
            else for (var binding : bindings) if (Objects.equals(binding.get("name"), entry)) matches.add(binding);
            require(matches.size() == 1, "Missing or ambiguous entry: " + entry); String root = (String) matches.getFirst().get("id"); if (reachable.add(root)) pending.addLast(root);
        }
        while (!pending.isEmpty()) {
            linker.owner = pending.removeFirst(); dependencies.binding(byId.get(linker.owner));
        }
        require(missing.isEmpty(), "Unlinked Core globals: " + missingDescription(missing));
        require(missingConstructors.isEmpty(), "Unlinked Core constructors: " + missingDescription(missingConstructors));
        var archived = module.get("archiveBindings") instanceof Map<?,?> map ? (Map<String,String>) map : Map.<String,String>of();
        for (String id : reachable) { String owner = archived.get(id); if (owner != null) throw new IllegalArgumentException("Unsupported foreign code/registration for " + owner + ": Core schema 2 is archive-only; native stubs, initializers, finalizers and callbacks are not linked"); }
        var result = without(module, "archiveBindings");
        var selected = new ArrayList<Map<String,Object>>();
        for (var binding : bindings) if (reachable.contains(binding.get("id"))) selected.add(binding);
        result.put("bindings", Collections.unmodifiableList(selected)); result.put("selectedForeignExceptionBridge", dependencies.exceptionBridge);
        result.put("selectedWeakFinalizer", dependencies.weakFinalizer); return result;
    }
    /** The same semantic edges govern ordinary linking and detached cache input. */
    private static final class Dependencies {
        private final Consumer<String> reference, constructor;
        private final BiPredicate<String,Boolean> defined;
        private final Supplier<Map<String,Object>> bridge;
        private final Supplier<String> runtimeUnit;
        private final Supplier<List<PackageScalarLink>> packageLinks;
        private Map<String,Object> exceptionBridge;
        private String weakFinalizer;
        Dependencies(Consumer<String> reference, Consumer<String> constructor, BiPredicate<String,Boolean> defined,
                Supplier<Map<String,Object>> bridge, Supplier<String> runtimeUnit, Supplier<List<PackageScalarLink>> packageLinks) {
            this.reference = reference; this.constructor = constructor; this.defined = defined;
            this.bridge = bridge; this.runtimeUnit = runtimeUnit; this.packageLinks = packageLinks;
        }
        void binding(Map<String,Object> binding) {
            var body = (List<Object>) binding.get("expr");
            if (body instanceof CoreBindingBody lazy) lazy.visitForLinking(value -> visit(value, Set.of())); else visit(body, Set.of());
        }
        private static Set<String> boundWith(Set<String> bound, Collection<String> extra) { var result = new LinkedHashSet<>(bound); result.addAll(extra); return result; }
        private void visit(List<Object> expression, Set<String> bound) {
                switch (Objects.toString(expression.getFirst(), "")) {
                    case "var" -> { String id = (String) expression.get(1); if (!bound.contains(id)) reference.accept(id); }
                    case "prim" -> {
                        String name = (String) expression.get(1);
                        if (CoreFileWait.named(name)) reference.accept(CoreFileWait.badFd);
                        String payload = CoreArithmeticExceptions.payload(name); if (payload != null) reference.accept(payload);
                        var compact = CompactOp.named(name); if (compact != null && compact.getAdds()) for (String failure : CompactOp.getFailures()) reference.accept(failure);
                        if (name.equals("atomically#")) reference.accept(STMOp.NESTED);
                        if (name.equals("mkWeak#")) {
                            // Runtime-entered Haskell actions use this same linked unit
                            // and its current original GHC finalizer handler CAF.
                            if (weakFinalizer == null) {
                                String unit = runtimeUnit.get();
                                require(unit != null && !blank(unit), "mkWeak# requires a selected THC runtime unit");
                                weakFinalizer = unit + ":THC.Internal.Weak.runWeakFinalizer";
                                require(defined.test(weakFinalizer, true),
                                    "mkWeak# requires the selected runtime finalizer ABI binding: " + weakFinalizer);
                            }
                            reference.accept(weakFinalizer);
                        }
                    }
                    case "lam" -> {
                        var body = (List<Object>) expression.get(2);
                        var ids = new ArrayList<String>();
                        for (var binding : (List<Map<String,Object>>) expression.get(1)) ids.add((String) binding.get("id"));
                        visit(body, boundWith(bound, ids));
                    }
                    case "app" -> {
                        var function = (List<Object>) expression.get(1); CoreExceptionPayload.validate(expression);
                        var metadata = CoreRepresentations.metadata(expression); boolean foreignDescriptor = metadata != null && metadata.get("foreignCall") instanceof Map<?,?>;
                        Object head = function.size() > 1 ? function.get(1) : null;
                        boolean isDefined = !function.isEmpty() && Objects.equals(function.getFirst(), "var") && head instanceof String id && (bound.contains(id) || defined.test(id, foreignDescriptor));
                        if (foreignDescriptor && CoreForeignExceptionBridge.executes(expression, isDefined, packageLinks.get())) {
                            if (exceptionBridge == null) exceptionBridge = bridge.get();
                            reference.accept((String) exceptionBridge.get("box")); reference.accept((String) exceptionBridge.get("project"));
                        }
                        if (CoreSignalForeign.named(metadata)) reference.accept(CoreSignalForeign.dispatcher);
                        boolean foreignHead = foreignDescriptor && !function.isEmpty() && Objects.equals(function.getFirst(), "var") && head instanceof String && !isDefined;
                        if (!foreignHead) visit(function, bound);
                        for (var argument : (List<List<Object>>) expression.get(2)) visit(argument, bound);
                    }
                    case "let" -> {
                        var group = (List<Map<String,Object>>) expression.get(2); var ids = new ArrayList<String>();
                        for (var binding : group) ids.add((String) binding.get("id"));
                        var rhsScope = Objects.equals(expression.get(1), true) ? boundWith(bound, ids) : bound;
                        for (var binding : group) visit((List<Object>) binding.get("expr"), rhsScope);
                        visit((List<Object>) expression.get(3), boundWith(bound, ids));
                    }
                    case "case" -> {
                        visit((List<Object>) expression.get(1), bound);
                        for (var alternative : (List<List<Object>>) expression.get(3)) {
                            if (Objects.equals(alternative.getFirst(), "data")) constructor.accept((String) alternative.get(1));
                            visit((List<Object>) alternative.get(3), boundWith(boundWith(bound, List.of((String) expression.get(2))), (List<String>) alternative.get(2)));
                        }
                    }
                    case "con" -> constructor.accept((String) expression.get(1));
                    default -> { }
                }
        }
    }
    private static String missingDescription(Map<String,Set<String>> missing) {
        var descriptions = new ArrayList<String>();
        for (var entry : missing.entrySet()) descriptions.add(entry.getKey() + " referenced by " + String.join(", ", entry.getValue()));
        return String.join(", ", descriptions);
    }
    private static boolean blank(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }

    /** Serialize a load request. Deferred file reads require process-local capabilities. */
    public static String request(List<String> paths, String entry) { return request(paths, entry, true, false, Main.defaultBackend(), true, false, null, null, false); }
    public static String request(List<String> paths, String entry, boolean instrument) { return request(paths, entry, instrument, false, Main.defaultBackend(), true, false, null, null, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported) { return request(paths, entry, instrument, diagnosticUnsupported, Main.defaultBackend(), true, false, null, null, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend) { return request(paths, entry, instrument, diagnosticUnsupported, backend, true, false, null, null, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, false, null, null, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled, boolean ioMain) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, ioMain, null, null, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled, boolean ioMain, String shutdownEntry) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, ioMain, shutdownEntry, null, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled, boolean ioMain, String shutdownEntry, Boolean asyncExceptions) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, ioMain, shutdownEntry, asyncExceptions, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported,
            String backend, boolean sourceNotesEnabled, boolean ioMain, String shutdownEntry, Boolean asyncExceptions,
            boolean verifyArtifacts) {
        require(shutdownEntry == null || ioMain && !blank(shutdownEntry) && !shutdownEntry.equals(entry), "Executable shutdown requires a distinct IO entry");
        var settings = new LinkedHashMap<String,Object>();
        settings.put("entry", entry); settings.put("instrument", instrument); settings.put("diagnosticUnsupported", diagnosticUnsupported); settings.put("backend", backend);
        settings.put("sourceNotesEnabled", sourceNotesEnabled); settings.put("verifyArtifacts", verifyArtifacts);
        for (String path : paths) if (path.startsWith("@")) { settings.put("strictLink", true); break; }
        if (ioMain) settings.put("ioMain", true); if (shutdownEntry != null) settings.put("shutdownEntry", shutdownEntry); if (asyncExceptions != null) settings.put("asyncExceptions", asyncExceptions);
        try { return requestDocument(paths, settings); }
        catch (Exception failure) { throw rethrow(failure); }
    }
    public static String managedExportRequest(List<String> paths, String backend, boolean instrument) { return managedExportRequest(paths, backend, instrument, false); }
    public static String managedExportRequest(List<String> paths, String backend, boolean instrument, boolean verifyArtifacts) {
        var settings = new LinkedHashMap<String,Object>(); settings.put("mode", "managed-exports"); settings.put("backend", backend); settings.put("instrument", instrument); settings.put("strictLink", true); settings.put("verifyArtifacts", verifyArtifacts);
        try { return requestDocument(paths, settings); } catch (Exception failure) { throw rethrow(failure); }
    }
    /** Internal decoded Core values, never a serialized runtime input protocol. */
    static TargetLayout visitDecodedModules(Map<String,Object> input, Consumer<Map<String,Object>> accept) {
        if (!(input.get("modules") instanceof List<?> modules)) throw new IllegalStateException("Expected decoded modules");
        for (Object module : modules) accept.accept(with((Map<String,Object>) module, "foreignExceptionBridgeUnit", input.get("foreignExceptionBridgeUnit")));
        return input.get("targetLayout") == null ? null : TargetLayout.fromDocument(input.get("targetLayout"));
    }
    private static boolean sameCapability(String supplied, String expected) { return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII)); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); } }
    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    public static CoreUnitDirectory unitDirectory(Map<String,Object> input) {
        require(Collections.disjoint(input.keySet(), Set.of("modules", "consumerModules", "detachedBindings", "targetLayout")),
                "Core runtime inputs must be declared CBD artifacts or a package manifest");
        require(input.get("verifyArtifacts") == null || input.get("verifyArtifacts") instanceof Boolean, "verifyArtifacts must be a Boolean");
        if (!input.containsKey("packageManifest")) {
            require(input.get("packageManifestSha256") == null && input.get("packageCapability") == null,
                    "Orphan package manifest identity");
            require(input.get("moduleFiles") instanceof List<?>, "Expected CBD module files or package manifest");
            return CoreUnitDirectory.read(Map.of("format", "thc-core-packages", "schema", 1L, "ghc", "9.14.1", "units", List.of()));
        }
        String manifest = text(input.get("packageManifest"), "Invalid package manifest path");
        boolean verify = Objects.equals(input.get("verifyArtifacts"), true);
        String expected = text(input.get("packageManifestSha256"), "Missing package manifest identity"), supplied = text(input.get("packageCapability"), "Missing package request capability");
        require(sameCapability(supplied, packageCapability(manifest, expected, verify)), "Invalid package request capability");
        try {
            byte[] bytes = Files.readAllBytes(Path.of(manifest)); if (verify || !expected.isEmpty()) require(sha256(bytes).equals(expected), "Core package manifest changed after request: " + manifest);
            if (!(Json.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?,?> document)) throw new IllegalStateException("Invalid Core package manifest");
            var directory = CoreUnitDirectory.read(document);
            require(!expected.isEmpty() || directory.getModules().stream().noneMatch(module -> module.interfaceSource() != null),
                    "Interface source requires a content-bound host request");
            require(Objects.equals(input.get("foreignExceptionBridgeUnit"), directory.getForeignExceptionBridgeUnit()), "Package bridge selection changed after request"); return directory;
        } catch (Exception failure) { throw rethrow(failure); }
    }
    /** Prepare from CBD through the normal lazy readers. Detach only the selected
     * in-memory values; no JSON Core, mapping or deferred body survives. */
    static Map<String,Object> selectedModules(Map<String,Object> input, String entry) {
        var directory = unitDirectory(input);
        try (var sources = directory.open(Boolean.TRUE.equals(input.get("verifyArtifacts")), false)) {
            var selected = new LinkedHashMap<CoreModuleAdmission,List<Map<String,Object>>>();
            var admissions = new LinkedHashMap<CoreUnitDirectory.ModuleRecord,CoreModuleAdmission>();
            var consumerAdmissions = new IdentityHashMap<Map<String,Object>,CoreModuleAdmission>();
            var consumers = new ArrayList<Map<String,Object>>();
            var consumerBindings = new LinkedHashMap<String,Map<String,Object>>();
            var consumerOwners = new LinkedHashMap<String,Map<String,Object>>();
            var moduleNames = new HashSet<String>();
            for (var module : directory.getModules()) moduleNames.add(module.unit() + ":" + module.name());
            visitUnitConsumers(with(input, "sourceNotesEnabled", false), sources, module -> {
                String unit = text(module.get("unit"), "Missing loose consumer unit"), name = text(module.get("module"), "Missing loose consumer module");
                boolean fragment = unit.equals("dependency-closure") && name.equals("THC.InterfaceClosure") && Objects.equals(module.get("boundary"), "actual-interface-unfoldings");
                require(fragment || moduleNames.add(unit + ":" + name), "Duplicate GHC module: " + unit + ":" + name);
                for (var binding : (List<Map<String,Object>>) module.get("bindings")) {
                    String id = (String) binding.get("id"); require(consumerBindings.putIfAbsent(id, binding) == null, "Duplicate binding: " + id);
                    consumerOwners.put(id, module);
                }
                consumers.add(module);
            });
            var visited = new LinkedHashSet<String>();
            var entries = new ArrayList<String>(); entries.add(entry);
            if (input.get("shutdownEntry") instanceof String shutdown) entries.add(shutdown);
            var pending = new ArrayDeque<String>(entries);
            class Selection {
                final Set<String> foreignUnits = new HashSet<>();
                final Map<String,PackageScalarLink> components = new HashMap<>();
                Map<String,Object> binding(String id) {
                    var consumer = consumerBindings.get(id);
                    if (consumer != null) {
                        require(!sources.containsSymbol(id), "Duplicate binding: " + id); return consumer;
                    }
                    return sources.binding(id);
                }
                CoreModuleAdmission admit(Map<String,Object> module) {
                    var existing = consumerAdmissions.get(module); if (existing != null) return existing;
                    var admitted = new CoreModuleAdmission(with(module, "bindings", List.of()), this::binding, components);
                    consumerAdmissions.put(module, admitted); selected.put(admitted, new ArrayList<>());
                    if (admitted.getExports() != null) for (var exported : admitted.getExports().getExports()) pending.add(exported.binder());
                    return admitted;
                }
                CoreModuleAdmission admit(CoreUnitDirectory.ModuleRecord module) {
                    var existing = admissions.get(module); if (existing != null) return existing;
                    sources.verifyModule(module);
                    var admitted = admit(sources.metadata(module)); admissions.put(module, admitted); return admitted;
                }
                void constructor(String id) {
                    for (var admission : selected.keySet()) for (var constructor : (List<Map<String,Object>>) admission.getModule().get("constructors"))
                        if (id.equals(constructor.get("id"))) return;
                    var owner = directory.owner(id); if (owner != null) admit(owner);
                    // The ordinary strict linker below reports a missing constructor.
                }
                Map<String,Object> bridge() {
                    var candidates = directory.getModules().stream().filter(module -> module.name().equals("THC.Internal.Exception") &&
                        (directory.getForeignExceptionBridgeUnit() == null || module.unit().equals(directory.getForeignExceptionBridgeUnit()))).toList();
                    var loose = consumers.stream().filter(module -> Objects.equals(module.get("module"), "THC.Internal.Exception") &&
                        (directory.getForeignExceptionBridgeUnit() == null || Objects.equals(module.get("unit"), directory.getForeignExceptionBridgeUnit()))).toList();
                    require(candidates.size() + loose.size() == 1, "Missing or ambiguous foreign exception bridge unit");
                    var proof = (loose.isEmpty() ? admit(candidates.getFirst()) : admit(loose.getFirst())).getBridge();
                    require(proof != null, "Foreign execution requires a genuine THC.Exception runtime bundle"); return proof;
                }
                List<PackageScalarLink> packageLinks() {
                    var links = new LinkedHashMap<String,PackageScalarLink>();
                    for (var admission : selected.keySet()) if (admission.getPackageLink() != null) {
                        var link = admission.getPackageLink().link(); var old = links.putIfAbsent(link.getUnit(), link);
                        require(old == null || old.same(link), "Conflicting package C component: " + link.getUnit());
                    }
                    return List.copyOf(links.values());
                }
                void provenance(CoreModuleAdmission admitted, Map<String,Object> binding) {
                    var units = new LinkedHashSet<String>();
                    if (admitted.getPackageLink() != null) units.add((String) admitted.getModule().get("unit"));
                    for (var call : PackageNativeArchive.calls(binding)) if (call.get("target") instanceof Map<?,?> target &&
                            "static".equals(target.get("kind")) && target.get("unit") instanceof String unit) units.add(unit);
                    for (String unit : units) if (foreignUnits.add(unit)) for (var module : directory.getModules())
                        if (module.unit().equals(unit) && module.packageScalarDeclarations()) admit(module);
                    var links = selected.keySet().stream().map(CoreModuleAdmission::getForeignLink).filter(Objects::nonNull).toList();
                    CoreCapiProvenance.supplement(Map.of("foreignLinks", links, "packageScalarLinks", packageLinks()), binding, (unit, name) -> {
                        var candidates = directory.getModules().stream().filter(module -> module.unit().equals(unit) && module.name().equals(name)).toList();
                        var loose = consumers.stream().filter(module -> Objects.equals(module.get("unit"), unit) && Objects.equals(module.get("module"), name)).toList();
                        require(candidates.size() + loose.size() == 1, "Missing or ambiguous CAPI declaration: " + unit + ":" + name);
                        return (loose.isEmpty() ? admit(candidates.getFirst()) : admit(loose.getFirst())).getForeignLink();
                    });
                }
            }
            var selection = new Selection();
            for (var module : consumers) {
                selection.admit(module);
                // Retain original complete-module provenance of interface fragments,
                // without selecting any unrelated binding body.
                if (module.get("providedModules") instanceof List<?> provided) for (var original : directory.getModules())
                    if (provided.contains(original.unit() + ":" + original.name())) {
                        selection.admit(original);
                    }
            }
            for (var module : directory.getModules()) if (module.registrationObligations()) selection.admit(module);
            var dependencies = new Dependencies(pending::add, selection::constructor,
                (id, foreign) -> consumerBindings.containsKey(id) || (foreign ? sources.containsSymbol(id) : directory.owner(id) != null),
                selection::bridge, directory::getForeignExceptionBridgeUnit, selection::packageLinks);
            while (!pending.isEmpty()) {
                String id = pending.removeFirst();
                if (!visited.add(id)) continue;
                var owner = directory.owner(id);
                var consumer = consumerOwners.get(id);
                require(consumer != null || owner != null, "Unlinked cached Core global: " + id);
                var admitted = consumer == null ? selection.admit(owner) : selection.admit(consumer);
                var binding = selection.binding(id);
                require(binding != null, "Missing cached Core binding: " + id);
                selected.get(admitted).add(binding);
                selection.provenance(admitted, binding);
                dependencies.binding(binding);
            }
            var modules = new ArrayList<Map<String,Object>>();
            var merger = new Merger();
            selected.forEach((admission, bindings) -> {
                merger.addSelected(admission, bindings);
                modules.add(admission.selected(bindings));
            });
            // Selection collects dependencies, not permission to execute them.
            // The ordinary linker still validates every selected original body.
            reachable(with(merger.finish(), "foreignExceptionBridgeUnit", directory.getForeignExceptionBridgeUnit()), entries, true);
            var result = without(input, "packageManifest", "packageManifestSha256", "packageCapability", "moduleFiles");
            result.put("modules", modules);
            if (directory.getForeignExceptionBridgeUnit() != null) result.put("foreignExceptionBridgeUnit", directory.getForeignExceptionBridgeUnit());
            if (sources.getTargetLayout() != null) result.put("targetLayout", sources.getTargetLayout().document());
            return (Map<String,Object>) detachedValue(result);
        }
    }
    private static Object detachedValue(Object value) {
        if (value instanceof Map<?,?> fields) {
            var result = new LinkedHashMap<String,Object>();
            fields.forEach((key, field) -> {
                // Optional lazy debug origins own a container reader. Cached
                // executable requests deliberately omit source-note resources.
                if (!key.equals("compactOrigin")) result.put((String) key, detachedValue(field));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> values) {
            var result = new ArrayList<Object>(values.size());
            for (Object field : values) result.add(detachedValue(field));
            return Collections.unmodifiableList(result);
        }
        return value;
    }
    /** Explicit CBD consumers share the same context-owned reader lifetime. */
    public static void visitUnitConsumers(Map<String,Object> input, CoreUnitDirectory.Sources sources,
            Consumer<Map<String,Object>> accept) {
        Object files = input.get("moduleFiles");
        if (files == null) return;
        require(files instanceof List<?>, "Invalid CBD module files");
        boolean verify = Boolean.TRUE.equals(input.get("verifyArtifacts"));
        for (Object raw : (List<?>) files) {
            require(raw instanceof Map<?,?>, "Invalid CBD artifact reference");
            var artifact = (Map<?,?>) raw;
            require(artifact.keySet().equals(Set.of("path", "sha256", "request", "capability")), "Invalid CBD artifact fields");
            String path = text(artifact.get("path"), "Missing CBD path");
            String hash = text(artifact.get("sha256"), "Missing CBD identity");
            String request = text(artifact.get("request"), "Missing CBD request identity");
            String capability = text(artifact.get("capability"), "Missing CBD capability");
            require(sameCapability(capability, packageCapability("CBD:" + path, hash + ":" + request, verify)), "Invalid CBD request capability");
            accept.accept(with(sources.consumer(Path.of(path), hash), "foreignExceptionBridgeUnit", input.get("foreignExceptionBridgeUnit")));
        }
    }
    private static String requestDocument(List<String> paths, Map<String,Object> settings) throws java.io.IOException {
        boolean verify = Boolean.TRUE.equals(settings.get("verifyArtifacts"));
        var document = new LinkedHashMap<>(settings);
        var files = new ArrayList<Object>();
        String manifest = null;
        for (String requested : paths) {
            if (requested.startsWith("@")) {
                require(manifest == null, "A Core request accepts at most one package manifest");
                manifest = Path.of(requested.substring(1)).toRealPath().toString();
            } else {
                String path = (verify ? Path.of(requested).toRealPath() : Path.of(requested).toAbsolutePath().normalize()).toString();
                String hash = verify ? sha256(Files.readAllBytes(Path.of(path))) : "";
                // Without a producer identity, neither mappings nor cached source
                // preparation may alias an earlier request at the same pathname.
                String request = verify ? "" : UUID.randomUUID().toString();
                files.add(Map.of("path", path, "sha256", hash, "request", request,
                        "capability", packageCapability("CBD:" + path, hash + ":" + request, verify)));
            }
        }
        if (!files.isEmpty() || manifest == null) document.put("moduleFiles", files);
        if (manifest != null) {
            byte[] bytes = Files.readAllBytes(Path.of(manifest));
            var directory = CoreUnitDirectory.read((Map<?,?>) Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            String hash = verify || directory.getModules().stream().anyMatch(module -> module.interfaceSource() != null) ? sha256(bytes) : "";
            document.put("packageManifest", manifest); document.put("packageManifestSha256", hash);
            document.put("packageCapability", packageCapability(manifest, hash, verify));
            document.put("foreignExceptionBridgeUnit", directory.getForeignExceptionBridgeUnit());
        }
        return Json.stringify(document);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
