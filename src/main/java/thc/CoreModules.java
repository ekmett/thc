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

    /** Retain linked definitions, never the complete raw package request. */
    public static final class Merger {
        private final Set<String> availableModules;
        private int count;
        private final List<ManagedExportAdmission> admissions = new ArrayList<>();
        private final Map<String,Map<String,Object>> bindings = new LinkedHashMap<>(), constructors = new LinkedHashMap<>(), sourceFiles = new LinkedHashMap<>(), sourceSpans = new LinkedHashMap<>();
        private final Map<String,Map<String,String>> bindingOrigins = new LinkedHashMap<>();
        private final Map<ModuleKey,ForeignBitcode> foreignLinks = new LinkedHashMap<>();
        private final Map<String,PackageScalarLink> packageScalarLinks = new LinkedHashMap<>();
        private final Map<String,Set<String>> packageScalarProofs = new LinkedHashMap<>();
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
        /** Declaration-only modules contribute typed ABI provenance, not roots. */
        public void addPackageProvenance(PackageScalarAdmission admission) { packageProvenance(admission); }
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
            var packageLink = prepared == null ? PackageScalarLinks.read(module) : prepared.getPackageLink();
            if (packageLink != null) packageProvenance(packageLink);
            var link = prepared == null ? CoreForeignArtifacts.linked(module) : prepared.getForeignLink();
            boolean archiveOnly = Objects.equals(module.get("schema"), 2L) || Objects.equals(module.get("schema"), 2), managedExport = admission != null;
            boolean managedImports = prepared == null ? packageLink == null && ManagedImportAdmission.read(module) != null : prepared.getImports() != null;
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
            result.put("packageScalarLinks", new ArrayList<>(packageScalarLinks.values())); result.put("sourceFiles", new ArrayList<>(sourceFiles.values())); result.put("sourceSpans", new ArrayList<>(sourceSpans.values())); return result;
        }
    }
    public static Map<String,Object> reachable(Map<String,Object> module, String entry) { return reachable(module, entry, false); }
    public static Map<String,Object> reachable(Map<String,Object> module, String entry, boolean strictLink) { return reachable(module, List.of(entry), strictLink); }
    public static Map<String,Object> reachable(Map<String,Object> module, List<String> entries) { return reachable(module, entries, false); }
    public static Map<String,Object> reachable(Map<String,Object> module, List<String> entries, boolean strictLink) { return linkedBindings(module, entries, strictLink, null, null); }
    public static Map<String,Object> demanded(Map<String,Object> module, String entry, CoreDemandBindings demand, Supplier<Map<String,Object>> bridge) { return linkedBindings(module, List.of(entry), true, demand, bridge); }
    private static Map<String,Object> linkedBindings(Map<String,Object> module, List<String> entries, boolean strictLink, CoreDemandBindings demand, Supplier<Map<String,Object>> bridge) {
        if (module.containsKey("archiveBindings")) CoreForeignArtifacts.validateArchive(module); else CoreForeignArtifacts.requireExecutableInput(module);
        var bindings = (List<Map<String,Object>>) module.get("bindings"); var byId = new LinkedHashMap<String,Map<String,Object>>(); bindings.forEach(binding -> byId.put((String) binding.get("id"), binding));
        var constructorIds = new HashSet<String>(); if (module.get("constructors") instanceof List<?> constructors) for (Object raw : constructors) constructorIds.add((String) ((Map<?,?>) raw).get("id"));
        require(!entries.isEmpty() && new HashSet<>(entries).size() == entries.size(), "Missing or duplicate Core entries");
        var reachable = new LinkedHashSet<String>(); var pending = new ArrayDeque<String>();
        var missing = new LinkedHashMap<String,Set<String>>(); var missingConstructors = new LinkedHashMap<String,Set<String>>();
        class Linker {
            String owner = ""; Map<String,Object> exceptionBridge;
            void constructor(String id) {
                if (strictLink && !constructorIds.contains(id) && (demand == null || !demand.constructors(Map.of()).containsKey(id))) missingConstructors.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(owner);
            }
            void reference(String id, Set<String> bound) {
                if (bound.contains(id)) return;
                if (byId.containsKey(id)) { if (reachable.add(id)) pending.addLast(id); }
                else if (demand != null && demand.contains(id)) demand.cell(id);
                else if (strictLink) missing.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(owner);
            }
            Set<String> boundWith(Set<String> bound, Collection<String> extra) { var result = new LinkedHashSet<>(bound); result.addAll(extra); return result; }
            void visit(List<Object> expression, Set<String> bound) {
                switch (Objects.toString(expression.getFirst(), "")) {
                    case "var" -> reference((String) expression.get(1), bound);
                    case "prim" -> {
                        String name = (String) expression.get(1);
                        if (CoreFileWait.named(name)) reference(CoreFileWait.badFd, Set.of());
                        String payload = CoreArithmeticExceptions.INSTANCE.payload(name); if (payload != null) reference(payload, Set.of());
                        var compact = CompactOp.named(name); if (compact != null && compact.getAdds()) for (String failure : CompactOp.getFailures()) reference(failure, Set.of());
                        if (name.equals("atomically#")) reference(STMOp.NESTED, Set.of());
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
                        boolean defined = !function.isEmpty() && Objects.equals(function.getFirst(), "var") && (head != null && bound.contains(head) || byId.containsKey(head) || head instanceof String id && demand != null && (foreignDescriptor ? demand.isDefined(id) : demand.contains(id)));
                        var packageLinks = module.get("packageScalarLinks") instanceof List<?> links ? (List<PackageScalarLink>) links : List.<PackageScalarLink>of();
                        if (CoreForeignExceptionBridge.executes(expression, defined, packageLinks)) {
                            if (exceptionBridge == null) exceptionBridge = bridge != null ? bridge.get() : CoreForeignExceptionBridge.select(module);
                            reference((String) exceptionBridge.get("box"), Set.of()); reference((String) exceptionBridge.get("project"), Set.of());
                        }
                        if (CoreSignalForeign.named(metadata)) reference(CoreSignalForeign.dispatcher, Set.of());
                        boolean foreignHead = foreignDescriptor && !function.isEmpty() && Objects.equals(function.getFirst(), "var") && head instanceof String && !defined;
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
                            if (Objects.equals(alternative.getFirst(), "data")) constructor((String) alternative.get(1));
                            visit((List<Object>) alternative.get(3), boundWith(boundWith(bound, List.of((String) expression.get(2))), (List<String>) alternative.get(2)));
                        }
                    }
                    case "con" -> constructor((String) expression.get(1));
                    default -> { }
                }
            }
        }
        var linker = new Linker(); var roots = new LinkedHashSet<>(entries);
        if (module.get("managedRegistrations") instanceof List<?> registrations) for (Object raw : registrations) for (var export : ((ManagedExportAdmission) raw).getExports()) roots.add(export.binder());
        for (String entry : roots) {
            var exact = byId.get(entry); var matches = new ArrayList<Map<String,Object>>();
            if (exact != null) matches.add(exact);
            else for (var binding : bindings) if (Objects.equals(binding.get("name"), entry)) matches.add(binding);
            require(matches.size() == 1, "Missing or ambiguous entry: " + entry); String root = (String) matches.getFirst().get("id"); if (reachable.add(root)) pending.addLast(root);
        }
        while (!pending.isEmpty()) {
            linker.owner = pending.removeFirst(); var body = (List<Object>) byId.get(linker.owner).get("expr");
            if (body instanceof CoreBindingBody lazy) lazy.visitForLinking(value -> linker.visit(value, Set.of())); else linker.visit(body, Set.of());
        }
        require(missing.isEmpty(), "Unlinked Core globals: " + missingDescription(missing));
        require(missingConstructors.isEmpty(), "Unlinked Core constructors: " + missingDescription(missingConstructors));
        var archived = module.get("archiveBindings") instanceof Map<?,?> map ? (Map<String,String>) map : Map.<String,String>of();
        for (String id : reachable) { String owner = archived.get(id); if (owner != null) throw new IllegalArgumentException("Unsupported foreign code/registration for " + owner + ": Core schema 2 is archive-only; native stubs, initializers, finalizers and callbacks are not linked"); }
        var result = without(module, "archiveBindings");
        var selected = new ArrayList<Map<String,Object>>();
        for (var binding : bindings) if (reachable.contains(binding.get("id"))) selected.add(binding);
        result.put("bindings", Collections.unmodifiableList(selected)); result.put("selectedForeignExceptionBridge", linker.exceptionBridge); return result;
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
    public static String request(List<String> paths, String entry) { return request(paths, entry, true, false, Main.defaultBackend(), true, false, null, null, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument) { return request(paths, entry, instrument, false, Main.defaultBackend(), true, false, null, null, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported) { return request(paths, entry, instrument, diagnosticUnsupported, Main.defaultBackend(), true, false, null, null, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend) { return request(paths, entry, instrument, diagnosticUnsupported, backend, true, false, null, null, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, false, null, null, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled, boolean ioMain) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, ioMain, null, null, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled, boolean ioMain, String shutdownEntry) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, ioMain, shutdownEntry, null, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled, boolean ioMain, String shutdownEntry, Boolean asyncExceptions) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, ioMain, shutdownEntry, asyncExceptions, false, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported, String backend, boolean sourceNotesEnabled, boolean ioMain, String shutdownEntry, Boolean asyncExceptions, boolean indexed) { return request(paths, entry, instrument, diagnosticUnsupported, backend, sourceNotesEnabled, ioMain, shutdownEntry, asyncExceptions, indexed, false); }
    public static String request(List<String> paths, String entry, boolean instrument, boolean diagnosticUnsupported,
            String backend, boolean sourceNotesEnabled, boolean ioMain, String shutdownEntry, Boolean asyncExceptions,
            boolean indexed, boolean verifyArtifacts) {
        require(shutdownEntry == null || ioMain && !blank(shutdownEntry) && !shutdownEntry.equals(entry), "Executable shutdown requires a distinct IO entry");
        var settings = new LinkedHashMap<String,Object>();
        settings.put("entry", entry); settings.put("instrument", instrument); settings.put("diagnosticUnsupported", diagnosticUnsupported); settings.put("backend", backend);
        settings.put("sourceNotesEnabled", sourceNotesEnabled); settings.put("verifyArtifacts", verifyArtifacts);
        for (String path : paths) if (path.startsWith("@")) { settings.put("strictLink", true); break; }
        if (ioMain) settings.put("ioMain", true); if (shutdownEntry != null) settings.put("shutdownEntry", shutdownEntry); if (asyncExceptions != null) settings.put("asyncExceptions", asyncExceptions);
        try { return indexed ? indexedRequestDocument(paths, settings) : requestDocument(paths, settings); }
        catch (Exception failure) { throw rethrow(failure); }
    }
    public static String managedExportRequest(List<String> paths, String backend, boolean instrument) { return managedExportRequest(paths, backend, instrument, false); }
    public static String managedExportRequest(List<String> paths, String backend, boolean instrument, boolean verifyArtifacts) {
        var settings = new LinkedHashMap<String,Object>(); settings.put("mode", "managed-exports"); settings.put("backend", backend); settings.put("instrument", instrument); settings.put("strictLink", true); settings.put("verifyArtifacts", verifyArtifacts);
        try { return requestDocument(paths, settings); } catch (Exception failure) { throw rethrow(failure); }
    }
    public static TargetLayout visitRequestModules(Map<String,Object> input, Consumer<Map<String,Object>> accept) { return visitRequestModules(input, (source, adapter) -> {}, accept); }
    public static TargetLayout visitRequestModules(Map<String,Object> input, BiConsumer<CoreJsonIndex,CoreJsonBindings> indexed, Consumer<Map<String,Object>> accept) {
        try { return visitRequestModulesChecked(input, indexed, accept); } catch (Exception failure) { throw rethrow(failure); }
    }
    private static TargetLayout visitRequestModulesChecked(Map<String,Object> input, BiConsumer<CoreJsonIndex,CoreJsonBindings> indexed, Consumer<Map<String,Object>> accept) throws java.io.IOException {
        require(input.get("verifyArtifacts") == null || input.get("verifyArtifacts") instanceof Boolean, "verifyArtifacts must be a Boolean");
        boolean verify = Objects.equals(input.get("verifyArtifacts"), true); Object files = input.get("indexedModuleFiles");
        if (files != null) {
            require(files instanceof List<?> list && !list.isEmpty() && input.get("modules") == null && input.get("consumerModules") == null && input.get("targetLayout") == null, "Indexed module request must not mix input protocols");
            require(input.get("packageManifest") != null || input.get("packageManifestSha256") == null && input.get("packageCapability") == null, "Orphan package manifest identity");
            var adapter = new CoreJsonBindings(!Objects.equals(input.get("sourceNotesEnabled"), false)); var opened = new ArrayList<CoreJsonIndex>();
            try {
                var layout = input.get("packageManifest") == null ? null : visitRequestModules(without(input, "indexedModuleFiles"), (source, projection) -> { opened.add(source); indexed.accept(source, projection); }, accept);
                var seenPaths = new HashSet<String>();
                for (Object raw : (List<?>) files) {
                    if (!(raw instanceof Map<?,?> file)) throw new IllegalStateException("Invalid indexed Core descriptor");
                    require(file.keySet().equals(Set.of("path", "sha256", "capability")), "Invalid indexed Core descriptor fields");
                    String path = text(file.get("path"), "Missing indexed Core path"); require(seenPaths.add(path), "Duplicate indexed Core path: " + path);
                    String sha = text(file.get("sha256"), "Missing indexed Core identity"), capability = text(file.get("capability"), "Missing indexed Core capability");
                    require((verify ? sha.matches("[0-9a-f]{64}") : sha.isEmpty()) && sameCapability(capability, indexedCapability(path, sha, verify)), "Invalid indexed Core capability");
                    CoreJsonIndex source = CoreJsonIndex.read(Path.of(path));
                    opened.add(source); if (verify) require(source.sha256().equals(sha), "Core JSON changed after request: " + path);
                    accept.accept(with(adapter.module(source.getRoot()), "foreignExceptionBridgeUnit", input.get("foreignExceptionBridgeUnit"))); indexed.accept(source, adapter);
                }
                // Immutable byte snapshots belong to parsed Engine roots, not a
                // single Context; closing one Context must not invalidate peers.
                return layout;
            } catch (Throwable failure) { opened.forEach(CoreJsonIndex::close); throw failure; }
        }
        Object manifest = input.get("packageManifest");
        if (manifest != null) {
            require(manifest instanceof String && input.get("modules") == null && input.get("targetLayout") == null, "Package request must not mix manifest and inline modules");
            String expected = text(input.get("packageManifestSha256"), "Missing package manifest identity"), supplied = text(input.get("packageCapability"), "Missing package request capability");
            require(sameCapability(supplied, packageCapability((String) manifest, expected, verify)), "Invalid package request capability");
            Object consumers = input.get("consumerModules");
            boolean validConsumers = consumers == null || consumers instanceof List<?>;
            if (validConsumers && consumers != null) for (Object value : (List<?>) consumers) if (!(value instanceof Map<?,?>)) { validConsumers = false; break; }
            require(validConsumers, "Invalid loose package consumers");
            var result = CorePackageManifest.visitRuntimeModules((String) manifest, expected, !Objects.equals(input.get("sourceNotesEnabled"), false), verify, indexed,
                    module -> accept.accept(with(module, "foreignExceptionBridgeUnit", input.get("foreignExceptionBridgeUnit"))));
            require(Objects.equals(input.get("foreignExceptionBridgeUnit"), result.getForeignExceptionBridgeUnit()), "Package bridge selection changed after request");
            if (consumers != null) for (var consumer : (List<Map<String,Object>>) consumers) accept.accept(with(consumer, "foreignExceptionBridgeUnit", result.getForeignExceptionBridgeUnit()));
            return result.getTargetLayout();
        }
        require(input.get("packageManifestSha256") == null && input.get("packageCapability") == null && input.get("consumerModules") == null, "Orphan package manifest identity");
        if (!(input.get("modules") instanceof List<?> modules)) throw new IllegalStateException("Expected modules array");
        for (Object module : modules) accept.accept(with((Map<String,Object>) module, "foreignExceptionBridgeUnit", input.get("foreignExceptionBridgeUnit")));
        return input.get("targetLayout") == null ? null : TargetLayout.fromDocument(input.get("targetLayout"));
    }
    private static boolean sameCapability(String supplied, String expected) { return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII)); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); } }
    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static String sha256(Path path) throws java.io.IOException {
        try (var stream = Files.newInputStream(path)) {
            var digest = digest(); byte[] buffer = new byte[8192];
            while (true) { int size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size); }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    private static String indexedCapability(String path, String sha, boolean verify) { return packageCapability("indexed-json:" + path, sha, verify); }
    public static CoreUnitDirectory unitDirectory(Map<String,Object> input) {
        if (!(input.get("packageManifest") instanceof String manifest)) return null;
        require(input.get("modules") == null && input.get("targetLayout") == null, "Package request must not mix input protocols");
        require(input.get("verifyArtifacts") == null || input.get("verifyArtifacts") instanceof Boolean, "verifyArtifacts must be a Boolean");
        boolean verify = Objects.equals(input.get("verifyArtifacts"), true);
        String expected = text(input.get("packageManifestSha256"), "Missing package manifest identity"), supplied = text(input.get("packageCapability"), "Missing package request capability");
        require(sameCapability(supplied, packageCapability(manifest, expected, verify)), "Invalid package request capability");
        try {
            byte[] bytes = Files.readAllBytes(Path.of(manifest)); if (verify) require(sha256(bytes).equals(expected), "Core package manifest changed after request: " + manifest);
            if (!(Json.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?,?> document)) throw new IllegalStateException("Invalid Core package manifest");
            var directory = CoreUnitDirectory.read(document); if (directory == null) return null;
            require(Objects.equals(input.get("foreignExceptionBridgeUnit"), directory.getForeignExceptionBridgeUnit()), "Package bridge selection changed after request"); return directory;
        } catch (Exception failure) { throw rethrow(failure); }
    }
    /** Store-time selection through the normal lazy readers. Serialize while the
     * readers are open; no path, capability, mapping or deferred body survives. */
    static String detachedRequest(Map<String,Object> input, String entry) {
        var directory = unitDirectory(input);
        if (directory == null) {
            require(input.containsKey("modules"), "Cached preparation requires inline modules or a package directory");
            return Json.stringify(input);
        }
        require(input.get("consumerModules") == null && input.get("indexedModuleFiles") == null,
            "Cached package selection requires a qualified entry in its package directory");
        try (var sources = directory.open(Boolean.TRUE.equals(input.get("verifyArtifacts")), false)) {
            var selected = new LinkedHashMap<CoreUnitDirectory.ModuleRecord,List<Map<String,Object>>>();
            var admissions = new LinkedHashMap<CoreUnitDirectory.ModuleRecord,CoreModuleAdmission>();
            var visited = new LinkedHashSet<String>();
            var pending = new ArrayDeque<String>(); pending.add(entry);
            while (!pending.isEmpty()) {
                String id = pending.removeFirst();
                if (!visited.add(id)) continue;
                var owner = directory.owner(id);
                require(owner != null, "Unlinked cached Core global: " + id);
                admissions.computeIfAbsent(owner, module -> new CoreModuleAdmission(sources.metadata(module), sources::binding));
                var binding = sources.binding(id);
                require(binding != null, "Missing cached Core binding: " + id);
                selected.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(binding);
                pending.addAll(CoreFreeVariables.coreFreeVariables((List<Object>) binding.get("expr")));
            }
            var modules = new ArrayList<Map<String,Object>>();
            var merger = new Merger();
            selected.forEach((owner, bindings) -> {
                var admission = admissions.get(owner);
                merger.addSelected(admission, bindings);
                modules.add(admission.selected(bindings));
            });
            // The ordinary linker still checks implicit dependencies and metadata;
            // unsupported/foreign code is not made admissible by detachment.
            reachable(merger.finish(), entry, true);
            var result = without(input, "packageManifest", "packageManifestSha256", "packageCapability");
            result.put("modules", modules);
            if (directory.getTargetLayout() != null) result.put("targetLayout", directory.getTargetLayout().document());
            return Json.stringify(detachedValue(result));
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
            return result;
        }
        if (value instanceof List<?> values) {
            var result = new ArrayList<Object>(values.size());
            for (Object field : values) result.add(detachedValue(field));
            return result;
        }
        return value;
    }
    /** Replay only explicit consumers; package definitions stay in their directory. */
    public static void visitUnitConsumers(Map<String,Object> input, BiConsumer<CoreJsonIndex,CoreJsonBindings> indexed, Consumer<Map<String,Object>> accept) {
        Object files = input.get("indexedModuleFiles"), consumers = input.get("consumerModules");
        require(files == null || consumers == null, "Mixed loose consumer protocols");
        var loose = without(input, "packageManifest", "packageManifestSha256", "packageCapability", "consumerModules");
        if (files != null) visitRequestModules(loose, indexed, accept);
        else if (consumers != null) {
            boolean validConsumers = consumers instanceof List<?>;
            if (validConsumers) for (Object value : (List<?>) consumers) if (!(value instanceof Map<?,?>)) { validConsumers = false; break; }
            require(validConsumers, "Invalid loose package consumers");
            visitRequestModules(with(loose, "modules", consumers), indexed, accept);
        }
    }
    private static Map<String,Object> manifestSettings(Map<String,Object> settings, CorePackageManifest.VisitResult identity, boolean verify) {
        var document = new LinkedHashMap<>(settings); document.put("packageManifest", identity.getManifestPath()); document.put("packageManifestSha256", identity.getManifestSha256());
        document.put("packageCapability", packageCapability(identity.getManifestPath(), identity.getManifestSha256(), verify)); document.put("foreignExceptionBridgeUnit", identity.getForeignExceptionBridgeUnit()); return document;
    }
    private static String indexedRequestDocument(List<String> paths, Map<String,Object> settings) throws java.io.IOException {
        boolean verify = Objects.equals(settings.get("verifyArtifacts"), true);
        var manifests = new ArrayList<String>();
        for (String path : paths) if (path.startsWith("@")) manifests.add(path);
        var loose = new ArrayList<String>();
        for (String path : paths) if (!path.startsWith("@")) loose.add(path);
        require(manifests.size() <= 1 && new HashSet<>(paths).size() == paths.size() && !paths.isEmpty(), "Indexed JSON requires distinct inputs and at most one package manifest");
        var seenPaths = new HashSet<String>(); var files = new ArrayList<Map<String,Object>>();
        for (String raw : loose) {
            String path = Path.of(raw).toRealPath().toString(); require(seenPaths.add(path), "Duplicate indexed Core path: " + path);
            String sha = verify ? sha256(Path.of(path)) : "";
            var file = new LinkedHashMap<String,Object>(); file.put("path", path); file.put("sha256", sha); file.put("capability", indexedCapability(path, sha, verify)); files.add(file);
        }
        CorePackageManifest.VisitResult identity = null;
        if (!manifests.isEmpty()) { identity = CorePackageManifest.indexedRequestIdentity(manifests.getFirst().substring(1), true, verify); if (identity == null) throw new IllegalStateException("Required value was null."); }
        var document = files.isEmpty() ? settings : with(settings, "indexedModuleFiles", files); if (identity != null) document = manifestSettings(document, identity, verify); return Json.stringify(document);
    }
    private static String requestDocument(List<String> paths, Map<String,Object> settings) throws java.io.IOException {
        boolean verify = Objects.equals(settings.get("verifyArtifacts"), true);
        var manifests = new ArrayList<String>();
        for (String path : paths) if (path.startsWith("@")) manifests.add(path);
        require(manifests.size() <= 1, "A Core request accepts at most one package manifest");
        String manifest = manifests.isEmpty() ? null : manifests.getFirst().substring(1);
        var options = new StringBuilder(); Json.appendObjectDocument(options, Json.stringify(settings));
        if (manifest != null) {
            var consumers = new ArrayList<String>(); for (String path : paths) if (!path.startsWith("@")) consumers.add(readText(path));
            var identity = CorePackageManifest.indexedRequestIdentity(manifest, false, verify);
            if (identity != null) return manifestDocument(settings, identity, verify, consumers);
            class Inline { StringBuilder text = new StringBuilder(); int count; }
            var inline = new Inline(); int limit = 2 * 1024 * 1024;
            var result = CorePackageManifest.visitModules(manifest, null, verify, (module, source) -> {
                var destination = inline.text;
                if (destination != null) {
                    if (destination.length() + source.length() > limit) inline.text = null;
                    else { if (inline.count++ != 0) destination.append(','); Json.appendObjectDocument(destination, source); }
                }
            });
            if (inline.text == null) return manifestDocument(settings, result, verify, consumers);
            var document = new StringBuilder().append(options, 0, options.length() - 1).append(",\"modules\":[").append(inline.text);
            for (String source : consumers) { if (inline.count++ != 0) document.append(','); Json.appendObjectDocument(document, source); }
            document.append(']'); if (result.getTargetLayout() != null) document.append(",\"targetLayout\":").append(Json.stringify(result.getTargetLayout().document()));
            if (result.getForeignExceptionBridgeUnit() != null) document.append(",\"foreignExceptionBridgeUnit\":").append(Json.stringify(result.getForeignExceptionBridgeUnit()));
            return document.append('}').toString();
        }
        var document = new StringBuilder().append(options, 0, options.length() - 1).append(",\"modules\":[");
        for (int index = 0; index < paths.size(); index++) { if (index != 0) document.append(','); Json.appendObjectDocument(document, readText(paths.get(index))); }
        return document.append("]}").toString();
    }
    private static String manifestDocument(Map<String,Object> settings, CorePackageManifest.VisitResult identity, boolean verify, List<String> consumers) {
        var document = manifestSettings(settings, identity, verify);
        if (!consumers.isEmpty()) {
            var modules = new ArrayList<Object>();
            for (String consumer : consumers) modules.add(Json.parse(consumer));
            document.put("consumerModules", Collections.unmodifiableList(modules));
        }
        return Json.stringify(document);
    }
    private static String readText(String path) throws java.io.IOException { return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
