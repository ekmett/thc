// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import thc.runtime.CoreForeignExceptionBridge;
import thc.runtime.TargetLayout;

/** Context-owned selected records over a shared immutable file lease.
 * Construction and cold global references never open the container. */
public final class CoreCompactModule implements AutoCloseable {
    private final CoreUnitDirectory.ModuleRecord module;
    private final TargetLayout targetLayout;
    private final boolean verifyArtifacts;
    private final CoreCompactFile file;
    private final CoreUnitDirectory.Artifact artifact;
    private final CoreCompactRecords records;
    private Map<String,Object> metadata;
    private final Map<Long,Map<String,Object>> selected = new HashMap<>();
    private boolean verified;
    public CoreCompactModule(CoreUnitDirectory.ModuleRecord module, TargetLayout targetLayout, boolean verifyArtifacts) {
        this(module, targetLayout, verifyArtifacts, new HashMap<>());
    }
    CoreCompactModule(CoreUnitDirectory.ModuleRecord module, TargetLayout targetLayout, boolean verifyArtifacts, Map<String,String> blobs) {
        this(module, targetLayout, verifyArtifacts, blobs, module.artifact());
    }
    CoreCompactModule(CoreUnitDirectory.ModuleRecord module, TargetLayout targetLayout, boolean verifyArtifacts,
            Map<String,String> blobs, CoreUnitDirectory.Artifact artifact) {
        this.module = module; this.targetLayout = targetLayout; this.verifyArtifacts = verifyArtifacts;
        this.artifact = artifact;
        file = new CoreCompactFile(artifact.path(), artifact.sha256(), verifyArtifacts);
        records = new CoreCompactRecords(file, artifact.sha256(), blobs);
    }
    public CoreCompactFile.Counters getCounters() { return file.getCounters(); }
    public Map<String,Object> metadata() {
        if (metadata != null) return metadata;
        try {
            var header = file.header();
            require(header.getContainsDelimitedControl() == module.containsDelimitedControl() &&
                    header.getRegistrationObligations() == module.registrationObligations() && header.getMainAlias() == module.mainAlias() &&
                    header.getPackageScalarDeclarations() == module.packageScalarDeclarations(),
                    "Compact Core summaries differ from package directory: " + module.getPrefix());
            var facts = records.header();
            require(Objects.equals(facts.get("ghc"), "9.14.1") && Objects.equals(facts.get("unit"), module.unit()) &&
                    Objects.equals(facts.get("module"), module.name()) && Objects.equals(facts.get("boundary"), "optimized-Core-after-Tidy-before-CorePrep"),
                    "Compact Core identity differs from package directory: " + module.getPrefix());
            if (facts.get("targetLayout") != null) require(Objects.equals(TargetLayout.fromDocument(facts.get("targetLayout")), targetLayout),
                    "Compact Core target layout differs from package directory");
            var result = new LinkedHashMap<>(facts);
            result.remove("targetLayout"); result.put("bindings", List.of());
            synchronized (getCounters()) { getCounters().decodedModules++; }
            return metadata = result;
        } catch (Exception failure) { return rethrow(failure); }
    }
    public boolean containsSymbol(String id) {
        try { return file.lookup(id) != null; } catch (Exception failure) { return rethrow(failure); }
    }
    public Map<String,Object> binding(String id) {
        try { Long offset = file.lookup(id); return offset == null ? null : bindingAt(offset); }
        catch (Exception failure) { return rethrow(failure); }
    }
    private Map<String,Object> bindingAt(long offset) {
        return selected.computeIfAbsent(offset, ignored -> decodeBinding(records, offset));
    }
    private Map<String,Object> decodeBinding(CoreCompactRecords decoder, long offset) {
        var binding = decoder.binding(offset);
        String id = (String) binding.get("id");
        require(id.startsWith(module.getPrefix()) || module.mainAlias() && id.equals(CoreUnitDirectory.MAIN_ALIAS),
                "Compact Core binding has a different module owner: " + id);
        synchronized (getCounters()) { getCounters().decodedBindings++; }
        return binding;
    }
    public void verify() {
        if (!verifyArtifacts || verified) return;
        try {
            var facts = metadata();
            var offsets = new ArrayList<Long>();
            // Check every cold record even when its module has no foreign declarations.
            // Each decode has a short-lived shape dictionary; complete verification never retains all bodies.
            file.verifyBindingOffsets(offset -> {
                decodeBinding(new CoreCompactRecords(file, artifact.sha256()), offset);
                offsets.add(offset);
            });
            // Existing admissions see the complete inventory and preserve occurrence multiplicity.
            var bindings = new AbstractList<Map<String,Object>>() {
                @Override public int size() { return offsets.size(); }
                @Override public Map<String,Object> get(int index) {
                    return decodeBinding(new CoreCompactRecords(file, artifact.sha256()), offsets.get(index));
                }
            };
            var original = new LinkedHashMap<>(facts); original.put("bindings", bindings);
            CoreForeignArtifacts.INSTANCE.validateArchive(original, true);
            CoreModules.admission(original, null);
            CoreForeignExceptionBridge.read(original);
            verified = true;
        } catch (Throwable failure) { rethrow(failure); }
    }
    @Override public void close() {
        selected.clear(); metadata = null;
        try { file.close(); } catch (Exception failure) { rethrow(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
