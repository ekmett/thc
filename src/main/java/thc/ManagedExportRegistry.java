// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.*;
import java.util.function.Supplier;
import thc.runtime.*;

/** One load transaction owns a program. No guest evaluation runs under this monitor. */
public final class ManagedExportRegistry {
    private final Language.State owner;
    private final Language language;
    private volatile boolean closed;
    private volatile Map<String,?> units = Map.of();
    private boolean loading, loaded;
    private final ManagedExportNamespace scope;
    public ManagedExportRegistry(Language.State owner, Language language) {
        this.owner = owner; this.language = language;
        scope = new ManagedExportNamespace(this, "THC managed exports", () -> units);
    }
    public ManagedExportNamespace getScope() { return scope; }
    public void checkOwner() {
        if (closed || Language.currentState(null) != owner) throw new RuntimeFault("Managed export belongs to another or closed THC context");
    }
    @TruffleBoundary @SuppressWarnings("unchecked")
    public ManagedExportNamespace load(ManagedExportPlan plan) {
        return load(() -> {
            for (var link : (List<PackageScalarLink>) plan.getLinked().get("packageScalarLinks")) owner.getPackageCbits().declare(link);
            ExecutableProgram program = switch (plan.getBackend()) {
                case "ast" -> new Program(language, plan.getLinked(), false, false);
                case "bytecode" -> new BytecodeProgram(language, plan.getLinked(), true);
                default -> throw new IllegalStateException("Invalid managed backend");
            };
            return new Loaded(program, plan.getExports(), (List<ManagedExportAdmission>) plan.getLinked().get("managedRegistrations"),
                (List<PackageScalarLink>) plan.getLinked().get("packageScalarLinks"));
        });
    }
    @TruffleBoundary
    public ManagedExportNamespace load(Map<String,Object> input, CoreUnitDirectory directory, String backend) {
        return load(() -> {
            if (directory.getTargetLayout() != null && directory.getTargetLayout().getWordBytes() * 8 != 64) throw new IllegalArgumentException("Managed export target word width differs");
            var program = new CoreUnitProgram(language, directory, input, null, backend, backend.equals("bytecode"), owner);
            try {
                var registrations = program.registerStartup();
                var exports = new ArrayList<ManagedExportSignature>();
                for (var admission : registrations) exports.addAll(admission.getExports());
                if (exports.isEmpty()) throw new IllegalArgumentException("No verified static foreign exports supplied");
                return new Loaded(program, ManagedExportPlan.checked(exports, program::signatureBindings), registrations, List.of());
            } catch (Throwable failure) { program.close(); throw failure; }
        });
    }
    private record Loaded(ExecutableProgram program, List<ManagedExportSignature> exports, List<ManagedExportAdmission> registrations,
            List<PackageScalarLink> links) {}
    private ManagedExportNamespace load(Supplier<Loaded> prepare) {
        checkOwner();
        synchronized (this) {
            if (loading || loaded) throw new RuntimeFault("This THC context already has a managed export bundle");
            loading = true;
        }
        Loaded prepared = null;
        try {
            var bundle = prepare.get(); prepared = bundle;
            var program = bundle.program();
            owner.getForeignRoots().register(program, language, bundle.registrations(), bundle.exports());
            var grouped = new LinkedHashMap<String,Map<String,List<ManagedExportSignature>>>();
            for (var signature : bundle.exports()) grouped.computeIfAbsent(signature.unit(), ignored -> new LinkedHashMap<>()).computeIfAbsent(signature.module(), ignored -> new ArrayList<>()).add(signature);
            var namespace = new LinkedHashMap<String,ManagedExportNamespace>();
            for (var unit : grouped.entrySet()) {
                var modules = new LinkedHashMap<String,ManagedExportNamespace>();
                for (var module : unit.getValue().entrySet()) {
                    var symbols = new LinkedHashMap<String,ManagedExportValue>();
                    for (var signature : module.getValue()) symbols.put(signature.symbol(), new ManagedExportValue(this, owner, language, program, signature));
                    modules.put(module.getKey(), new ManagedExportNamespace(this, unit.getKey() + ":" + module.getKey(), () -> symbols));
                }
                namespace.put(unit.getKey(), new ManagedExportNamespace(this, unit.getKey(), () -> modules));
            }
            if (program instanceof CoreUnitProgram unitProgram) unitProgram.linkStartup();
            for (var link : bundle.links()) owner.getPackageCbits().link(link);
            synchronized (this) {
                checkOwner();
                if (program instanceof CoreUnitProgram unitProgram) owner.getCoreUnitPrograms().add(unitProgram);
                units = namespace; loaded = true;
            }
            return scope;
        } catch (Throwable failure) {
            if (prepared != null) {
                owner.getForeignRoots().release(prepared.program());
                if (prepared.program() instanceof CoreUnitProgram unitProgram) unitProgram.close();
            }
            throw failure;
        } finally { synchronized (this) { loading = false; } }
    }
    public synchronized void close() { closed = true; units = Map.of(); }
}
