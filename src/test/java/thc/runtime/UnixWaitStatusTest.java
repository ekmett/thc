// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.Main.withContextProfile;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class UnixWaitStatusTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/unix-wait-status");
    private final List<OriginalStdioOp> operations = new ArrayList<>();
    UnixWaitStatusTest() { for (var operation : OriginalStdioOp.values()) if (operation.getWaitStatus()) operations.add(operation); }
    private Map<String,Object> json(String name) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(directory,name).toPath())); }
    private record Row(String name, long input, long result) {}
    private List<Row> rows() throws Exception {
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(directory,"oracle.tsv").toPath())) {
            var fields = line.split("\t", -1); assertEquals(3, fields.length); rows.add(new Row(fields[0],Long.parseLong(fields[1]),Long.parseLong(fields[2])));
        }
        assertEquals(280,rows.size()); var groups = grouped(rows); assertEquals(7, groups.size()); for (var corpus : groups.values()) assertEquals(40, corpus.size()); return rows;
    }
    private Map<String,List<Row>> grouped(List<Row> rows) { var groups = new LinkedHashMap<String,List<Row>>(); for (var row : rows) groups.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row); return groups; }
    private Context context() { return context(true,true); }
    private Context context(boolean inlining, boolean nativeAccess) { return withContextProfile(Context.newBuilder("thc").allowNativeAccess(nativeAccess), ContextProfile.SYNCHRONOUS_TEST).option("compiler.Inlining",Boolean.toString(inlining)).build(); }
    private ExecutableProgram program(Language language,Map<String,Object> source,String backend) { return backend.equals("ast") ? new Program(language,source) : new BytecodeProgram(language,source); }
    private void compiled(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    @Test void originalCallsMatchNativeWithInlining() throws Exception { nativeChecks(true); }
    @Test void originalCallsMatchNativeAcrossResidualCalls() throws Exception { nativeChecks(false); }
    private void nativeChecks(boolean inlining) throws Exception {
        originalDeclarationsAndOracleStayExact();
        for (var stage : List.of("pre","post")) for (var backend : List.of("ast","bytecode")) try (var context = context(inlining,true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var group : grouped(rows()).entrySet()) {
                    var name = group.getKey(); var corpus = group.getValue(); var source = with(CoreModules.reachable(json(stage + ".json"),name),"instrument",true);
                    var bindings = (List<Map<String,Object>>) source.get("bindings"); assertEquals(1,bindings.size());
                    int lambdas = 0; for (var node : nodes(bindings.getFirst().get("expr"))) if (!node.isEmpty() && Objects.equals(node.getFirst(),"lam")) lambdas++; assertEquals(1,lambdas);
                    var program = program(language,source,backend); var entry = program.entryValue(name); var host = program.hostEntryTarget(1);
                    var targets = new ArrayList<RootCallTarget>(); for (var binding : bindings) targets.add(program.entryTarget((String) binding.get("id")));
                    class Check { void call(Row row) { assertEquals(row.result(),Calls.target(host,new Object[]{entry,new Object[]{row.input()}}),stage + "/" + backend + "/inlining=" + inlining + "/" + name + "/" + row.input()); }}
                    var check = new Check(); for (var row : corpus) check.call(row);
                    for (var target : targets.reversed()) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); compiled(target); }
                    for (var row : corpus.reversed()) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.call(row);
                        assertEquals(before + 1,((Number) program.diagnostics().get("compiledEntries")).longValue(),"first and subsequent installed calls: " + stage + "/" + backend + "/" + name);
                        for (var target : targets) compiled(target);
                    }
                    assertEquals(0L,((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0,language.getHandoffState().get().getArguments().getDepth()); assertEquals(0,language.getHandoffState().get().getResults().getDepth());
                }
            } finally { context.leave(); }
        }
    }
    private String symbol(OriginalStdioOp operation,String owner) { return operation.getSymbol().replace("unixzm2zi8zi8zi0zminplace",owner.replace("-","zm").replace(".","zi")); }
    @Test void originalDeclarationsAndOracleStayExact() throws Exception {
        var manifest = json("manifest.json"); assertEquals("9.14.1",manifest.get("ghc")); assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest.get("unixUnit")));
        assertEquals(280L,manifest.get("nativeRows")); assertEquals(true,manifest.get("strictAccepted")); var owner = (String) manifest.get("unixUnit");
        var entries = new HashSet<String>(); for (var operation : operations) entries.add("wait" + operation.name()); assertEquals(entries,new HashSet<>((List<?>) manifest.get("entries")));
        assertEquals(Set.of("compiler/test-fixtures/UnixWaitStatusAudit.hs","test/haskell-fixtures/UnixWaitStatusFixtures.hs","compiler/THC/Plugin.hs","test/haskell-fixtures/FixtureSupport.hs","scripts/core_original_foreign.py","scripts/audit-core.py","scripts/core-capabilities.json","src/main/c/wait-status-api.c","scripts/build-cbits.py"),((Map<?,?>) manifest.get("inputHashes")).keySet());
        for (var key : List.of("inputHashes","artifactHashes","interfaceHashes")) {
            var hashes = (Map<String,String>) manifest.get(key); assertFalse(hashes.isEmpty());
            for (var hash : hashes.entrySet()) { var path = hash.getKey(); var file = new File(path); if (!file.isAbsolute()) file = new File(root,path);
                assertEquals(hash.getValue(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))),path); }
        }
        for (var stage : List.of("pre","post")) {
            var calls = foreignApps(json(stage + ".json")); var expected = new HashSet<String>(); for (var operation : operations) expected.add(symbol(operation,owner));
            var actual = new HashSet<Object>(); for (var call : calls) actual.add(target(call).get("symbol")); assertEquals(expected,actual);
            for (var call : calls) assertEquals(owner,target(call).get("unit"));
            for (var operation : operations) { var audit = json(stage + "-wait" + operation.name() + ".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("missingGlobals")); assertEquals(List.of(),audit.get("issues")); }
        }
        var corpus = new HashMap<List<Object>,Long>(); for (var row : rows()) corpus.put(list(row.name(),row.input()),row.result());
        // Actual C macro values: WCOREDUMP is the raw 0x80 mask.
        assertEquals(128L,corpus.get(list("waitWCOREDUMP",137L))); assertEquals(9L,corpus.get(list("waitWTERMSIG",137L)));
        assertEquals(255L,corpus.get(list("waitWEXITSTATUS",0xff00L))); assertEquals(127L,corpus.get(list("waitWSTOPSIG",0x7f7fL)));
        assertEquals(1L,corpus.get(list("waitWIFSTOPPED",0x7f7fL))); assertEquals(0L,corpus.get(list("waitWIFSIGNALED",0xffffL)));
        assertEquals(corpus.get(list("waitWIFEXITED",0L)),corpus.get(list("waitWIFEXITED",4294967296L)));
    }
    private Map<String,Object> target(List<Object> call) { return (Map<String,Object>) ((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target"); }
    private List<List<Object>> foreignApps(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?,?> map) for (var child : map.values()) result.addAll(foreignApps(child));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && Objects.equals(list.getFirst(),"app") && list.size() > 6 && list.get(6) instanceof Map<?,?> metadata && metadata.containsKey("foreignCall")) result.add((List<Object>) list);
            for (var child : list) result.addAll(foreignApps(child));
        }
        return result;
    }
    @Test void exactOriginalUnitConventionStateAndWidthAreRequired() throws Exception {
        for (var backend : List.of("ast","bytecode")) for (var variant : List.of("unit","safety","convention","arity","width","result","head","flags")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var source = CoreModules.reachable(json("post.json"),"waitWCOREDUMP");
                var app = single(foreignApps(source),ignored -> true); var call = (Map<String,Object>) ((Map<?,?>) app.get(6)).get("foreignCall");
                switch (variant) {
                    case "unit" -> ((Map<String,Object>) call.get("target")).put("unit","ghc-internal"); case "safety" -> call.put("safety","safe");
                    case "convention" -> call.put("convention","ccall"); case "arity" -> call.put("suppliedArity",1L);
                    case "width" -> ((List<Map<String,Object>>) call.get("argumentReps")).get(0).put("primReps",list("IntRep"));
                    case "result" -> ((Map<String,Object>) call.get("resultRep")).put("primReps",list("Word32Rep"));
                    case "head" -> ((Map<String,Object>) ((List<?>) app.get(1)).get(2)).put("rep",map("kind","long","primReps",list("IntRep"),"evaluated",true));
                    case "flags" -> ((List<Object>) app.get(3)).set(0,true);
                }
                assertThrows(RuntimeFault.class,() -> program(language,source,backend),backend + "/" + variant);
            } finally { context.leave(); }
        }
    }
    @Test void installedSymbolsKeepExactOwnerIndexModuleAndFunction() throws Exception {
        for (var operation : operations) for (var backend : List.of("ast","bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Control { Map<String,Object> module(String owner,String symbol) throws Exception {
                    var source = CoreModules.reachable(json("post.json"),"wait" + operation.name()); var target = target(single(foreignApps(source),ignored -> true)); target.put("unit",owner); target.put("symbol",symbol); return source;
                }}
                var control = new Control(); for (var owner : List.of("unix-2.8.8.0-inplace","unix-2.8.8.0-460b","unix-2.8.8.0-deadbeef")) program(language,control.module(owner,symbol(operation,owner)),backend);
                var owner = "unix-2.8.8.0-460b";
                for (var bad : List.of(List.of("unix-2.8.8.0-inplace",symbol(operation,owner)),List.of(owner,operation.getSymbol()),
                    List.of("unix-2.8.8.0-ABCD",symbol(operation,"unix-2.8.8.0-ABCD")),List.of("unix-2.8.8.0-nothex",symbol(operation,"unix-2.8.8.0-nothex")),List.of("unix-2.8.7.0-460b",symbol(operation,"unix-2.8.7.0-460b")),
                    List.of(owner,symbol(operation,owner).replace("ghczuwrapperZC" + operations.indexOf(operation) + "ZC","ghczuwrapperZC7ZC")),List.of(owner,symbol(operation,owner).replace("ProcessziInternals","ProcessziByteString")),List.of(owner,symbol(operation,owner) + "Extra")))
                    assertThrows(RuntimeFault.class,() -> program(language,control.module(bad.get(0),bad.get(1)),backend));
            } finally { context.leave(); }
        }
    }
    @Test void storedOperandsAndStateCarrierRemainCheckedBeforeNativeAccess() throws Exception {
        for (var backend : List.of("ast","bytecode")) try (var context = context(true,false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var operation : operations) {
                    var source = CoreModules.reachable(json("post.json"),"wait" + operation.name()); var call = single(foreignApps(source),ignored -> true);
                    for (int i = 0; i <= 1; i++) { int index = i; assertThrows(RuntimeFault.class,() -> program(language,rawModule(call,source,index),backend)); }
                    var target = program(language,rawModule(call,source),backend).entryTarget("entry");
                    assertThrows(RuntimeFault.class,() -> Calls.target(target,new Object[]{0L,137L,9L}));
                    var failure = assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,new Object[]{0L,137,9L}));
                    assertTrue(Objects.toString(failure.getMessage(),"").contains("zero-width scalar carrier"),failure.getMessage());
                    var shadowed = rawModule(call,source); var app = single(foreignApps(shadowed),ignored -> true); app.set(1,list("var","p0",((List<?>) app.get(1)).get(2)));
                    assertThrows(RuntimeFault.class,() -> program(language,shadowed,backend));
                }
                assertEquals(0,language.getHandoffState().get().getArguments().getDepth()); assertEquals(0,language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    @Test void statusTransportUsesCurrentNativeCapability() {
        try (var context = context(true,false)) { context.initialize("thc"); context.enter();
            try { assertThrows(RuntimeFault.class,() -> CoreOriginalStdio.waitStatus(null,OriginalStdioOp.WCOREDUMP,137)); } finally { context.leave(); } }
        for (int i = 0; i < 2; i++) try (var context = context()) { context.initialize("thc"); context.enter();
            try { assertEquals(128L,CoreOriginalStdio.waitStatus(null,OriginalStdioOp.WCOREDUMP,137)); assertThrows(RuntimeFault.class,() -> CoreOriginalStdio.waitStatus(null,OriginalStdioOp.F_GETFL,0)); } finally { context.leave(); } }
    }
}
