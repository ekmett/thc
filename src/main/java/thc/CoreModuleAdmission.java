// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.function.Function;
import thc.runtime.CoreForeignExceptionBridge;
import thc.runtime.CoreBoxedForeignDeclarations;

/** Original module metadata is admitted once. A selected binding is not a
 * replacement for the original module's complete foreign-call inventory. */
public final class CoreModuleAdmission {
    private final Map<String,Object> module, bridge;
    private final ManagedExportAdmission exports;
    private final PackageNativeArchive archive;
    private final PackageScalarAdmission packageLink;
    private final ForeignBitcode foreignLink;
    private final ManagedImportAdmission imports;
    private final CoreBoxedForeignDeclarations boxedImports;
    private final List<Map<Object,Integer>> inventories = new ArrayList<>();
    public CoreModuleAdmission(Map<String,Object> module, Function<String,Map<String,Object>> binding) {
        this(module, binding, new HashMap<>());
    }
    public CoreModuleAdmission(Map<String,Object> module, Function<String,Map<String,Object>> binding,
            Map<String,PackageScalarLink> components) {
        this.module = module;
        exports = CoreModules.admission(module, binding::apply);
        CoreForeignArtifacts.validateArchive(module, false);
        archive = PackageNativeArchives.read(module, false);
        boxedImports = CoreBoxedForeignDeclarations.read(module, false);
        var admitted = PackageScalarLinks.read(module, true, false);
        if (admitted == null) packageLink = null;
        else {
            var link = admitted.link();
            var previous = components.putIfAbsent(link.getUnit(), link);
            if (previous != null && !previous.same(link))
                throw new IllegalArgumentException("Conflicting package C component: " + link.getUnit());
            packageLink = previous == null ? admitted : new PackageScalarAdmission(previous, admitted.proved());
        }
        foreignLink = CoreForeignArtifacts.linked(module, false);
        imports = packageLink == null ? ManagedImportAdmission.read(module, false) : null;
        if (module.containsKey("foreignExceptionBridge")) {
            String prefix = module.get("unit") + ":THC.Internal.Exception.";
            var selected = new LinkedHashMap<>(module);
            selected.put("bindings", List.of(binding.apply(prefix + "boxForeign"), binding.apply(prefix + "projectForeign")));
            bridge = CoreForeignExceptionBridge.read(selected);
        } else bridge = null;
        for (String key : List.of("staticForeignImports", "staticForeignImportStubs")) {
            if (module.get(key) instanceof Map<?,?> proof && Objects.equals(proof.get("status"), "verified")) {
                if (!(proof.get("expectedCalls") instanceof List<?> expected)) throw new IllegalStateException("Missing original Core foreign-call inventory");
                inventories.add(counts(expected));
            }
        }
    }
    public Map<String,Object> getModule() { return module; }
    public Map<String,Object> getBridge() { return bridge; }
    public ManagedExportAdmission getExports() { return exports; }
    public PackageNativeArchive getArchive() { return archive; }
    public PackageScalarAdmission getPackageLink() { return packageLink; }
    public ForeignBitcode getForeignLink() { return foreignLink; }
    public ManagedImportAdmission getImports() { return imports; }
    public CoreBoxedForeignDeclarations getBoxedImports() { return boxedImports; }
    private static Map<Object,Integer> counts(List<?> calls) { var counts = new LinkedHashMap<Object,Integer>(); for (Object call : calls) counts.merge(call, 1, Integer::sum); return counts; }
    public Map<String,Object> selected(List<Map<String,Object>> bindings) {
        var actual = CoreCallInventory.mapCalls(bindings); var counts = counts(actual);
        for (var inventory : inventories) for (var count : counts.entrySet()) {
            if (count.getValue() > inventory.getOrDefault(count.getKey(), 0)) throw new IllegalArgumentException("Demanded Core foreign call is absent from original inventory");
        }
        if (foreignLink != null) CoreForeignArtifacts.validateCalls(bindings, foreignLink, false);
        if (imports != null) imports.validateCalls(actual);
        var selected = new LinkedHashMap<>(module); selected.put("bindings", bindings); return selected;
    }
}
