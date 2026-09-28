// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import java.util.List;
import java.util.Map;

/** Original GHC identity of a globally named Core function or thunk. */
public record CoreFunctionIdentity(String bindingId, String unitId, String moduleName, String occurrence) {
    public static CoreFunctionIdentity from(Map<String, ?> module, Map<String, ?> binding) {
        if (!(binding.get("id") instanceof String id)) return null;
        Object ownerValue = module.get("bindingOrigins") instanceof Map<?, ?> origins ? origins.get(id) : null;
        Map<?, ?> owner = ownerValue instanceof Map<?, ?> map ? map : Map.of();
        Object unitValue = owner.get("unit"), moduleValue = owner.get("module");
        if (unitValue == null) unitValue = module.get("unit");
        if (moduleValue == null) moduleValue = module.get("module");
        if (!(unitValue instanceof String unit) || !(moduleValue instanceof String name)) return null;
        String prefix = unit + ":" + name + ".";
        if (!id.startsWith(prefix) || id.length() == prefix.length()) return null;
        return new CoreFunctionIdentity(id, unit, name, id.substring(prefix.length()));
    }

    /** Assign only the target created for this binding, never an aliased target. */
    public static void install(Map<String, ?> module, Map<String, ?> binding, Object value,
                               Map<String, CoreApplicationCertificates.Arity> arities) {
        var identity = from(module, binding);
        if (identity == null || !(binding.get("expr") instanceof List<?> rhs)) return;
        Object head = rhs.isEmpty() ? null : rhs.getFirst();
        String applicationHead = "app".equals(head) && rhs.size() > 1 && rhs.get(1) instanceof List<?> application &&
            application.size() > 1 && "var".equals(application.getFirst()) && application.get(1) instanceof String id ? id : null;
        boolean certifiedApplication = CoreApplicationCertificates.eagerApplication(rhs,
            applicationHead == null ? null : arities.get(applicationHead));
        RootCallTarget target;
        if ("lam".equals(head) && value instanceof Closure closure) target = closure.target;
        else if (Boolean.TRUE.equals(binding.get("lifted")) && !certifiedApplication &&
                !java.util.Arrays.asList("var", "lit", "lam", "con", "prim", "void").contains(head) && value instanceof Thunk thunk) {
            target = thunk.getTarget();
            if (target == null) return;
        } else return;
        if (target.getRootNode() instanceof GuestRoot root) root.configureCoreIdentity(identity);
    }
}
