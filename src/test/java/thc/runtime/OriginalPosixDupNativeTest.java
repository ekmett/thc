// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Native and context descriptors are separate namespaces. Compare allocation
 * roles and observable shared-file behavior without passing host fds to THC. */
@SuppressWarnings("unchecked")
class OriginalPosixDupNativeTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File fixture = new File(root,"build/original-posix-dup");
    private final Map<String,List<String>> requests = new LinkedHashMap<>();
    OriginalPosixDupNativeTest() {
        requests.put("originalDup",List.of("shared","close-source","append","lowest0","lowest1","lowest2","invalid","closed"));
        requests.put("originalDupErrno",List.of("invalid","closed")); requests.put("originalDup2",List.of("replace","self","alias","invalid","closed","bad-target")); requests.put("originalDup2Errno",List.of("invalid","closed","bad-target"));
    }
    private Map<String,Object> json(File file) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(file.toPath())); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private ManagedAddress address(byte[] bytes) { return ManagedAddress.fromByteArray(bytes); }
    private ManagedAddress path(Path file) { return address((file + "\0").getBytes(StandardCharsets.UTF_8)); }
    @Test void originalDuplicationMatchesNativeAndEveryFirstInstalledTarget() throws Exception {
        var manifest = json(new File(fixture,"manifest.json")); assertEquals(1L,manifest.get("schema")); assertEquals("9.14.1",manifest.get("ghc")); assertEquals(new ArrayList<>(requests.keySet()),manifest.get("entries"));
        assertEquals(19L,manifest.get("nativeRows")); assertEquals(true,manifest.get("strictAccepted")); assertEquals(false,manifest.get("runtimeVerified"));
        hashes(root,manifest.get("inputHashes"),Set.of("test/fixtures/compiler/OriginalPosixDupAudit.hs","test/fixtures/compiler/OriginalPosixDupAuditNative.hs","test/haskell-fixtures/OriginalPosixDupFixtures.hs","test/haskell-fixtures/Main.hs","thc.cabal","test/haskell-fixtures/FixtureSupport.hs","bin/audit-core.py","bin/core_original_foreign.py","bin/core-capabilities.json"));
        var labels = new ArrayList<>(List.of("ghc-version","ghc-info","native-build","pre-export","post-export")); for (int i = 0; i <= 18; i++) labels.add("native-" + i);
        for (var stage : List.of("pre","post")) for (var name : requests.keySet()) labels.add(stage + "-audit-" + name);
        var artifacts = new HashSet<>(Set.of("oracle.json","native/oracle"));
        for (int i = 0; i <= 18; i++) for (var extension : List.of("txt","private","other")) artifacts.add("results/" + i + "." + extension);
        for (var label : labels) for (var extension : List.of("stdout","stderr","command.json")) artifacts.add("logs/" + label + "." + extension);
        for (var stage : List.of("pre","post")) { artifacts.add(stage + "/core/OriginalPosixDupAudit.json"); artifacts.add(stage + "/core/THC.InterfaceClosure.json"); for (var name : requests.keySet()) artifacts.add(stage + "/" + name + ".audit.json"); }
        assertEquals(167,artifacts.size()); var paths = new HashSet<String>(); for (var path : artifacts) paths.add("build/original-posix-dup/" + path);
        hashes(root,manifest.get("artifactHashes"),paths,"build/original-posix-dup/"); assertEquals(167,((Map<?,?>) manifest.get("artifactHashes")).size());
        var oracle = (List<Map<String,Object>>) Json.parse(Files.readString(new File(fixture,"oracle.json").toPath())); var expectedKeys = new ArrayList<List<String>>(); for (var entry : requests.entrySet()) for (var scenario : entry.getValue()) expectedKeys.add(List.of(entry.getKey(),scenario));
        var actualKeys = new ArrayList<List<Object>>(); for (var row : oracle) actualKeys.add(list(row.get("entry"),row.get("scenario"))); assertEquals(expectedKeys,actualKeys); long ebadf = StdioHostAbi.load().error(4);
        for (int index = 0; index < oracle.size(); index++) {
            var row = oracle.get(index); var name = (String) row.get("entry"); var scenario = (String) row.get("scenario"); boolean failed = List.of("invalid","closed","bad-target").contains(scenario), two = name.startsWith("originalDup2");
            if (failed) assertEquals(name.endsWith("Errno") ? ebadf : -1L,row.get("result")); else if (two) assertEquals(row.get("target"),row.get("result"));
            else { assertTrue((Long) row.get("result") >= 0); assertNotEquals(row.get("source"),row.get("result")); if (scenario.startsWith("lowest")) assertEquals((long) Character.digit(scenario.charAt(scenario.length() - 1),10),row.get("result")); }
            assertEquals(ebadf,row.get("errno")); assertEquals(failed ? (two && !scenario.equals("bad-target") ? 2L : -1L) : scenario.equals("append") ? 7L : 3L,row.get("position"));
            assertEquals(failed ? (two && !scenario.equals("bad-target") ? 97L : -1L) : scenario.equals("append") ? -1L : 99L,row.get("byte"));
            var contents = new ArrayList<Long>(); for (long value = 97; value <= 102; value++) contents.add(value); if (scenario.equals("append")) contents.add(90L); assertEquals(contents,row.get("contents"));
            for (var stream : List.of("stdout","stderr")) { assertEquals("",row.get(stream + "Hex")); assertArrayEquals(new byte[0],Files.readAllBytes(new File(fixture,"logs/native-" + index + "." + stream).toPath())); }
            assertEquals(0L,json(new File(fixture,"logs/native-" + index + ".command.json")).get("exit"));
            var scalars = new StringJoiner(","); for (var field : List.of("result","source","target","position","byte","errno")) scalars.add(String.valueOf(row.get(field)));
            var renderedBytes = new StringJoiner(",","[","]"); for (var value : (List<?>) row.get("contents")) renderedBytes.add(String.valueOf(value));
            assertEquals("(" + scalars + "," + renderedBytes + ")\n",Files.readString(new File(fixture,"results/" + index + ".txt").toPath()));
            var values = (List<Long>) row.get("contents"); byte[] bytes = new byte[values.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = values.get(i).byteValue();
            assertArrayEquals(bytes,Files.readAllBytes(new File(fixture,"results/" + index + ".private").toPath())); assertEquals("target",Files.readString(new File(fixture,"results/" + index + ".other").toPath()));
        }
        for (var stage : List.of("pre","post")) {
            var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalPosixDupAudit","THC.InterfaceClosure")) modules.add(json(new File(fixture,stage + "/core/" + part + ".json"))); var module = CoreModules.merge(modules); var bindings = (List<Map<String,Object>>) module.get("bindings");
            for (var name : requests.keySet()) {
                var owner = "main:OriginalPosixDupAudit." + name; var calls = foreignCalls(single(bindings,b -> Objects.equals(b.get("id"),owner)).get("expr")); var symbols = new ArrayList<String>();
                for (var call : calls) { CoreOriginalStdio.validateHead((List<Object>) call.get(1),false); var reps = new ArrayList<Object>(); for (var arg : (List<List<?>>) call.get(2)) reps.add(((Map<?,?>) arg.getLast()).get("rep"));
                    symbols.add(Objects.requireNonNull(CoreOriginalStdio.validate(call.get(6),reps,(List<?>) call.get(3),((Map<?,?>) call.get(6)).get("rep"))).getSymbol()); }
                var expected = new ArrayList<>(List.of(name.startsWith("originalDup2") ? "dup2" : "dup")); if (name.endsWith("Errno")) expected.add("__hscore_get_errno"); var sorted = new ArrayList<>(symbols); Collections.sort(expected); Collections.sort(sorted); assertEquals(expected,sorted);
                audit(json(new File(fixture,stage + "/" + name + ".audit.json")),owner,symbols);
            }
            for (var backend : List.of("ast","bytecode")) for (boolean inlining : new boolean[]{false,true}) {
                var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
                try (var context = Context.newBuilder("thc").allowIO(IOAccess.ALL).in(new ByteArrayInputStream(new byte[0])).out(out).err(err).allowExperimentalOptions(true).option("compiler.Inlining",Boolean.toString(inlining))
                    .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var files = Language.currentState().getFiles(); var stdio = Language.currentState().getStdio();
                        for (var request : requests.entrySet()) {
                            var name = request.getKey(); var scenarios = request.getValue(); var linked = with(CoreModules.reachable(module,name),"instrument",true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language,linked) : new BytecodeProgram(language,linked); var entry = program.entryTarget(name);
                            record Backup(long wanted,long saved) {}
                            class Exercise { void run(boolean compiled) throws Exception {
                                for (var scenario : scenarios) {
                                    var row = single(oracle,r -> Objects.equals(r.get("entry"),name) && Objects.equals(r.get("scenario"),scenario));
                                    var file = directory.resolve(stage + "-" + backend + "-" + inlining + "-" + name + "-" + scenario + "-" + compiled); var otherFile = directory.resolve(file.getFileName() + ".other");
                                    Files.writeString(file,"abcdef"); Files.writeString(otherFile,"target"); long source = files.open(path(file),scenario.equals("append") ? 2 : 3,ForeignSafety.UNSAFE), other = files.open(path(otherFile),3,ForeignSafety.UNSAFE);
                                    assertTrue(source >= 3); assertTrue(other >= 3); assertEquals(2L,files.seek(source,2,0)); assertEquals(1L,files.seek(other,1,0));
                                    long target = switch (scenario) { case "self" -> source; case "alias" -> files.duplicate(source); case "bad-target" -> -1L; default -> other; };
                                    Backup backup = null; if (scenario.startsWith("lowest")) { long wanted = Character.digit(scenario.charAt(scenario.length() - 1),10); long saved = files.duplicate(wanted); assertEquals(0L,files.close(wanted)); backup = new Backup(wanted,saved); }
                                    if (scenario.equals("closed")) assertEquals(0L,files.close(source)); long fd = scenario.equals("invalid") ? -1L : source;
                                    boolean failed = List.of("invalid","closed","bad-target").contains(scenario), two = name.startsWith("originalDup2"); assertEquals(-1L,stdio.close(-1));
                                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); long result = (Long) Calls.target(entry,two ? new Object[]{0L,fd,target} : new Object[]{0L,fd}); assertEquals(row.get("errno"),stdio.errno());
                                    if (failed) assertEquals(row.get("result"),result); else if (two) assertEquals(target,result); else { assertTrue(result >= 0); assertNotEquals(source,result); if (backup != null) assertEquals(backup.wanted(),result); }
                                    if (compiled) { assertEquals(before + 1,((Number) program.diagnostics().get("compiledEntries")).longValue(),stage + "/" + backend + "/" + name + "/" + scenario); for (var active : targets(entry)) valid(active); }
                                    long alias = two ? target : result; if (scenario.equals("close-source")) assertEquals(0L,files.close(source)); long position = -1, value = -1;
                                    if (!failed || two && !scenario.equals("bad-target")) {
                                        if (scenario.equals("append")) { assertEquals(0L,files.seek(alias,0,0)); assertEquals(1L,files.write(alias,address(new byte[]{90}),1,ForeignSafety.UNSAFE)); }
                                        else { byte[] bytes = new byte[1]; assertEquals(1L,files.read(alias,address(bytes),1,ForeignSafety.UNSAFE)); value = bytes[0]; }
                                        position = files.seek(alias,0,1);
                                    }
                                    assertEquals(row.get("position"),position); assertEquals(row.get("byte"),value); if (backup != null) assertEquals(backup.wanted(),files.duplicateTo(backup.saved(),backup.wanted()));
                                    var close = new LinkedHashSet<>(List.of(source,other,target)); if (!failed) close.add(alias); if (backup != null) close.add(backup.saved()); for (long fdToClose : close) if (fdToClose >= 3) files.close(fdToClose);
                                    var actual = new ArrayList<Long>(); for (byte b : Files.readAllBytes(file)) actual.add((long) b); assertEquals(row.get("contents"),actual); assertEquals("target",Files.readString(otherFile));
                                }
                            }}
                            var exercise = new Exercise(); exercise.run(false); var active = targets(entry); assertEquals(1,active.size(),"Original entry with the runRW State lambda inlined");
                            for (var target : active) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); } exercise.run(true); assertEquals(active,targets(entry)); for (var target : active) valid(target);
                            assertEquals(0L,((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertEquals(0,out.size()); assertEquals(0,err.size()); var handoff = language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getArguments().retainedReferences()); assertEquals(0,handoff.getResults().retainedReferences());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
