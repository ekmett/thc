// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch",matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalFcntlTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-fcntl";
    private final List<String> names = List.of("originalAppend","originalCreat","originalNoctty","originalNonblock","originalRdonly","originalRdwr","originalWronly","originalGetfl","originalSetfl","originalExcl","originalBinary","originalTrunc","originalGetFlags","originalSetFlags");
    private final List<OriginalStdioOp> constants = List.of(OriginalStdioOp.O_APPEND,OriginalStdioOp.O_CREAT,OriginalStdioOp.O_NOCTTY,OriginalStdioOp.O_NONBLOCK,OriginalStdioOp.O_RDONLY,OriginalStdioOp.O_RDWR,OriginalStdioOp.O_WRONLY,OriginalStdioOp.F_GETFL,OriginalStdioOp.F_SETFL,OriginalStdioOp.O_EXCL,OriginalStdioOp.O_BINARY,OriginalStdioOp.O_TRUNC);
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> source(String stage) throws Exception { var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalFcntlAudit","THC.InterfaceClosure")) modules.add(json(prefix + "/" + stage + "/core/" + part + ".json")); return CoreModules.merge(modules); }
    private Context context() { return NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST); }
    private void valid(RootCallTarget target) throws Exception { valid(target,target.getRootNode().getName()); }
    private void valid(RootCallTarget target,String label) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target),label); }
    private void compile(RootCallTarget target) throws Exception { compile(target,target.getRootNode().getName()); }
    private void compile(RootCallTarget target,String label) throws Exception { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target,label + " immediately after compilation"); }
    private void released(Language language) { var handoff = language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getArguments().retainedReferences()); assertEquals(0,handoff.getResults().retainedReferences()); }
    private OriginalStdioOp validate(List<Object> call) { var reps = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var metadata = CoreRepresentations.metadata(arg); reps.add(metadata == null ? null : metadata.get("rep")); } return CoreOriginalStdio.validate(call.get(6),reps,(List<?>) call.get(3),((Map<?,?>) call.get(6)).get("rep")); }
    private ExecutableProgram program(String backend,Language language,Map<String,Object> module) { return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); }
    private Object[] packet(Object[] args) { var values = new Object[args.length + 1]; values[0] = 0L; System.arraycopy(args,0,values,1,args.length); return values; }
    @Test void genuineOriginalCallsMatchNativeFlagsAndAliasesInBothCompiledBackends() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(true,manifest.get("supported")); assertEquals("linux",manifest.get("platform")); assertEquals(names,manifest.get("entries")); assertEquals(true,manifest.get("strictAccepted")); assertEquals(false,manifest.get("runtimeVerified")); assertEquals(4L,manifest.get("nativeRows"));
        hashes(root,manifest.get("inputHashes"),Set.of("compiler/test-fixtures/OriginalFcntlAudit.hs","compiler/test-fixtures/OriginalFcntlNative.hs","test/haskell-fixtures/OriginalStdioFixtures.hs","scripts/core_original_foreign.py","scripts/core-capabilities.json"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json",prefix + "/native/oracle")); for (var stage : List.of("pre","post")) { for (var name : names) artifacts.add(prefix + "/" + stage + "/" + name + ".audit.json"); for (var part : List.of("OriginalFcntlAudit","THC.InterfaceClosure")) artifacts.add(prefix + "/" + stage + "/core/" + part + ".json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var oracle = json(prefix + "/oracle.json"); var expected = (List<Long>) oracle.get("constants"); var rows = (List<List<Long>>) oracle.get("rows"); var abi = StdioHostAbi.load(); var actual = new ArrayList<Long>(); for (var constant : constants) actual.add(abi.flagConstant(constant)); assertEquals(actual,expected); assertEquals(List.of(-1L,abi.error(4)),oracle.get("invalid"));
        for (var stage : List.of("pre","post")) {
            var module = source(stage); var expectedOps = new HashSet<>(constants); expectedOps.addAll(List.of(OriginalStdioOp.FCNTL_READ,OriginalStdioOp.FCNTL_WRITE)); var actualOps = new HashSet<OriginalStdioOp>(); for (var call : foreignCalls(module)) actualOps.add(Objects.requireNonNull(validate(call))); assertEquals(expectedOps,actualOps);
            for (var name : names) { var audit = json(prefix + "/" + stage + "/" + name + ".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); }
            for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var executable = program(backend,language,with(module,"instrument",true)); var entries = new LinkedHashMap<String,RootCallTarget>(); for (var name : names) entries.put(name,executable.entryTarget(name));
                    var state = Language.currentState(); var file = directory.resolve(stage + "-" + backend); Files.writeString(file,"abc"); var path = ManagedAddress.fromByteArray((file + "\0").getBytes(StandardCharsets.UTF_8)); long fd = state.getStdio().open(path,abi.flagConstant(OriginalStdioOp.O_RDWR),0); assertTrue(fd >= 3); long alias = state.getStdio().duplicate(fd);
                    class Exercise {
                        boolean compiled; List<RootCallTarget> installed = List.of();
                        long call(String name,Object... arguments) throws Exception { long before = ((Number) executable.diagnostics().get("compiledEntries")).longValue(); long result = (Long) Calls.target(entries.get(name),packet(arguments)); if (compiled) { assertTrue(((Number) executable.diagnostics().get("compiledEntries")).longValue() > before); for (var target : installed) valid(target); } released(language); return result; }
                        void run() throws Exception {
                            for (int i = 0; i < constants.size(); i++) assertEquals(expected.get(i) + 7L,call(names.get(i),7L));
                            for (var row : rows) { assertEquals(row.get(1),call("originalSetFlags",fd,row.get(0))); assertEquals(row.get(2),call("originalGetFlags",alias),"status belongs to the shared open description"); }
                            assertEquals(-1L,call("originalGetFlags",-1L)); assertEquals(abi.error(4),state.getStdio().errno()); assertEquals(0L,call("originalSetFlags",alias,rows.getFirst().get(0))); assertEquals(abi.error(4),state.getStdio().errno(),"success preserves sticky errno");
                        }
                    }
                    var exercise = new Exercise(); exercise.run(); var installed = new LinkedHashSet<RootCallTarget>(); for (var target : entries.values()) installed.addAll(targets(target)); exercise.installed = new ArrayList<>(installed); for (var target : exercise.installed) compile(target); exercise.compiled = true; exercise.run();
                    assertEquals(0L,state.getStdio().close(fd)); assertEquals(rows.getFirst().get(2),exercise.call("originalGetFlags",alias)); assertEquals(-1L,exercise.call("originalGetFlags",fd)); assertEquals(0L,state.getStdio().close(alias)); assertEquals("abc",Files.readString(file)); assertEquals(0L,((Number) executable.diagnostics().get("unsupportedTraps")).longValue());
                } finally { context.leave(); }
            }
        }
    }
    @Test void exactWidthsStateAndCommandBoundariesRejectBeforeEffects() throws Exception {
        var module = source("post"); var calls = new ArrayList<List<Object>>(); var seen = new HashSet<OriginalStdioOp>(); for (var call : foreignCalls(module)) if (seen.add(validate(call))) calls.add(call);
        for (var call : calls) {
            var original = Objects.requireNonNull(validate(call));
            for (var edit : List.of(list("safety","safe"),list("convention","prim"))) { var changed = (List<Object>) Json.parse(Json.stringify(call)); ((Map<String,Object>) ((Map<?,?>) changed.get(6)).get("foreignCall")).put((String) edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,() -> validate(changed)); }
            if (original.getFlagConstant()) for (var unit : List.of("main","unix-2.8.8.0-inplace","ghc-internal-9.1401.0-inplace")) { var changed = (List<Object>) Json.parse(Json.stringify(call)); ((Map<String,Object>) ((Map<?,?>) ((Map<?,?>) changed.get(6)).get("foreignCall")).get("target")).put("unit",unit); assertThrows(RuntimeFault.class,() -> validate(changed)); }
            for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var raw = rawModule(call,module); var executable = program(backend,language,raw); var target = executable.entryTarget("entry"); var state = Language.currentState(); var abi = StdioHostAbi.load();
                    var path = directory.resolve("raw-" + backend + "-" + original.name()); Files.writeString(path,"abc"); long fd = state.getStdio().open(ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8)),abi.flagConstant(OriginalStdioOp.O_RDWR),0);
                    long before = state.getStdio().fcntl(fd,abi.flagConstant(OriginalStdioOp.F_GETFL),0,false);
                    Object[] args = switch (original) { case FCNTL_READ -> new Object[]{(int) fd,(int) abi.flagConstant(OriginalStdioOp.F_GETFL),thc.runtime.Unit.INSTANCE}; case FCNTL_WRITE -> new Object[]{(int) fd,(int) abi.flagConstant(OriginalStdioOp.F_SETFL),before | abi.flagConstant(OriginalStdioOp.O_APPEND),thc.runtime.Unit.INSTANCE}; default -> new Object[]{thc.runtime.Unit.INSTANCE}; };
                    callScalarTestTarget(target,packet(args)); assertEquals(0L,state.getStdio().fcntl(fd,abi.flagConstant(OriginalStdioOp.F_SETFL),before,true)); var label = backend + "/" + original.name() + " raw foreign entry"; compile(target,label);
                    long count = ((Number) executable.diagnostics().get("compiledEntries")).longValue(); var result = callScalarTestTarget(target,packet(args)); assertEquals(count + 1,((Number) executable.diagnostics().get("compiledEntries")).longValue(),label + " first installed invocation must enter compiled code"); valid(target,label + " after first installed invocation (result=" + result + ")"); released(language);
                    assertEquals(switch (original) { case FCNTL_READ -> (int) before; case FCNTL_WRITE -> 0; case FD_CLOEXEC -> (Object) abi.flagConstant(original); default -> (int) abi.flagConstant(original); },result);
                    long stable = state.getStdio().fcntl(fd,abi.flagConstant(OriginalStdioOp.F_GETFL),0,false); var badState = args.clone(); badState[badState.length - 1] = 9L; assertThrows(RuntimeFault.class,() -> Calls.target(target,packet(badState))); assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,packet(badState)));
                    if (original.getFcntl()) { var badWidth = args.clone(); badWidth[0] = 1L << 32; assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,packet(badWidth))); var wrongCommand = args.clone(); wrongCommand[1] = -7; assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,packet(wrongCommand))); }
                    for (int i = 0; i < original.getArguments().size(); i++) { int index = i; assertThrows(RuntimeFault.class,() -> program(backend,language,rawModule(call,module,index))); }
                    assertEquals(stable,state.getStdio().fcntl(fd,abi.flagConstant(OriginalStdioOp.F_GETFL),0,false)); assertEquals("abc",Files.readString(path)); released(language); assertEquals(0L,state.getStdio().close(fd));
                } finally { context.leave(); }
            }
        }
    }
    @Test void appendChangesAffectActualWritesAndPrivateExtensionPolicy() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var abi = StdioHostAbi.load(); var path = directory.resolve("append"); Files.writeString(path,"abc"); long fd = state.getStdio().open(ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8)),abi.flagConstant(OriginalStdioOp.O_RDWR),0);
                long get = abi.flagConstant(OriginalStdioOp.F_GETFL), set = abi.flagConstant(OriginalStdioOp.F_SETFL); assertEquals(-1L,state.getStdio().fcntl(1,get,0,false),"ungranted embedding stream is not a host fd"); assertEquals(abi.error(7),state.getStdio().errno());
                long original = state.getStdio().fcntl(fd,get,0,false); assertEquals(0L,state.getStdio().fcntl(fd,set,original | abi.flagConstant(OriginalStdioOp.O_APPEND),true)); assertEquals(1L,state.getStdio().write(fd,ManagedAddress.fromByteArray(new byte[]{90}),1)); assertEquals("abcZ",Files.readString(path));
                assertEquals(-1L,state.getFiles().setSize(fd,8)); assertEquals(0L,state.getStdio().fcntl(fd,set,original,true)); assertEquals(0L,state.getFiles().setSize(fd,8)); assertEquals(8L,Files.size(path)); assertEquals(0L,state.getStdio().close(fd));
            } finally { context.leave(); }
        }
    }
}
