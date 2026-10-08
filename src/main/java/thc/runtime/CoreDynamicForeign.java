// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import thc.PackageScalarSignature;
import static thc.runtime.RuntimeFault.fault;

/** A dynamic import's GHC calling convention belongs to the call, not its pointer. */
final class CoreDynamicForeign {
    private CoreDynamicForeign() {}
    static PackageScalarCall.Kind select(Map<?, ?> target) {
        return "dynamic".equals(target.get("kind")) ? PackageScalarCall.Kind.DYNAMIC
            : "createAdjustor".equals(target.get("symbol")) && target.get("unit") == null ? PackageScalarCall.Kind.CREATE_CALLBACK
            : "freeHaskellFunctionPtr".equals(target.get("symbol")) && "ghc-internal".equals(target.get("unit")) ? PackageScalarCall.Kind.FREE_CALLBACK : null;
    }
    static PackageScalarCall validate(Map<?, ?> metadata, Map<?, ?> descriptor, Map<?, ?> target,
            List<?> arguments, List<?> flags, Object output) {
        var kind = select(target);
        if (kind == null) return null;
        boolean dynamic = kind == PackageScalarCall.Kind.DYNAMIC;
        if (!(dynamic ? target.keySet().equals(Set.of("kind"))
                : target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) && "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction"))) ||
                !descriptor.keySet().equals(Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")) ||
                !CorePackageScalarForeign.number(descriptor.get("schema"), 1) || !"ccall".equals(descriptor.get("convention")) ||
                !(descriptor.get("safety") instanceof String safety) ||
                !(descriptor.get("argumentReps") instanceof List<?> declared) || declared.size() < 2)
            throw fault("Invalid dynamic foreign-call declaration");
        ForeignSafety.synchronous(safety);
        int count = declared.size();
        if (arguments.size() != count || flags.size() != count ||
                !CorePackageScalarForeign.number(descriptor.get("arity"), count) ||
                !CorePackageScalarForeign.number(descriptor.get("suppliedArity"), count))
            throw fault("Dynamic foreign-call arity differs");
        var reps = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            var proof = CoreRepresentations.parse(declared.get(i));
            String rep = i == count - 1 ? null : scalar(proof);
            if (i == 0 && !"AddrRep".equals(rep) || !Boolean.FALSE.equals(flags.get(i)) ||
                    !CorePackageScalarForeign.scalar(declared.get(i), rep, true) ||
                    !CorePackageScalarForeign.scalar(arguments.get(i), rep, false))
                throw fault("Dynamic foreign-call operand differs from its declared ABI");
            if (rep != null) reps.add(rep);
        }
        var result = CoreRepresentations.parse(descriptor.get("resultRep"));
        if (!result.isTuple() || result.getComponents() == null || result.getComponents().size() < 1 || result.getComponents().size() > 2)
            throw fault("Dynamic foreign-call result is not a State/scalar tuple");
        String rep = result.getComponents().size() == 1 ? "void" : scalar(result.getComponents().get(1));
        if (!CorePackageScalarForeign.result(descriptor.get("resultRep"), rep, true) ||
                !CorePackageScalarForeign.result(metadata.get("rep"), rep, false) || !CorePackageScalarForeign.result(output, rep, false))
            throw fault("Dynamic foreign-call result differs from its declared ABI");
        if (!dynamic && (!"unsafe".equals(safety) ||
                !(kind == PackageScalarCall.Kind.CREATE_CALLBACK ? reps.equals(List.of("AddrRep", "AddrRep", "AddrRep")) && "AddrRep".equals(rep)
                    : reps.equals(List.of("AddrRep")) && "void".equals(rep))))
            throw fault("Callback runtime operation differs from GHC's declared ABI");
        String symbol = dynamic ? "<dynamic>" : (String) target.get("symbol");
        return new PackageScalarCall(null, new PackageScalarSignature(symbol, symbol, List.copyOf(reps), rep, "ccall", safety), kind);
    }
    private static String scalar(CoreRepresentation proof) {
        if (proof.isAggregate() || proof.isVector() || proof.getPrimReps() == null || proof.getPrimReps().size() != 1)
            throw fault("Dynamic foreign call requires a scalar native ABI");
        String rep = proof.getPrimReps().getFirst();
        NativeCallbacks.nfiType(rep); // The native signature rejects unsupported carriers before a call can run.
        return rep;
    }
}
