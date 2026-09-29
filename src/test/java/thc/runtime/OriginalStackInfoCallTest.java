// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Unchanged original FCall applications in synthetic scalar-result consumers. */
@SuppressWarnings("unchecked")
public class OriginalStackInfoCallTest {
    private Map<String, Object> original() throws Exception {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/core/original-stack-info-calls.json"))) {
            return (Map<String, Object>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private Map<String, Object> module(Object layout) throws Exception { return module(layout, binding -> {}); }
    private Map<String, Object> module(Object layout, Consumer<Map<String, Object>> mutate) throws Exception {
        var source = original(); var bindings = new ArrayList<Map<String, Object>>();
        for (var record : (List<Map<String, Object>>) source.get("calls")) {
            var call = (List<Object>) record.get("application"); var metadata = (Map<String, Object>) call.get(6);
            var tuple = (Map<String, Object>) metadata.get("rep"); var foreign = (Map<String, Object>) metadata.get("foreignCall");
            var symbol = (String) ((Map<String, Object>) foreign.get("target")).get("symbol");
            var components = tuple.get("components") instanceof List<?> values ? (List<Map<String, Object>>) values : null;
            var choices = symbol.equals("getInfoTableAddrszh") ? List.of(0, 1) : List.of(components == null ? 0 : components.size() - 1);
            for (int choice : choices) {
                var name = switch (symbol) { case "getStackInfoTableAddrzh" -> "stack"; case "getInfoTableAddrszh" -> choice == 0 ? "frame" : "key"; default -> "lookup"; };
                var formals = new ArrayList<Map<String, Object>>();
                for (var argument : (List<List<Object>>) call.get(2)) formals.add(Map.of("id", argument.get(1), "name", argument.get(1), "lifted", false, "rep", ((Map<String, Object>) argument.getLast()).get("rep")));
                var result = components == null ? tuple : components.get(choice);
                Object body = call;
                if (components != null) {
                    var ids = new ArrayList<String>(); for (int i = 0; i < components.size(); i++) ids.add(name + "-result-" + i);
                    var binders = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < components.size(); i++) binders.add(Map.of("id", ids.get(i), "lifted", false, "rep", components.get(i)));
                    body = List.of("case", call, name + "-pair", List.of(List.of("data", "ghc-internal:GHC.Internal.Types.(#,#)", ids,
                        List.of("var", ids.get(choice), Map.of("rep", result)), Map.of("binders", binders))),
                        Map.of("rep", result, "binder", Map.of("id", name + "-pair", "lifted", false, "rep", tuple)));
                }
                var binding = new LinkedHashMap<String, Object>();
                binding.put("id", "test:OriginalStackInfo." + name); binding.put("name", name); binding.put("arity", formals.size()); binding.put("lifted", true);
                binding.put("rep", closure); binding.put("expr", List.of("lam", formals, body, Map.of("rep", closure, "resultRep", result)));
                mutate.accept(binding); bindings.add(binding);
            }
        }
        var module = new LinkedHashMap<String, Object>();
        module.put("schema", 1); module.put("module", "OriginalStackInfo"); module.put("unit", "test"); module.put("ghc", "9.14.1");
        module.put("instrument", true); module.put("bindings", bindings); module.put("targetLayout", layout);
        module.put("constructors", List.of(Map.of("id", "ghc-internal:GHC.Internal.Types.(#,#)", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
        module.put("sourceFiles", source.get("sourceFiles")); module.put("sourceSpans", source.get("sourceSpans")); return module;
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    private static final class Capture extends Expr {
        @Override public Object execute(VirtualFrame frame) { return ManagedStackSnapshot.capture(this); }
    }
    private static final class CaptureRoot extends GuestRoot {
        @Child private Expr body = new Capture();
        CaptureRoot(Language language) { super(language, new FrameLayout().build()); }
        @Override public Object execute(VirtualFrame frame) { return body.execute(frame); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public String getName() { return "stack-info-call-test"; }
    }
    @Test public void originalApplicationsExecuteInBothBackendsAndFirstInstalledCompiledEntries() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{false, true}) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var snapshot = (ManagedStackSnapshot) new CaptureRoot(language).getCallTarget().call();
                var layout = StackInfoTestLayout.layout(); var program = load(language, backend, module(layout));
                var targets = new LinkedHashMap<String, RootCallTarget>(); for (var name : List.of("stack", "frame", "key", "lookup")) targets.put(name, program.entryTarget(name));
                class Runner {
                    boolean compiled;
                    Object call(String name, Object... arguments) throws Exception {
                        var before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var target = targets.get(name);
                        var packet = new Object[arguments.length + 1]; packet[0] = 0L; System.arraycopy(arguments, 0, packet, 1, arguments.length);
                        Object result;
                        var typed = ((GuestRoot) target.getRootNode()).getTypedInput();
                        if (typed == null) result = Calls.target(target, packet);
                        else {
                            // Address formals use the callee's real typed entry,
                            // including the scalar State token, not a raw array.
                            var input = typed.state().getArguments().acquire(typed.getPacket());
                            input.setInputMode(1);
                            try {
                                typed.getPacket().copyIn(input, packet);
                                result = Calls.target(target, new Object[]{input});
                            } finally { typed.releaseChecked(input); }
                        }
                        if (compiled) {
                            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), name);
                            valid(target, backend + "/inlining=" + inlining + "/" + name);
                        }
                        released(language); return result;
                    }
                    void exercise() throws Exception {
                        var stack = (ManagedAddress) call("stack", snapshot); var standard = (ManagedAddress) call("frame", snapshot, 0L); var key = (ManagedAddress) call("key", snapshot, 0L);
                        long typeByte = (long) layout.offset("infoTableTypeOffset") + (StackInfoTestLayout.endian.equals("little") ? 0 : 3);
                        assertEquals(53L, stack.readWord8(typeByte)); assertEquals(30L, standard.readWord8(typeByte));
                        assertTrue(standard.plus((long) layout.offset("infoTableBytes")).sameLocation(key));
                        var storage = ManagedAllocation.mutable(89, 8); var output = ManagedAddress.fromAllocation(storage).plus(5);
                        assertEquals(1, call("lookup", key, output, thc.runtime.Unit.INSTANCE));
                        assertTrue(key.sameLocation(storage.readAddressByteOffset(5)));
                        assertEquals("THC managed diagnostic frame", storage.readAddressByteOffset(13).utf8());
                        var before = storage.readAddressByteOffset(13);
                        assertEquals(0, call("lookup", ManagedAddress.nullAddress(), output, thc.runtime.Unit.INSTANCE));
                        assertSame(before, storage.readAddressByteOffset(13));
                    }
                }
                var runner = new Runner(); for (int i = 0; i < 3; i++) runner.exercise();
                for (var entry : targets.entrySet()) {
                    entry.getValue().getClass().getMethod("compile", boolean.class).invoke(entry.getValue(), true);
                    valid(entry.getValue(), backend + "/inlining=" + inlining + "/" + entry.getKey() + " installation");
                }
                runner.compiled = true; runner.exercise(); // First installed call, no settling or recompilation.
                runner.exercise(); runner.compiled = false;
                var key = (ManagedAddress) runner.call("key", snapshot, 0L); var output = ManagedAddress.fromAllocation(ManagedAllocation.mutable(72, 8));
                assertThrows(RuntimeFault.class, () -> runner.call("lookup", key, output, 9L));
                assertThrows(RuntimeFault.class, () -> runner.call("frame", snapshot, -1L)); released(language);
            } finally { context.leave(); }
        }
    }
    @Test public void typedLayoutAndStoredOperandProofsAreRequiredBeforeExecution() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var layout : Arrays.asList(null, StackInfoTestLayout.document(), StackInfoTestLayout.layout(Map.of("tablesNextToCode", false))))
                    assertThrows(RuntimeFault.class, () -> load(language, backend, module(layout)));
                var bad = module(StackInfoTestLayout.layout(), binding -> {
                    var lambda = new ArrayList<>((List<Object>) binding.get("expr")); var formals = new ArrayList<>((List<Map<String, Object>>) lambda.get(1));
                    var first = new LinkedHashMap<>(formals.getFirst()); first.put("rep", Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true));
                    formals.set(0, first); lambda.set(1, formals); binding.put("expr", lambda);
                });
                assertThrows(RuntimeFault.class, () -> load(language, backend, bad)); released(language);
            } finally { context.leave(); }
        }
    }
}
