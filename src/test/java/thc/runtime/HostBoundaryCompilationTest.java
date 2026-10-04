// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import thc.EntryValue;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** A valid guest target does not imply that HotSpot's shared entry stub survived. */
@Isolated("Retires the JVM-wide Truffle call-boundary stub")
@Execution(ExecutionMode.SAME_THREAD)
class HostBoundaryCompilationTest {
    private Map<String, Object> module() {
        return Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.HostBoundaryCompilation",
            "instrument", true, "constructors", List.of(),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true,
                "expr", List.of("lam", List.of(Map.of("id", "input", "lifted", false)),
                    List.of("app", List.of("prim", "+#"), List.of(List.of("var", "input"), List.of("lit", "int", "1")), List.of(false, false))))));
    }
    private Object boundaryMethod() throws ReflectiveOperationException {
        var jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
        var backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci);
        var metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend);
        var method = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getDeclaredMethod("callBoundary", Object[].class);
        return Class.forName("jdk.vm.ci.meta.MetaAccessProvider").getMethod("lookupJavaMethod", java.lang.reflect.Executable.class).invoke(metaAccess, method);
    }
    private void checkBoundaryCompilation(String backend) throws ReflectiveOperationException {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = switch (backend) {
                    case "ast" -> new Program(language, module());
                    case "bytecode" -> new BytecodeProgram(language, module());
                    default -> throw new IllegalStateException("Unexpected backend: " + backend);
                };
                var guest = program.entryTarget("entry"); var host = program.hostEntryTarget(1);
                var function = context.asValue(new EntryValue(program, "entry", 1));
                for (int i = 0; i < 40; i++) assertEquals((long) i + 1, function.execute((long) i).asLong());
                assertTrue(function.invokeMember("compile").asBoolean());
                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                var runtime = Truffle.getRuntime();
                var repair = runtime.getClass().getMethod("bypassedInstalledCode", targetClass);
                var boundary = boundaryMethod();
                var hasCode = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode");
                var reprofile = Class.forName("jdk.vm.ci.meta.ResolvedJavaMethod").getMethod("reprofile");
                class Observation {
                    void validTargets() throws ReflectiveOperationException {
                        assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(guest));
                        assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(host));
                    }
                    long entries() { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
                    List<Object> calls() throws ReflectiveOperationException {
                        return List.of(targetClass.getMethod("getCallCount").invoke(guest), targetClass.getMethod("getCallCount").invoke(host));
                    }
                }
                var observation = new Observation();
                // Retire only the shared boundary nmethod; always restore the JVM-wide stub.
                repair.invoke(runtime, host);
                try {
                    assertEquals(true, hasCode.invoke(boundary)); observation.validTargets();
                    reprofile.invoke(boundary);
                    assertEquals(false, hasCode.invoke(boundary), "Retire the boundary without executing another guest call");
                    observation.validTargets();
                    // Raw target compilation does not repair the shared host
                    // entry stub. The first public bridge still runs interpreted.
                    var rawCompile = targetClass.getMethod("compile", boolean.class);
                    var waitForCompilation = targetClass.getMethod("waitForCompilation");
                    for (var target : List.of(guest, host)) {
                        rawCompile.invoke(target, true);
                        waitForCompilation.invoke(target);
                    }
                    assertEquals(false, hasCode.invoke(boundary), "Raw compilation leaves the stub retired");
                    observation.validTargets();
                    int hostCalls = (Integer) observation.calls().get(1);
                    assertEquals(8L, function.execute(7L).asLong());
                    assertEquals(hostCalls + 1, observation.calls().get(1), "The negative control misses installed host code");

                    // The public compile operation must repair before the next
                    // call, without using a guest invocation to settle the stub.
                    reprofile.invoke(boundary);
                    assertEquals(false, hasCode.invoke(boundary));
                    observation.validTargets();
                    long beforeCompile = observation.entries(); var callsBeforeCompile = observation.calls();
                    assertTrue(function.invokeMember("compile").asBoolean());
                    var restoredBeforeCall = hasCode.invoke(boundary); observation.validTargets();
                    assertEquals(beforeCompile, observation.entries(), "Compilation must not execute compiled guest code");
                    assertEquals(callsBeforeCompile, observation.calls(), "Compilation must not settle interpreted or first-tier calls");
                    long beforeCall = observation.entries();
                    assertEquals(3_000_000_002L, function.execute(3_000_000_001L).asLong());
                    long delta = observation.entries() - beforeCall;
                    assertEquals(callsBeforeCompile, observation.calls(), "The first public call must enter installed host and guest code");
                    System.out.println("HOST_BOUNDARY " + backend + " restoredBeforeCall=" + restoredBeforeCall + " firstCompiledDelta=" + delta);
                    assertAll(
                        () -> assertEquals(true, restoredBeforeCall, "Explicit compilation must restore the shared stub before the first public call"),
                        () -> assertTrue(delta > 0, "The first public call must enter installed guest code"));
                } finally {
                    repair.invoke(runtime, host);
                    assertEquals(true, hasCode.invoke(boundary), "Do not leave subsequent tests with a retired boundary");
                }
            } finally { context.leave(); }
        }
    }
    @Test void astCompilationRestoresTheRetiredBoundary() throws ReflectiveOperationException { checkBoundaryCompilation("ast"); }
    @Test void bytecodeCompilationRestoresTheRetiredBoundary() throws ReflectiveOperationException { checkBoundaryCompilation("bytecode"); }
}
