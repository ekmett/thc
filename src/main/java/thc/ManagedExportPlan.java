// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.function.Function;
import thc.runtime.*;

/** Contains immutable code/metadata only. Programs and CAFs are created in State at execution. */
public final class ManagedExportPlan {
    private final Map<String,Object> linked;
    private final List<ManagedExportSignature> exports;
    private final String backend;
    public ManagedExportPlan(Map<String,Object> linked, List<ManagedExportSignature> exports, String backend) { this.linked = linked; this.exports = exports; this.backend = backend; }
    public Map<String,Object> getLinked() { return linked; }
    public List<ManagedExportSignature> getExports() { return exports; }
    public String getBackend() { return backend; }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    public static String backend(Map<String,Object> input) {
        var keys = Set.of("mode", "modules", "moduleFiles", "backend", "instrument", "sourceNotesEnabled", "strictLink", "targetLayout", "verifyArtifacts", "packageManifest", "packageManifestSha256", "packageCapability", "foreignExceptionBridgeUnit");
        require(keys.containsAll(input.keySet()) && Objects.equals(input.get("mode"), "managed-exports") && Objects.equals(input.get("strictLink"), true), "Managed export loading requires its explicit strict request");
        require(input.get("verifyArtifacts") == null || input.get("verifyArtifacts") instanceof Boolean, "verifyArtifacts must be a Boolean");
        String backend = input.get("backend") instanceof String text ? text : Main.defaultBackend();
        require(backend.equals("ast") || backend.equals("bytecode"), "Unknown THC backend: " + backend); return backend;
    }
    @SuppressWarnings("unchecked")
    public static ManagedExportPlan read(Map<String,Object> input) {
        String backend = backend(input);
        var merger = new CoreModules.Merger(); var admissions = new ArrayList<ManagedExportAdmission>();
        var layout = CoreModules.visitDecodedModules(input, original -> {
            var module = (Map<String,Object>) Json.immutable(original);
            require(!module.containsKey("foreignLink"), "Native linked products are not managed export registration");
            var admission = Objects.equals(module.get("schema"), 2L) ? ManagedExportAdmission.read(module) : null;
            if (admission != null) admissions.add(admission);
            merger.add(module, admission);
        });
        var exports = new ArrayList<ManagedExportSignature>();
        for (var admission : admissions) exports.addAll(admission.getExports());
        require(!exports.isEmpty(), "No verified static foreign exports supplied");
        require(layout == null || layout.getWordBytes() * 8 == 64, "Managed export target word width differs");
        var merged = merger.finish();
        var entries = new LinkedHashSet<String>();
        for (var export : exports) entries.add(export.binder());
        var linked = new LinkedHashMap<>(CoreModules.reachable(merged, new ArrayList<>(entries), true));
        linked.put("instrument", !Objects.equals(input.get("instrument"), false)); linked.put("diagnosticUnsupported", false);
        linked.put("sourceNotesEnabled", !Objects.equals(input.get("sourceNotesEnabled"), false)); if (layout != null) linked.put("targetLayout", layout);
        var bindings = (List<Map<String,Object>>) linked.get("bindings");
        return new ManagedExportPlan(linked, checked(exports, ignored -> bindings), backend);
    }
    /** Check only the exported binder's actual signature/alias spine. The
     * declaration never supplies a guessed Core calling convention. */
    @SuppressWarnings("unchecked")
    public static List<ManagedExportSignature> checked(List<ManagedExportSignature> exports, Function<String,List<Map<String,Object>>> definitions) {
        var checked = new ArrayList<ManagedExportSignature>();
        for (var export : exports) {
            var bindings = definitions.apply(export.binder());
            var matching = new ArrayList<Map<String,Object>>();
            for (var binding : bindings) if (Objects.equals(binding.get("id"), export.binder())) matching.add(binding);
            if (matching.isEmpty()) throw new NoSuchElementException("Collection contains no element matching the predicate.");
            if (matching.size() != 1) throw new IllegalArgumentException("Collection contains more than one matching element.");
            var expression = (List<Object>) matching.getFirst().get("expr");
            var signature = CoreRepresentations.knownFunctionSignature(expression, bindings);
            List<CoreRepresentation> inputs; CoreRepresentation result;
            if (signature != null) { inputs = signature.getInputs(); result = signature.getResult(); }
            else if (export.arguments().isEmpty() && !export.io()) { inputs = List.of(); result = CoreRepresentations.expression(expression); }
            else throw new IllegalStateException("Managed export lacks a known Core function signature: " + export.binder());
            require(inputs.size() == export.arguments().size() + (export.io() ? 1 : 0), "Managed export Core input count differs");
            boolean boxedArguments = true;
            for (var argument : inputs.subList(0, export.arguments().size())) {
                if (!boxed(argument)) { boxedArguments = false; break; }
            }
            require(boxedArguments, "Managed export arguments must retain boxed scalar representation");
            if (export.io()) {
                var state = inputs.getLast(); var fields = result.getComponents();
                require(state.getKind() == CoreKind.VOID && Objects.equals(state.getPrimReps(), List.of()) && !state.isAggregate() && result.isTuple() && fields != null && fields.size() == 2 && fields.getFirst().getKind() == CoreKind.VOID && Objects.equals(fields.getFirst().getPrimReps(), List.of()) && !fields.getFirst().isAggregate() && boxed(fields.get(1)) && Objects.equals(result.getPrimReps(), List.of("BoxedRep (Just Lifted)")), "Managed IO export requires its exact state/boxed-result tuple");
                TupleShape.Companion.validate(result);
                checked.add(new ManagedExportSignature(export.unit(), export.module(), export.symbol(), export.binder(), export.arguments(), export.result(), export.io(), export.wordBits(), result));
            } else { require(boxed(result), "Managed export result must retain boxed scalar representation"); checked.add(export); }
        }
        return checked;
    }
    private static boolean boxed(CoreRepresentation proof) { return !proof.isAggregate() && !proof.isVector() && Objects.equals(proof.getPrimReps(), List.of("BoxedRep (Just Lifted)")) && (proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.OBJECT); }
}
