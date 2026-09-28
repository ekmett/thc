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
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Executes unchanged installed GHC wrappers copied into real consumer Core. */
@SuppressWarnings("unchecked")
class OriginalStdioNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root,"build/original-stdio");
    private final List<String> names = OriginalStdioChecks.names;
    private Object json(File file) throws Exception { return Json.parse(Files.readString(file.toPath())); }
    private byte[] bytes(String hex) { return HexFormat.of().parseHex(hex); }
    private Map<String,Object> manifest() throws Exception {
        var manifest = (Map<String,Object>) json(new File(directory,"manifest.json"));
        assertEquals(1L,manifest.get("schema")); assertEquals(false,manifest.get("installedArtifactsHashed")); assertEquals(false,manifest.get("runtimeVerified"));
        assertEquals("full",manifest.get("mode")); assertEquals("9.14.1",manifest.get("ghc")); assertEquals(true,manifest.get("strictAccepted"));
        hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalStdioAudit.hs","t/fixtures/compiler/OriginalStdioAuditNative.hs","t/haskell-fixtures/Main.hs","t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/OriginalStdioFixtures.hs","bin/prepare-original-stdio.sh","thc.cabal","bin/audit-core.py","bin/core_original_foreign.py","bin/core-capabilities.json"));
        var required = new HashSet<>(Set.of("build/original-stdio/oracle.json"));
        for (var stage : List.of("pre","post")) {
            for (var part : List.of("OriginalStdioAudit","THC.InterfaceClosure")) required.add("build/original-stdio/" + stage + "/core/" + part + ".json");
            for (var name : names) required.add("build/original-stdio/" + stage + "/" + name + ".audit.json");
        }
        for (int i = 0; i < 144; i++) { required.add("build/original-stdio/results/" + i + ".txt");
            for (var extension : List.of("stdout","stderr","command.json")) required.add("build/original-stdio/logs/native-" + String.format(Locale.ROOT,"%03d",i) + "." + extension); }
        hashes(root,manifest.get("artifactHashes"),required,"build/original-stdio/"); assertEquals(names,manifest.get("entries")); assertEquals(144L,manifest.get("nativeRows"));
        var commands = (List<Map<String,Object>>) manifest.get("commands"); assertFalse(commands.isEmpty()); for (var command : commands) assertEquals(0L,command.get("exit")); return manifest;
    }
    private void valid(RootCallTarget target,String label) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target),label); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0,state.getArguments().getDepth()); assertEquals(0,state.getArguments().retainedReferences());
        assertEquals(0,state.getResults().getDepth()); assertEquals(0,state.getResults().retainedReferences()); assertNull(state.getPending());
    }
    @Test void originalInlinedCallsMatchNativeWithInlining() throws Exception { nativeChecks(true); }
    @Test void originalInlinedCallsMatchNativeAcrossResidualCalls() throws Exception { nativeChecks(false); }
    @Test void nativeDomainAndRawObservationsHaveIndependentExpectations() throws Exception {
        var rows = OriginalStdioChecks.rows(json(new File(directory,"oracle.json"))); assertEquals(144,rows.size()); assertEquals(144,new HashSet<>(rows).size());
        for (var name : names) for (var field : List.of("stdoutHex","stderrHex")) {
            boolean found = false; for (var row : rows) if (Objects.equals(row.get("entry"),name) && Objects.equals(row.get(field),hex(OriginalStdioChecks.payload))) { found = true; break; } assertTrue(found);
        }
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); var prefix = "logs/native-" + String.format(Locale.ROOT,"%03d",i);
            assertArrayEquals(bytes((String) row.get("stdoutHex")),Files.readAllBytes(new File(directory,prefix + ".stdout").toPath()));
            assertArrayEquals(bytes((String) row.get("stderrHex")),Files.readAllBytes(new File(directory,prefix + ".stderr").toPath()));
            assertEquals(row.get("result") + "\n",Files.readString(new File(directory,"results/" + i + ".txt").toPath()));
        }
    }
    @Test void missingDuplicateReorderedOrForgedNativeRowsReject() throws Exception {
        var rows = expectedRows(); var duplicate = new ArrayList<>(rows); duplicate.add(rows.getFirst());
        for (var bad : List.of(rows.subList(0,rows.size() - 1),duplicate,rows.reversed())) assertThrows(AssertionError.class,() -> OriginalStdioChecks.rows(bad));
        for (var edit : List.of(list("result",999L),list("stdoutHex","ff"),list("stderrHex","ff"),list("entry","syntheticWrite"),
                list("arguments",list(0L,0L,1L)),list("arguments",list(1L,-1L,1L)),list("arguments",list(1L,256L,1L)),list("arguments",list(1L,0L,-1L)),
                list("arguments",list(1L,0L,true)),list("arguments",list(1L,0L)),list("result",-1.0))) {
            var changed = new ArrayList<>(rows); changed.set(0,with(rows.getFirst(),edit.get(0),edit.get(1))); assertThrows(AssertionError.class,() -> OriginalStdioChecks.rows(changed));
        }
    }
    private Map<String,Object> module() throws Exception { return (Map<String,Object>) json(new File(directory,"pre/core/OriginalStdioAudit.json")); }
    private List<Object> expression(Map<String,Object> module) {
        for (var binding : (List<Map<String,Object>>) module.get("bindings")) if (Objects.equals(binding.get("name"),names.getFirst())) return (List<Object>) binding.get("expr");
        throw new NoSuchElementException();
    }
    @Test void originalConsumerStructureAndAuditMutationsReject() throws Exception {
        List<Consumer<Map<String,Object>>> mutations = List.of(
            it -> ((List<Map<String,Object>>) expression(it).get(1)).get(0).put("rep",OriginalStdioFixtures.scalar("WordRep")),
            it -> ((List<Object>) expression(it).get(2)).set(3,list(0L)),
            it -> ((List<Map<String,Object>>) ((List<?>) ((List<?>) expression(it).get(2)).get(1)).get(1)).get(0).put("type","State# Forged"),
            it -> foreignCalls(expression(it)).getFirst().set(1,list("var","unproved")),
            it -> ((Map<String,Object>) ((Map<?,?>) foreignCalls(expression(it)).getFirst().get(6)).get("foreignCall")).put("safety","safe"),
            it -> ((List<Object>) it.get("bindings")).add(((List<?>) it.get("bindings")).getFirst()));
        for (var mutate : mutations) { var changed = module(); mutate.accept(changed); var failure = assertThrows(Throwable.class,() -> OriginalStdioChecks.module(changed)); assertTrue(failure instanceof AssertionError || failure instanceof RuntimeFault,failure.toString()); }
        var owner = "main:OriginalStdioAudit.originalWrite"; var symbols = OriginalStdioChecks.module(module()).get(owner); var audit = (Map<String,Object>) json(new File(directory,"pre/originalWrite.audit.json"));
        audit(audit,owner,symbols);
        for (var edit : List.of(list("accepted",false),list("roots",list("other")),list("issues",list(map("code","unsupported-primitive"))),list("missingGlobals",list(map("id","other"))),
            list("reachableBindings",list(map("id",owner),map("id","helper"))),list("foreignCalls",List.of()))) assertThrows(AssertionError.class,() -> audit(with(audit,edit.get(0),edit.get(1)),owner,symbols));
    }
    @Test void provenanceRejectsMissingTamperedAndEscapingInputs(@TempDir File temporary) throws Exception {
        var file = new File(temporary,"source.hs"); Files.writeString(file.toPath(),"native source"); var hash = hex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
        var inputs = Map.of("source.hs",hash); hashes(temporary,inputs,inputs.keySet());
        assertThrows(AssertionError.class,() -> hashes(temporary,Map.of(),inputs.keySet()));
        assertThrows(AssertionError.class,() -> hashes(temporary,Map.of("../source.hs",hash),Set.of())); assertThrows(AssertionError.class,() -> hashes(temporary,Map.of(file.getPath(),hash),Set.of()));
        Files.writeString(file.toPath()," changed",StandardOpenOption.APPEND); assertThrows(AssertionError.class,() -> hashes(temporary,inputs,inputs.keySet()));
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest(); var oracle = OriginalStdioChecks.rows(json(new File(directory,"oracle.json"))); var rows = new LinkedHashMap<String,List<Map<String,Object>>>();
        for (var row : oracle) rows.computeIfAbsent((String) row.get("entry"),ignored -> new ArrayList<>()).add(row); assertEquals(new HashSet<>(names),rows.keySet());
        var payload = bytes((String) manifest.get("payloadHex")); byte[] expectedPayload = new byte[256]; for (int i = 0; i < 256; i++) expectedPayload[i] = (byte) i; assertArrayEquals(expectedPayload,payload);
        var stages = (Map<String,Map<String,Object>>) manifest.get("stages"); var audits = (Map<String,Map<String,String>>) manifest.get("audits"); assertEquals(Set.of("pre","post"),stages.keySet()); assertEquals(stages.keySet(),audits.keySet());
        for (var stageEntry : stages.entrySet()) {
            var stage = stageEntry.getKey(); var settings = stageEntry.getValue(); var paths = (List<String>) settings.get("modules"); var expectedPaths = new ArrayList<String>();
            for (var part : List.of("OriginalStdioAudit","THC.InterfaceClosure")) expectedPaths.add("build/original-stdio/" + stage + "/core/" + part + ".json"); assertEquals(expectedPaths,paths);
            var modules = new ArrayList<Map<String,Object>>(); for (var path : paths) modules.add((Map<String,Object>) json(new File(root,path))); var module = CoreModules.merge(modules);
            var calls = OriginalStdioChecks.module(module); assertEquals(new HashSet<>(names),audits.get(stage).keySet());
            for (var name : names) { var path = "build/original-stdio/" + stage + "/" + name + ".audit.json"; assertEquals(path,audits.get(stage).get(name)); var owner = "main:OriginalStdioAudit." + name; audit((Map<String,Object>) json(new File(root,path)),owner,calls.get(owner)); }
            for (var name : names) for (var backend : List.of("ast","bytecode")) {
                var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
                try (var context = Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors).allowExperimentalOptions(true)
                    .option("compiler.Inlining",Boolean.toString(inlining)).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = with(CoreModules.reachable(module,name),"instrument",true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language,linked) : new BytecodeProgram(language,linked); var entry = program.entryTarget(name); var address = ManagedAddress.fromByteArray(payload.clone());
                        var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                        class Check { void row(Map<String,Object> row) {
                            output.reset(); errors.reset(); var args = new ArrayList<Long>(); for (var value : (List<Number>) row.get("arguments")) args.add(value.longValue()); assertEquals(3,args.size());
                            long fd = args.get(0), offset = args.get(1), count = args.get(2);
                            assertEquals(row.get("result"),Calls.target(entry,new Object[]{0L,fd,address,offset,count}),label + "/" + args);
                            assertArrayEquals(bytes((String) row.get("stdoutHex")),output.toByteArray(),label + " stdout/" + args); assertArrayEquals(bytes((String) row.get("stderrHex")),errors.toByteArray(),label + " stderr/" + args);
                            assertArrayEquals(payload,address.cbitsBacking(),label + " immutable input/" + args); released(language);
                        }}
                        var check = new Check(); var cases = rows.get(name); assertEquals(36,cases.size()); for (var row : cases) check.row(row); var active = targets(entry);
                        for (var target : active) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target,label + " installed"); }
                        for (var row : cases) { long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.row(row); long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertTrue(after > before,label + " entered compiled code"); assertEquals(active,targets(entry),label + " retained target identities"); for (var target : active) valid(target,label + " first-installed target remains valid"); }
                        for (var counter : List.of("unsupportedTraps","blackholes")) assertEquals(0L,((Number) program.diagnostics().get(counter)).longValue(),label + "/" + counter);
                    } finally { context.leave(); }
                }
            }
        }
    }
}
