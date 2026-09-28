// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Untouched original foreign applications, not a replacement Haskell decoder or native frames. */
@SuppressWarnings("unchecked")
public class OriginalStackDecoderCallTest {
    private Map<String, Object> resource() throws Exception {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/core/original-stack-decoder-calls.json"))) {
            return (Map<String, Object>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Set<String> hot = new LinkedHashSet<>(List.of("getStackFieldszh", "getSmallBitmapzh", "advanceStackFrameLocationzh", "getWordzh"));
    private final Set<String> cold = new LinkedHashSet<>(List.of("getStackClosurezh", "getLargeBitmapzh", "getBCOLargeBitmapzh", "getRetFunLargeBitmapzh", "getRetFunSmallBitmapzh", "isArgGenBigRetFunTypezh", "getUnderflowFrameNextChunkzh"));
    private String name(String symbol, int field) { return symbol + "-" + field; }
    /** Each tuple component is independently returned; no getter's result is replaced by a constant. */
    private Map<String, Object> module() throws Exception {
        var original = resource(); var bindings = new ArrayList<Map<String, Object>>();
        for (var record : (List<Map<String, Object>>) original.get("calls")) {
            var call = (List<Object>) record.get("application"); var result = (Map<String, Object>) ((Map<String, Object>) call.get(6)).get("rep");
            var descriptor = (Map<String, Object>) ((Map<String, Object>) call.get(6)).get("foreignCall");
            var symbol = (String) ((Map<String, Object>) descriptor.get("target")).get("symbol");
            var components = result.get("components") instanceof List<?> fields ? (List<Map<String, Object>>) fields : null;
            var formals = new ArrayList<Map<String, Object>>();
            for (var argument : (List<List<Object>>) call.get(2)) formals.add(Map.of("id", argument.get(1), "name", argument.get(1), "lifted", false, "rep", ((Map<String, Object>) argument.getLast()).get("rep")));
            for (int choice = 0; choice < (components == null ? 1 : components.size()); choice++) {
                var name = name(symbol, choice); var scalar = components == null ? result : components.get(choice); Object body = call;
                if (components != null) {
                    var ids = new ArrayList<String>(); for (int i = 0; i < components.size(); i++) ids.add(name + "-result-" + i);
                    var constructor = "ghc-internal:GHC.Internal.Types.(#" + ",".repeat(components.size() - 1) + "#)";
                    var binders = new ArrayList<Map<String, Object>>(); for (int i = 0; i < components.size(); i++) binders.add(Map.of("id", ids.get(i), "lifted", false, "rep", components.get(i)));
                    body = List.of("case", call, name + "-tuple", List.of(List.of("data", constructor, ids, List.of("var", ids.get(choice), Map.of("rep", scalar)), Map.of("binders", binders))),
                        Map.of("rep", scalar, "binder", Map.of("id", name + "-tuple", "lifted", false, "rep", result)));
                }
                bindings.add(Map.of("id", "test:OriginalStackDecoder." + name, "name", name, "arity", formals.size(), "lifted", true, "rep", closure,
                    "expr", List.of("lam", formals, body, Map.of("rep", closure, "resultRep", scalar))));
            }
        }
        var constructors = new ArrayList<Map<String, Object>>();
        for (int arity = 2; arity <= 3; arity++) { var name = "(#" + ",".repeat(arity - 1) + "#)"; constructors.add(Map.of("id", "ghc-internal:GHC.Internal.Types." + name, "name", name, "kind", "unboxed-tuple", "arity", arity, "tag", 1)); }
        return Map.of("schema", 1, "ghc", "9.14.1", "unit", "test", "module", "OriginalStackDecoder", "instrument", true,
            "targetLayout", StackInfoTestLayout.layout(), "bindings", bindings, "sourceFiles", original.get("sourceFiles"), "sourceSpans", original.get("sourceSpans"), "constructors", constructors);
    }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private static final class Capture extends Expr { @Override public Object execute(VirtualFrame frame) { return ManagedStackSnapshot.capture(this); } }
    private static final class CaptureRoot extends GuestRoot {
        @Child private Capture body = new Capture();
        CaptureRoot(Language language) { super(language, new FrameLayout().build()); }
        @Override public Object execute(VirtualFrame frame) { return body.execute(frame); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public String getName() { return "decoder-leaf"; }
    }
    private static final class CallerRoot extends GuestRoot {
        @Child private DirectCallNode call;
        CallerRoot(Language language, RootCallTarget target) { super(language, new FrameLayout().build()); call = DirectCallNode.create(target); }
        @Override public Object execute(VirtualFrame frame) { return call.call(); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public String getName() { return "decoder-caller"; }
    }
    private String digest(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private Object symbol(List<Object> app) { return ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) app.get(6)).get("foreignCall")).get("target")).get("symbol"); }
    private void references(Object value, Set<String> referenced) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("source") instanceof String source) referenced.add(source);
            if (map.get("sourceNotes") instanceof List<?> notes) referenced.addAll((List<String>) notes);
            for (var child : map.values()) references(child, referenced);
        } else if (value instanceof List<?> list) for (var child : list) references(child, referenced);
    }
    private void checkProvenance(Map<String, Object> resource) throws Exception {
        var text = Files.readString(new File(System.getProperty("thc.projectRoot"), "t/fixtures/compiler/OriginalStackProof.json").toPath());
        assertEquals("db63661c12a6ecb757697e759fcb95e4d51f3689619bdb7682a041788eb41d4f", digest(text));
        var proof = (Map<String, Object>) Json.parse(text); var source = "GHC.Internal.Stack.Decode/GHC.Internal.Stack.Decode.json";
        var all = new LinkedHashSet<>(hot); all.addAll(cold); var seen = new LinkedHashSet<Object>(); var expected = new ArrayList<Map<String, Object>>();
        for (var record : (List<Map<String, Object>>) proof.get("calls")) {
            var symbol = symbol((List<Object>) record.get("expression")); if (!all.contains(symbol) || !seen.add(symbol)) continue;
            var path = (List<?>) record.get("path"); var joined = new StringBuilder("/");
            for (int i = 2; i < path.size(); i++) { if (i > 2) joined.append('/'); joined.append(path.get(i)); }
            expected.add(Map.of("source", source, "owner", record.get("owner"), "application", record.get("expression"), "path", joined.toString()));
        }
        assertEquals("902339d332fb4ce2b3c87dcac1ee6495d41ad886", resource.get("ghcRevision")); assertEquals("62e3400c5b889d3971cb4047709c408fd270255f", resource.get("exporterRevision"));
        var calls = (List<Map<String, Object>>) resource.get("calls"); assertEquals(expected, calls); // Identity, owner-relative path and raw application together.
        var symbols = new ArrayList<String>();
        for (var record : calls) {
            var app = (List<Object>) record.get("application"); var meta = (Map<String, Object>) app.get(6); var descriptor = (Map<String, Object>) meta.get("foreignCall");
            var symbol = (String) ((Map<String, Object>) descriptor.get("target")).get("symbol");
            if (symbol.equals("getStackClosurezh")) assertEquals(Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false), descriptor.get("resultRep"));
            symbols.add(symbol);
        }
        assertEquals(all, new LinkedHashSet<>(symbols)); assertEquals(11, symbols.size());
        var files = (List<Map<String, Object>>) resource.get("sourceFiles"); assertEquals(1, files.size()); var file = files.getFirst();
        assertEquals(Set.of("id", "path", "content"), file.keySet()); assertEquals(file.get("id"), file.get("path"));
        assertEquals("0ea6a82ea41bdf14b28aec5cb36a586ed86eb6f87f373ea21095d2b1b018089f", digest((String) file.get("content")));
        var referenced = new LinkedHashSet<String>(); for (var call : calls) references(call.get("application"), referenced);
        var spans = (List<Map<String, Object>>) resource.get("sourceSpans");
        var ids = new LinkedHashSet<>(); var spanFiles = new LinkedHashSet<>(); for (var span : spans) { ids.add(span.get("id")); spanFiles.add(span.get("file")); }
        assertEquals(referenced, ids); assertEquals(referenced.size(), spans.size());
        var fileIds = new LinkedHashSet<>(); for (var item : files) fileIds.add(item.get("id")); assertEquals(fileIds, spanFiles);
        // Pin records COPIED from the sourceFiles/sourceSpans tables of the full
        // c3762b0e... export, not reconstructed from note IDs. Sorted JSON object
        // keys make formatting irrelevant while preserving original record order.
        var sortedFiles = new ArrayList<Map<String, Object>>(); for (var item : files) sortedFiles.add(new TreeMap<>(item));
        var sortedSpans = new ArrayList<Map<String, Object>>(); for (var item : spans) sortedSpans.add(new TreeMap<>(item));
        var projection = new LinkedHashMap<String, Object>(); projection.put("sourceFiles", sortedFiles); projection.put("sourceSpans", sortedSpans);
        assertEquals("a3c80947a99ee3a53fc4bbe415ad20b21f9a76852b8324a73d14baf240379dd2", digest(Json.stringify(projection)));
        assertEquals(List.of(Map.of("file", source, "sha256", "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42", "boundary", "optimized-Core-after-Tidy-before-CorePrep")), resource.get("sources"));
    }
    private Map<String, Object> plus(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    private List<Map<String, Object>> firstChanged(List<Map<String, Object>> source, String key, Object value) { var result = new ArrayList<>(source); result.set(0, plus(source.getFirst(), key, value)); return result; }
    @Test public void retainedApplicationsAreExactOriginalProofExcerptsIncludingLiftedClosureResult() throws Exception { checkProvenance(resource()); }
    @Test public void retainedOwnerPathsAndSourceProjectionRejectMutation() throws Exception {
        var original = resource(); var calls = (List<Map<String, Object>>) original.get("calls");
        for (var field : List.of("owner", "path", "source")) assertThrows(AssertionError.class, () -> checkProvenance(plus(original, "calls", firstChanged(calls, field, "forged"))));
        var spans = (List<Map<String, Object>>) original.get("sourceSpans"); var repeated = new ArrayList<>(spans); repeated.add(spans.getFirst());
        for (var changed : List.of(spans.subList(1, spans.size()), repeated, firstChanged(spans, "label", "forged"), firstChanged(spans, "charIndex", -1L)))
            assertThrows(AssertionError.class, () -> checkProvenance(plus(original, "sourceSpans", changed)));
        var files = (List<Map<String, Object>>) original.get("sourceFiles"); var repeatedFiles = new ArrayList<>(files); repeatedFiles.add(files.getFirst());
        for (var changed : List.of(List.of(), repeatedFiles, List.of(plus(files.getFirst(), "path", "forged")))) assertThrows(AssertionError.class, () -> checkProvenance(plus(original, "sourceFiles", changed)));
    }
    @Test public void originalAstGettersExecuteInFirstInstalledCompiledEntries() throws Exception { exerciseBackend("ast"); }
    @Test public void originalBytecodeGettersExecuteInFirstInstalledCompiledEntries() throws Exception { exerciseBackend("bytecode"); }
    private void exerciseBackend(String backend) throws Exception {
        for (boolean inlining : new boolean[]{false, true}) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var capture = new CaptureRoot(language).getCallTarget();
                var single = (ManagedStackSnapshot) capture.call(); var multiple = (ManagedStackSnapshot) new CallerRoot(language, capture).getCallTarget().call();
                assertEquals(1, single.getFrames().size()); assertEquals(2, multiple.getFrames().size());
                var input = module(); ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                var targets = new LinkedHashMap<String, RootCallTarget>(); for (var binding : (List<Map<String, Object>>) input.get("bindings")) targets.put((String) binding.get("name"), program.entryTarget((String) binding.get("name")));
                class Runner {
                    boolean compiled;
                    Object call(String symbol, int field, Object... args) throws Exception {
                        var name = name(symbol, field); var target = targets.get(name); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var packet = new Object[args.length + 1]; packet[0] = 0L; System.arraycopy(args, 0, packet, 1, args.length);
                        try { return Calls.target(target, packet); }
                        finally {
                            if (compiled) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), name); valid(target, backend + "/inlining=" + inlining + "/" + name); }
                            released(language);
                        }
                    }
                    void exercise() throws Exception {
                        for (var snapshot : List.of(single, multiple)) {
                            assertEquals((long) snapshot.getFrames().size(), call("getStackFieldszh", 0, snapshot));
                            for (int offset = 0; offset < snapshot.getFrames().size(); offset++) {
                                var key = ManagedStackRuntime.frameInfo(snapshot, offset, StackInfoTestLayout.layout()).getKey();
                                long bits = (Long) call("getWordzh", 0, snapshot, (long) offset); assertEquals(key.toNativeBits(), bits);
                                assertTrue(key.sameLocation(NativeAddresses.current(null).recover(bits)));
                                assertEquals(0L, call("getSmallBitmapzh", 0, snapshot, (long) offset)); assertEquals(0L, call("getSmallBitmapzh", 1, snapshot, (long) offset));
                                boolean more = offset + 1 < snapshot.getFrames().size(); var next = call("advanceStackFrameLocationzh", 0, snapshot, (long) offset);
                                if (more) assertSame(snapshot, next); else assertNull(next);
                                assertEquals(more ? offset + 1L : 0L, call("advanceStackFrameLocationzh", 1, snapshot, (long) offset));
                                assertEquals(more ? 1L : 0L, call("advanceStackFrameLocationzh", 2, snapshot, (long) offset));
                            }
                        }
                    }
                }
                var runner = new Runner(); for (int i = 0; i < 3; i++) runner.exercise();
                for (var entry : targets.entrySet()) {
                    boolean selected = false; for (var symbol : hot) if (entry.getKey().startsWith(symbol + "-")) { selected = true; break; }
                    if (selected) { var target = entry.getValue(); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, backend + "/inlining=" + inlining + "/" + entry.getKey() + " installation"); }
                }
                runner.compiled = true; runner.exercise(); // First entry after installation, no settling or recompilation.
                runner.exercise(); runner.compiled = false;
                for (var symbol : cold) assertThrows(RuntimeFault.class, () -> runner.call(symbol, 0, multiple, 0L), backend + "/" + symbol + " must reject absent payload or incompatible frame kind");
                for (var symbol : List.of("getSmallBitmapzh", "advanceStackFrameLocationzh", "getWordzh")) for (long offset : new long[]{-1L, 2L, Long.MAX_VALUE, Long.MIN_VALUE}) assertThrows(RuntimeFault.class, () -> runner.call(symbol, 0, multiple, offset));
                assertThrows(RuntimeFault.class, () -> runner.call("getStackFieldszh", 0, (Object) null));
                assertThrows(RuntimeFault.class, () -> runner.call("advanceStackFrameLocationzh", 0, null, 0L)); released(language);
            } finally { context.leave(); }
        }
    }
}
