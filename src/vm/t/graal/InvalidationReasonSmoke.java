// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotNmethod;

// Every invalidation reason exported by LabsJDK must have a usable description.
public final class InvalidationReasonSmoke {
    private static final String PREFIX = "nmethod::InvalidationReason::";

    private static void checkDescription(String name, String description) {
        if (description == null || description.isBlank() || description.equalsIgnoreCase("unknown")) {
            throw new AssertionError(name + " has no description: " + description);
        }
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        Map<String, Long> reasons = new TreeMap<>();
        HotSpotJVMCIRuntime.runtime().getConfigStore().getConstants().forEach((name, value) -> {
            if (name.startsWith(PREFIX)) reasons.put(name.substring(PREFIX.length()), value);
        });
        for (String name : new String[] {"NOT_INVALIDATED", "UNLOADING", "UNLOADING_COLD"}) {
            if (!reasons.containsKey(name)) throw new AssertionError("Missing VM constant: " + name);
        }

        // Description queries must also work before code is installed in a mirror.
        var constructor = HotSpotNmethod.class.getDeclaredConstructor(
            Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethodImpl"),
            String.class, boolean.class, boolean.class, long.class);
        constructor.setAccessible(true);
        HotSpotNmethod mirror = constructor.newInstance(null, "not-installed", false, false, 0L);
        if (mirror.getInvalidationReason() != Math.toIntExact(reasons.get("NOT_INVALIDATED"))) {
            throw new AssertionError("New mirror does not have the VM's NOT_INVALIDATED reason");
        }
        System.out.println("Checking new mirror: NOT_INVALIDATED");
        String initialDescription = mirror.getInvalidationReasonDescription();
        checkDescription("NOT_INVALIDATED", initialDescription);

        Class<?> bridgeClass = Class.forName("jdk.vm.ci.hotspot.CompilerToVM");
        Method accessor = bridgeClass.getDeclaredMethod("compilerToVM");
        accessor.setAccessible(true);
        Object bridge = accessor.invoke(null);
        Method describe = bridgeClass.getDeclaredMethod("getInvalidationReasonDescription", int.class);
        describe.setAccessible(true);
        for (var reason : reasons.entrySet()) {
            System.out.println("Checking reason: " + reason.getKey());
            String description = (String) describe.invoke(bridge, Math.toIntExact(reason.getValue()));
            checkDescription(reason.getKey(), description);
            if (reason.getKey().equals("NOT_INVALIDATED") && !description.equals(initialDescription)) {
                throw new AssertionError("Mirror and VM descriptions disagree");
            }
        }
        System.out.println("InvalidationReasonSmoke passed: " + reasons.size() + " VM reasons and new mirror");
    }
}
