// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.Main.withContextProfile;
import static thc.runtime.OriginalStdioChecks.*;

@SuppressWarnings("unchecked")
class UnixLibcTest {
    @TempDir Path temporary;
    private Map<String,Object> cbd(String path) throws Exception { return CoreCbdFixtures.read(new File(directory,path).toPath()); }
    private String entryId(String name) { return "main:UnixLibcAudit." + name; }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root,"build/unix-libc");
    private final Map<String,Object> entries = map("unixClose","close","unixDup","dup","unixIsatty","isatty","unixGetenv","getenv");
    private Map<String,Object> json(String name) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(directory,name).toPath())); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private ExecutableProgram program(Language language,Map<String,Object> module,String backend) { return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); }
    private static <T> T inside(Context context,Callable<T> action) throws Exception { context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); } }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void install(Collection<RootCallTarget> targets) throws Exception {
        var runtime = Truffle.getRuntime(); var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        for (var target : targets) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); runtime.getClass().getMethod("bypassedInstalledCode",type).invoke(runtime,target); valid(target); }
    }
    private ManagedAddress address(String text) { var bytes = text.getBytes(StandardCharsets.UTF_8); return ManagedAddress.fromByteArray(Arrays.copyOf(bytes,bytes.length + 1)); }
    private void released(Language language) { var state = language.getHandoffState().get(); assertEquals(0,state.getArguments().getDepth()); assertEquals(0,state.getResults().getDepth()); assertEquals(0,state.getArguments().retainedReferences()); assertEquals(0,state.getResults().retainedReferences()); }
    private void validate(List<Object> call) {
        var metadata = (Map<?,?>) call.get(6); var descriptor = (Map<?,?>) metadata.get("foreignCall"); var symbol = ((Map<?,?>) descriptor.get("target")).get("symbol");
        var arguments = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var rep = CoreRepresentations.metadata(arg); arguments.add(rep == null ? null : rep.get("rep")); }
        if (Objects.equals(symbol,"getenv")) assertEquals(EnvironmentOp.GET,CoreEnvironmentForeign.validate(metadata,arguments,(List<?>) call.get(3),metadata.get("rep")));
        else { var op = CoreOriginalStdio.validate(metadata,arguments,(List<?>) call.get(3),metadata.get("rep")); assertEquals(symbol,op == null ? null : op.getSymbol()); }
    }
    private void fixture() throws Exception {
        var manifest = json("manifest.json"); assertEquals("9.14.1",manifest.get("ghc")); var installedUnit = manifest.get("unixUnit"); assertTrue(CoreOriginalStdio.isOriginalUnixUnit(installedUnit));
        assertEquals(entries.keySet(),new HashSet<>((List<String>) manifest.get("entries")));
        for (var key : List.of("inputHashes","artifactHashes")) { var hashes = (Map<String,String>) manifest.get(key); assertFalse(hashes.isEmpty());
            for (var hash : hashes.entrySet()) { var file = new File(hash.getKey()); if (!file.isAbsolute()) file = new File(root,hash.getKey()); assertEquals(hash.getValue(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))),hash.getKey()); } }
        assertEquals(false,manifest.get("installedArtifactsHashed")); var interfaces = new ArrayList<String>();
        for (var path : (List<String>) manifest.get("interfaces")) { int at = path.lastIndexOf("/System/"); interfaces.add("System/" + (at < 0 ? path : path.substring(at + "/System/".length()))); }
        assertEquals(List.of("System/Posix/IO/Common.hi","System/Posix/Terminal/Common.hi","System/Posix/Env/PosixString.hi"),interfaces);
        var retained = (Map<?,?>) Json.parse(Files.readString(new File(root,"src/test/resources/core/original-unix-libc-descriptors.json").toPath()));
        for (var stage : List.of("pre","post")) {
            var module = cbd(stage + ".cbd"); var calls = foreignCalls(module); assertEquals(4,calls.size());
            for (var call : calls) {
                var actual = (Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall"); var target = (Map<?,?>) actual.get("target");
                assertEquals(installedUnit,target.get("unit"),"Preserve the original installed owner"); var expected = (Map<?,?>) retained.get(target.get("symbol"));
                assertEquals("unix-2.8.8.0-inplace",((Map<?,?>) expected.get("target")).get("unit"));
                // All ABI fields remain exact; only the recorded installation owner differs.
                assertEquals(without(expected,"target"),without(actual,"target")); assertEquals(without((Map<?,?>) expected.get("target"),"unit"),without(target,"unit"));
            }
            for (var call : calls) validate(call);
            for (var entry : entries.keySet()) { var audit = json(stage + "-" + entry + ".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals"));
                var proof = new ArrayCoreEvidence(module,entryId(entry)); assertEquals(1,proof.getBindings().size()); assertEquals(1,proof.guestLambdas(proof.getRoot().get("expr")).size(),"One original typed consumer root"); }
        }
    }
    @Test void originalUnixDeclarationsMatchNativeFileLifecycleOnFirstInstalledCalls() throws Exception {
        fixture(); var oracle = json("oracle.json"); var expected = (Map<?,?>) oracle.get("lifecycle");
        assertEquals(map("dupSucceeded",true,"closeSource",0L,"bytes","Z","count",1L,"isatty",0L,"closeAlias",0L,"closeAgain",-1L),expected);
        var invalid = (List<Map<String,Object>>) oracle.get("invalid"); assertEquals(15,invalid.size());
        for (var stage : List.of("pre","post")) for (var backend : List.of("ast","bytecode")) try (var context = NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST)) { inside(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var selected = new ArrayList<>(entries.keySet()); selected.remove("unixGetenv");
            var module = with(CoreModules.reachable(cbd(stage + ".cbd"),selected.stream().map(this::entryId).toList(),true),"instrument",true); var p = program(language,module,backend);
            var targets = new LinkedHashMap<String,RootCallTarget>(); for (var name : selected) targets.put(name,p.entryTarget(entryId(name))); var state = Language.currentState();
            class Exercise {
                boolean compiled;
                long call(String name,long input) throws Exception {
                    long before = ((Number) p.diagnostics().get("compiledEntries")).longValue(); if (compiled) for (var target : targets.values()) valid(target);
                    long answer = (Long) callScalarTestTarget(targets.get(name),new Object[]{0L,input});
                    if (compiled) { assertEquals(before + 1,((Number) p.diagnostics().get("compiledEntries")).longValue(),stage + "/" + backend + "/" + name + " first and subsequent entries"); for (var target : targets.values()) valid(target); }
                    released(language); return answer;
                }
                void run() throws Exception {
                    var file = Files.createTempFile(temporary,"unix-",".txt"); Files.write(file,new byte[]{90});
                    long fd = state.getFiles().open(address(file.toString()),0,ForeignSafety.UNSAFE); assertTrue(fd >= 3);
                    long alias = call("unixDup",fd); assertEquals(true,alias >= 3 && alias != fd); assertEquals(expected.get("closeSource"),call("unixClose",fd));
                    var output = ManagedAddress.fromByteArray(new byte[1]); assertEquals(expected.get("count"),state.getStdio().read(alias,output,1)); assertEquals(90L,output.readWord8(0));
                    assertEquals(expected.get("isatty"),call("unixIsatty",alias)); assertEquals(expected.get("closeAlias"),call("unixClose",alias)); assertEquals(expected.get("closeAgain"),call("unixClose",alias)); Files.delete(file);
                    for (var row : invalid) { assertEquals(row.get("result"),call((String) row.get("entry"),(Long) row.get("input"))); assertEquals(row.get("errno"),state.getStdio().errno()); }
                }
            }
            var exercise = new Exercise(); exercise.run(); install(targets.values()); exercise.compiled = true; exercise.run(); return null;
        }); }
    }
    @Test void originalUnixGetenvMatchesPresentEmptyAndMissingNativeValues() throws Exception {
        fixture(); var rows = (List<List<String>>) json("oracle.json").get("environment");
        assertEquals(list(list("THC_UNIX_FFI_VALUE","present-value"),list("THC_UNIX_FFI_EMPTY",""),list("THC_UNIX_FFI_ABSENT",null)),rows);
        for (var stage : List.of("pre","post")) for (var backend : List.of("ast","bytecode")) try (var context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true).allowEnvironmentAccess(EnvironmentAccess.NONE)
            .environment("THC_UNIX_FFI_VALUE","present-value").environment("THC_UNIX_FFI_EMPTY",""),ContextProfile.SYNCHRONOUS_TEST).build()) { inside(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = with(CoreModules.reachable(cbd(stage + ".cbd"),entryId("unixGetenv"),true),"instrument",true);
            var p = program(language,module,backend); var target = p.entryTarget(entryId("unixGetenv"));
            class Exercise { boolean compiled; void run() throws Exception {
                for (var row : rows) { var name = row.get(0); var expected = row.get(1); long before = ((Number) p.diagnostics().get("compiledEntries")).longValue();
                    var result = (ManagedAddress) callScalarTestTarget(target,new Object[]{0L,address(Objects.requireNonNull(name))}); String actual = null;
                    if (result != ManagedAddress.nullAddress()) { byte[] bytes = new byte[(int) result.cStringLength()]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) result.readWord8(i); actual = new String(bytes,StandardCharsets.UTF_8); }
                    assertEquals(expected,actual); if (compiled) { assertEquals(before + 1,((Number) p.diagnostics().get("compiledEntries")).longValue()); valid(target); } released(language);
                }
            }}
            var exercise = new Exercise(); exercise.run(); install(List.of(target)); exercise.compiled = true; exercise.run(); return null;
        }); }
    }
    @Test void exactUnixUnitAbiAndRawStateRemainRequired() throws Exception {
        fixture(); var source = cbd("pre.cbd");
        for (var call : foreignCalls(source)) {
            class Control { void reject(BiConsumer<List<Object>,Map<String,Object>> action) throws Exception {
                var changed = (List<Object>) copy(call); var descriptor = (Map<String,Object>) ((Map<?,?>) changed.get(6)).get("foreignCall"); action.accept(changed,descriptor);
                assertThrows(RuntimeFault.class,() -> validate(changed));
                for (var backend : List.of("ast","bytecode")) try (var context = Context.newBuilder("thc").build()) { inside(context,() -> {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var symbol = ((Map<?,?>) descriptor.get("target")).get("symbol");
                    Object argument = Objects.equals(symbol,"getenv") ? address("THC_UNIX_FFI_ABSENT") : -1L;
                    assertThrows(RuntimeFault.class,() -> callScalarTestTarget(program(language,rawModule(changed,source),backend)
                        .entryTarget("entry"),new Object[]{0L,argument,thc.runtime.Unit.INSTANCE}));
                    released(language); return null;
                }); }
            }}
            var control = new Control();
            for (var unit : List.of("unix","unix-2.8.8.1-inplace","unix-2.8.8.0-forged","unix-2.8.8.0","unix-2.8.8.0-","unix-2.8.8.0-460b-extra","other")) control.reject((a,d) -> ((Map<String,Object>) d.get("target")).put("unit",unit));
            for (var safety : List.of("safe","interruptible")) control.reject((a,d) -> d.put("safety",safety));
            control.reject((a,d) -> d.put("arity",1L)); control.reject((a,d) -> ((List<Map<String,Object>>) d.get("argumentReps")).get(0).put("primReps",list("WordRep")));
            control.reject((a,d) -> Collections.reverse((List<?>) ((Map<?,?>) d.get("resultRep")).get("components")));
            for (var backend : List.of("ast","bytecode")) try (var context = Context.newBuilder("thc").build()) { inside(context,() -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var p = program(language,rawModule(call,source),backend);
                var symbol = ((Map<?,?>) ((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target")).get("symbol"); Object argument = Objects.equals(symbol,"getenv") ? address("THC_UNIX_FFI_ABSENT") : -1L;
                assertThrows(RuntimeFault.class,() -> callScalarTestTarget(p.entryTarget("entry"),new Object[]{0L,argument,7L})); released(language); return null;
            }); }
        }
    }
}
