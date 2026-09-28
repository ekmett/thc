// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static thc.Main.withContextProfile;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class WcwidthTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/wcwidth");
    private Map<String, Object> json(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(directory, path).toPath(), StandardCharsets.UTF_8)); }
    @FunctionalInterface private interface Action { void run() throws Throwable; }
    // Thread-local native locale: do not mutate the JVM's process-wide locale.
    // The guest's synchronous libc call executes on this same carrier thread.
    private void withLocale(String name, Action action) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var linker = Linker.nativeLinker();
            var create = linker.downcallHandle(linker.defaultLookup().find("newlocale").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var use = linker.downcallHandle(linker.defaultLookup().find("uselocale").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var free = linker.downcallHandle(linker.defaultLookup().find("freelocale").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            var selected = (MemorySegment) create.invokeWithArguments(1, arena.allocateFrom(name), MemorySegment.NULL); assertNotEquals(0L, selected.address(), "Linux LC_CTYPE locale " + name + " must exist");
            var previous = (MemorySegment) use.invokeWithArguments(selected); assertNotEquals(0L, previous.address());
            try { action.run(); } finally { use.invokeWithArguments(previous); free.invokeWithArguments(selected); }
        }
    }
    private void checkRows(boolean compiled, List<List<Long>> rows, int index, ExecutableProgram program, RootCallTarget target, Language language, String label) throws Exception {
        for (var row : rows) {
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            assertEquals(row.get(index + 1), Calls.target(target, new Object[] {0L, row.getFirst()}), label + "/" + row.getFirst());
            if (compiled) { assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
            assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
        }
    }
    @Test public void originalTastyDeclarationAndFallbackMatchNativeLocalesInBothCompiledBackends() throws Throwable {
        var manifest = json("manifest.json"); OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("inputHashes"), Set.of(
            "test/fixtures/run-wcwidth/src/Width.hs", "test/fixtures/run-wcwidth/app/Main.hs", "test/haskell-fixtures/WcwidthFixtures.hs",
            "src/THC/Driver/PackageNative.hs", "src/THC/Driver/NativeLibrarySources.hs"), null);
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("artifactHashes"), Set.of("build/wcwidth/Width.json", "build/wcwidth/C.tsv", "build/wcwidth/C.UTF-8.tsv",
            "build/wcwidth/rawWidth.json", "build/wcwidth/displayWidth.json", "build/wcwidth/ConsoleReporter.hs", "build/wcwidth/TASTY-LICENSE"), "build/wcwidth/");
        for (String entry : List.of("rawWidth", "displayWidth")) assertEquals(true, json(entry + ".json").get("accepted"));
        var original = json("Width.json"); var proof = (Map<?, ?>) original.get("packageNativeLink"); assertEquals("llvm-embedded-elf", proof.get("format"));
        var libraries = (List<Map<?, ?>>) ((Map<?, ?>) proof.get("buildInputs")).get("nativeLibraries"); var providers = new ArrayList<Object>(); for (var library : libraries) providers.add(library.get("provider"));
        assertEquals(List.of("native-libc-wcwidth-v1"), providers); var merged = CoreModules.merge(List.of(original)); var links = (List<PackageScalarLink>) merged.get("packageScalarLinks");
        assertEquals(1, links.size()); var link = links.getFirst(); assertFalse(link.getAbi().isEmpty());
        for (var abi : link.getAbi()) { assertEquals("safe", abi.getSafety()); assertEquals("capi", abi.getConvention()); assertEquals(List.of("Int32Rep"), abi.getArguments()); assertEquals("Int32Rep", abi.getResult()); }
        var observations = new LinkedHashMap<String, List<List<Long>>>();
        for (String locale : List.of("C", "C.UTF-8")) {
            var rows = new ArrayList<List<Long>>(); for (String line : Files.readAllLines(new File(directory, locale + ".tsv").toPath(), StandardCharsets.UTF_8)) {
                var row = new ArrayList<Long>(); for (String cell : line.split("\t", -1)) row.add(Long.parseLong(cell)); rows.add(row);
            }
            observations.put(locale, rows);
        }
        for (var rows : observations.values()) assertEquals(12, rows.size()); assertNotEquals(observations.get("C"), observations.get("C.UTF-8"), "oracle exercises locale dependence");
        for (var observation : observations.entrySet()) {
            String locale = observation.getKey(); var rows = observation.getValue(); for (var row : rows) assertEquals(row.get(1) == -1L ? 1L : row.get(1), row.get(2));
            for (String backend : List.of("ast", "bytecode")) withLocale(locale, () -> {
                try (Context context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(); owner.getPackageCbits().link(link); owner.getThreads().enterCurrent(null, false, true, null);
                        try {
                            var names = List.of("rawWidth", "displayWidth");
                            for (int index = 0; index < names.size(); index++) {
                                String name = names.get(index), entry = "wcwidth-ffi-0.1.0.0-inplace:Width." + name;
                                var source = new LinkedHashMap<>(CoreModules.reachable(merged, entry)); source.put("instrument", true);
                                ExecutableProgram program = backend.equals("ast") ? new Program(language, source, true) : new BytecodeProgram(language, source, true); var target = program.entryTarget(entry);
                                checkRows(false, rows, index, program, target, language, locale + "/" + backend + "/" + name);
                                target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                                checkRows(true, rows, index, program, target, language, locale + "/" + backend + "/" + name);
                            }
                        } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
                    } finally { context.leave(); }
                }
            });
        }
    }
}
