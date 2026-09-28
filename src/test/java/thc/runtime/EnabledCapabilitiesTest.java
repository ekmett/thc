// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import kotlin.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.ContextProfile;
import thc.Language;
import thc.Main;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

class EnabledCapabilitiesTest {
    private final Map<String, Object> address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
    private final Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> offset = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> word32 = Map.of("kind", "long", "primReps", List.of("Word32Rep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
        "primReps", List.of("Word32Rep"), "components", List.of(state, word32));
    private final List<Object> label = List.of("lit", "data-addr", "enabled_capabilities", Map.of("rep", address));

    private Map<String, Object> module() {
        var parameter = Map.of("id", "s", "lifted", false, "rep", state);
        var call = List.of("app", List.of("prim", "readWord32OffAddr#"),
            List.of(label, List.of("lit", "int", "0", Map.of("rep", offset)),
                List.of("var", "s", Map.of("rep", state))),
            List.of(false, false, false), false, false, Map.of("rep", result));
        var fields = List.of(Map.of("id", "next", "lifted", false, "rep", state),
            Map.of("id", "value", "lifted", false, "rep", word32));
        var body = List.of("case", call, "pair", List.of(List.of("data", "tuple2", List.of("next", "value"),
            List.of("var", "value", Map.of("rep", word32)), Map.of("binders", fields))),
            Map.of("rep", word32, "binder", Map.of("id", "pair", "lifted", false, "rep", result)));
        return Map.of("instrument", true,
            "constructors", List.of(Map.of("id", "tuple2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "bindings", List.of(Map.of("id", "read", "name", "read", "arity", 1,
                "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(parameter), body, Map.of("rep", closure, "resultRep", word32)))));
    }

    private Context context() { return Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.SYNCHRONOUS_TEST).build(); }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }

    @Test void liveLabelUsesExactWord32ReadAndOwnedContextInBothBackends() throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var threads = Language.currentState().getThreads();
                long cpuCount = threads.getCpuAffinity().getCount();
                assertTrue(cpuCount >= 1 && cpuCount <= Runtime.getRuntime().availableProcessors());
                threads.enterCurrent(null, false, true, null);
                try {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                    var target = program.entryTarget("read");
                    LongSupplier read = () -> Integer.toUnsignedLong((Integer) Calls.target(target, new Object[]{0L, Unit.INSTANCE}));
                    for (int i = 0; i < 100; i++) assertEquals(cpuCount, read.getAsLong());
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    valid(target);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(cpuCount, read.getAsLong());
                    assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    valid(target);

                    var workerFailure = new AtomicReference<Throwable>();
                    var worker = new Thread(() -> {
                        try { threads.enterCurrent(null, false, true, null); threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                        catch (Throwable failure) { workerFailure.set(failure); }
                    });
                    worker.start(); worker.join();
                    if (workerFailure.get() != null) throw new AssertionError("Guest carrier registration failed", workerFailure.get());
                    assertEquals(cpuCount, read.getAsLong(), "Guest carriers share the available CPU capacity");
                    valid(target);

                    var cell = CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.INSTANCE.parse(address));
                    assertEquals(cpuCount, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(cell, 0)));
                    for (var operation : ManagedAddressRead.values()) if (operation != ManagedAddressRead.WORD32)
                        assertThrows(RuntimeFault.class, () -> {
                            if (operation.isInt()) operation.readInt(cell, 0); else operation.read(cell, 0);
                        });
                    assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD32.readInt(cell, 1));
                    assertThrows(RuntimeFault.class, () -> cell.readWord8(0));
                    assertThrows(RuntimeFault.class, () -> cell.writeWord8(0, 1));
                    assertThrows(RuntimeFault.class, () -> cell.writeNativeScalar(0, 4, 1));
                    assertThrows(RuntimeFault.class, () -> cell.plus(1));
                    assertThrows(RuntimeFault.class, cell::toNativeBits);
                    for (String bad : List.of("other_symbol", "enabled_capabilities_extra"))
                        assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore(bad, CoreRepresentations.INSTANCE.parse(address)));
                    var wrongRep = new LinkedHashMap<>(address);
                    wrongRep.put("primReps", List.of("WordRep"));
                    assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.INSTANCE.parse(wrongRep)));
                    var unevaluated = new LinkedHashMap<>(address);
                    unevaluated.put("evaluated", false);
                    assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.INSTANCE.parse(unevaluated)));

                    ManagedAddress foreignCell;
                    try (var foreign = context()) {
                        foreign.initialize("thc"); foreign.enter();
                        try {
                            assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD32.readInt(cell, 0));
                            foreignCell = CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.INSTANCE.parse(address));
                        } finally { foreign.leave(); }
                    }
                    assertEquals(cpuCount, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(cell, 0)));
                    assertTrue(cell.sameLocation(cell));
                    assertThrows(RuntimeFault.class, () -> cell.sameLocation(foreignCell));
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
}
