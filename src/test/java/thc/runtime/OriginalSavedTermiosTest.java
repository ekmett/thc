// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalSavedTermiosTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-termios";
    private final List<String> names = List.of("originalGetSavedTermios", "originalSetSavedTermios");
    private final List<String> symbols = List.of("__hscore_get_saved_termios", "__hscore_set_saved_termios");
    private static String entryId(String name) { return "main:OriginalSavedTermiosAudit." + name; }
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private Map<String,Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String,Object>>();
        for (var part : List.of("OriginalSavedTermiosAudit", "THC.InterfaceClosure")) modules.add(thc.CoreCbdFixtures.read(new File(root, prefix + "/saved/" + stage + "/core/" + part + ".cbd").toPath()));
        return CoreModules.merge(modules);
    }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private OriginalStdioOp validate(List<Object> call) {
        var reps = new ArrayList<Object>();
        for (var arg : (List<List<Object>>) call.get(2)) { var metadata = CoreRepresentations.metadata(arg); reps.add(metadata == null ? null : metadata.get("rep")); }
        return CoreOriginalStdio.validate(call.get(6), reps, (List<?>) call.get(3), ((Map<?,?>) call.get(6)).get("rep"));
    }
    @Test void originalNativeRowsMatchInterpretedAndFirstInstalledAstAndBytecode() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(true, manifest.get("supported"));
        hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalSavedTermiosAudit.hs", "t/fixtures/compiler/OriginalSavedTermiosNative.hs", "t/haskell-fixtures/OriginalTermiosFixtures.hs", "bin/core_original_foreign.py"));
        var artifacts = new HashSet<>(Set.of(prefix + "/saved/oracle.json"));
        for (var stage : List.of("pre", "post")) for (var name : names) artifacts.add(prefix + "/saved/" + stage + "/" + name + ".audit.json");
        hashes(root, manifest.get("artifactHashes"), artifacts, prefix + "/");
        var rows = (List<List<Long>>) json(prefix + "/saved/oracle.json").get("rows");
        var expected = new ArrayList<List<Long>>();
        for (long fd : new long[]{Integer.MIN_VALUE, -1, 0, 1, 2, 3, Integer.MAX_VALUE}) for (long offset : new long[]{-1, 0, 3, 8}) {
            long next = offset == -1 ? 5 : 8 - offset;
            expected.add(List.of(fd, offset, fd >= 0 && fd <= 2 ? offset : -1L, next, fd >= 0 && fd <= 2 ? next : -1L, 1L));
        }
        assertEquals(expected, rows);
        for (var stage : List.of("pre", "post")) {
            var source = module(stage); var foundSymbols = new HashSet<String>();
            for (var call : foreignCalls(source)) foundSymbols.add(Objects.requireNonNull(validate(call)).getSymbol());
            assertEquals(new HashSet<>(symbols), foundSymbols);
            for (int index = 0; index < names.size(); index++) {
                var audit = json(prefix + "/saved/" + stage + "/" + names.get(index) + ".audit.json");
                assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
                var actual = new ArrayList<Object>(); for (var call : (List<Map<?,?>>) audit.get("foreignCalls")) actual.add(call.get("symbol"));
                assertEquals(List.of(symbols.get(index)), actual);
            }
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = with(CoreModules.reachable(source, names.stream().map(OriginalSavedTermiosTest::entryId).toList(), true), "instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var get = program.entryTarget(entryId(names.get(0))); var set = program.entryTarget(entryId(names.get(1)));
                    class Exercise {
                        boolean compiled;
                        Object call(RootCallTarget target, Object... values) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            Object[] args = new Object[values.length + 1]; args[0] = 0L; System.arraycopy(values, 0, args, 1, values.length);
                            var value = callScalarTestTarget(target, args);
                            if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth()); return value;
                        }
                        void run() {
                            for (var row : rows) {
                                var base = ManagedAddress.fromByteArray(new byte[8]);
                                for (long fd = 0; fd <= 2; fd++) assertEquals(0L, call(set, fd, ManagedAddress.nullAddress()));
                                var first = row.get(1) == -1L ? ManagedAddress.nullAddress() : base.plus(row.get(1));
                                var next = row.get(3) == -1L ? ManagedAddress.nullAddress() : base.plus(row.get(3));
                                assertEquals(0L, call(set, row.get(0), first)); assertSame(row.get(2) == -1L ? ManagedAddress.nullAddress() : first, call(get, row.get(0)));
                                assertEquals(0L, call(set, row.get(0), next)); assertSame(row.get(4) == -1L ? ManagedAddress.nullAddress() : next, call(get, row.get(0)));
                                for (long other = 0; other <= 2; other++) if (other != row.get(0)) assertSame(ManagedAddress.nullAddress(), call(get, other));
                                assertEquals(0L, call(set, row.get(0), ManagedAddress.nullAddress())); assertSame(ManagedAddress.nullAddress(), call(get, row.get(0)));
                            }
                        }
                    }
                    var exercise = new Exercise(); exercise.run();
                    var active = new ArrayList<>(new LinkedHashSet<>(targets(get))); for (var target : targets(set)) if (!active.contains(target)) active.add(target);
                    var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    for (var target : active) { cls.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, cls.getMethod("isValidLastTier").invoke(target)); }
                    for (var target : List.of(get, set)) { var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", cls).invoke(runtime, target); }
                    exercise.compiled = true; exercise.run(); assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                } finally { context.leave(); }
            }
        }
    }
    @Test void stateAndCanonicalDescriptorValidationPrecedeSlotEffects() throws Exception {
        var source = module("pre");
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var saved = Language.currentState().getSavedTermios(); var original = ManagedAddress.fromByteArray(new byte[8]).plus(3); saved.set(1, original);
                for (var call : foreignCalls(source)) {
                    var operation = Objects.requireNonNull(validate(call)); var raw = rawModule(call, source);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, raw) : new BytecodeProgram(language, raw);
                    var target = program.entryTarget("entry");
                    Object[] arguments = operation == OriginalStdioOp.SET_SAVED_TERMIOS ? new Object[]{1L, ManagedAddress.nullAddress()} : new Object[]{1L};
                    Object[] packet = new Object[arguments.length + 2]; packet[0] = 0L; System.arraycopy(arguments, 0, packet, 1, arguments.length); packet[packet.length - 1] = 9L;
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, packet)); assertSame(original, saved.get(1));
                    for (long bad : new long[]{Long.MIN_VALUE, 2147483648L}) {
                        arguments[0] = bad; System.arraycopy(arguments, 0, packet, 1, arguments.length); packet[packet.length - 1] = thc.runtime.Unit.INSTANCE;
                        assertThrows(RuntimeFault.class, () -> Calls.target(target, packet)); assertSame(original, saved.get(1));
                    }
                    for (int i = 0; i < operation.getArguments().size(); i++) {
                        int index = i; assertThrows(RuntimeFault.class, () -> { var malformed = rawModule(call, source, index);
                            if (backend.equals("ast")) new Program(language, malformed); else new BytecodeProgram(language, malformed); });
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void onlyExactOriginalForeignDescriptorsAreRecognized() throws Exception {
        var calls = foreignCalls(module("pre")); assertEquals(2, calls.size());
        for (var original : calls) {
            Consumer<Consumer<Map<String,Object>>> reject = edit -> {
                var call = (List<Object>) Json.parse(Json.stringify(original));
                edit.accept((Map<String,Object>) ((Map<?,?>) call.get(6)).get("foreignCall"));
                assertThrows(RuntimeFault.class, () -> validate(call));
            };
            reject.accept(it -> it.put("safety", "safe")); reject.accept(it -> it.put("convention", "capi"));
            reject.accept(it -> it.put("arity", 1L)); reject.accept(it -> it.put("suppliedArity", 0L));
            reject.accept(it -> ((Map<String,Object>) it.get("target")).put("unit", "base"));
            reject.accept(it -> ((Map<String,Object>) it.get("target")).put("isFunction", false));
            reject.accept(it -> ((List<Map<String,Object>>) it.get("argumentReps")).get(0).put("primReps", list("IntRep")));
            reject.accept(it -> ((Map<String,Object>) it.get("resultRep")).put("components", List.of()));
        }
    }
}
