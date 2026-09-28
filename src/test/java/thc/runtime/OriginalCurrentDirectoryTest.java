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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Original Unix declarations and independent native child observations. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named="os.arch",matches="amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalCurrentDirectoryTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-current-directory";
    private final Map<String,OriginalStdioOp> operations = new LinkedHashMap<>();
    OriginalCurrentDirectoryTest() { operations.put("pathChdir",OriginalStdioOp.CHDIR); operations.put("pathGetCwd",OriginalStdioOp.GETCWD); }
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> source(String stage) throws Exception { return json(prefix + "/" + stage + ".json"); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private ManagedAddress address(byte[] bytes) { return ManagedAddress.fromByteArray(Arrays.copyOf(bytes,bytes.length + 1)); }
    private ManagedAddress address(String value) { return address(value.getBytes(StandardCharsets.UTF_8)); }
    private ManagedAddress pathAddress(Path path) { return ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path)); }
    private byte[] bytes(Map<String,Object> row,String key) { var values = (List<Long>) row.get(key); byte[] result = new byte[values.size()]; for (int i=0;i<result.length;i++) result[i]=values.get(i).byteValue(); return result; }
    private byte[] concat(byte[]... arrays) { int size=0; for(var a:arrays) size+=a.length; byte[] result=new byte[size]; int offset=0; for(var a:arrays) { System.arraycopy(a,0,result,offset,a.length); offset+=a.length; } return result; }
    private Path rawPath(Path base,byte[] value) { var path=NativeDirectoryOwner.pathBytes(base); return NativeDirectoryOwner.bytesPath(concat(Arrays.copyOf(path,path.length-1),new byte[]{47},value)); }
    private Context context() { return NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST); }
    private ExecutableProgram program(Language language,String backend,Map<String,Object> module) { return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<Object> original(String name) throws Exception { return original(name,"post"); }
    private List<Object> original(String name,String stage) throws Exception { return single(foreignCalls(CoreModules.reachable(source(stage),name)),ignored -> true); }
    private Map<String,Object> raw(List<Object> call) throws Exception { return rawModule(call,source("post")); }
    private OriginalStdioOp validate(List<Object> call) { var reps=new ArrayList<Object>(); for(var arg:(List<List<Object>>)call.get(2)) { var metadata=CoreRepresentations.metadata(arg); reps.add(metadata==null?null:metadata.get("rep")); } return CoreOriginalStdio.validate(call.get(6),reps,(List<?>)call.get(3),((Map<?,?>)call.get(6)).get("rep")); }
    private void released(Language language) { var handoff=language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getArguments().retainedReferences()); assertEquals(0,handoff.getResults().retainedReferences()); }
    private Path setup() throws Exception {
        var base=Files.createTempDirectory(directory,"case-"); Files.createDirectories(base.resolve("parent/child")); Files.createDirectory(base.resolve("sub")); Files.writeString(base.resolve("regular"),"unchanged"); Files.createDirectory(rawPath(base,new byte[]{-1,110})); Files.createSymbolicLink(base.resolve("link"),Path.of("sub")); Files.createDirectory(base.resolve("search-denied")); Files.setPosixFilePermissions(base.resolve("search-denied"),Set.of()); return base;
    }
    private byte[] name(ManagedStdio stdio) { byte[] storage=new byte[65536]; var output=ManagedAddress.fromByteArray(storage); assertSame(output,stdio.currentDirectory(output,storage.length)); int end=0; while(end<storage.length&&storage[end]!=0) end++; return Arrays.copyOf(storage,end); }

    @Test void originalUnixCallsMatchNativeAndTheFirstInstalledEntry() throws Exception {
        var manifest=json(prefix+"/manifest.json"); assertEquals(new ArrayList<>(operations.keySet()),manifest.get("entries")); assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest.get("unixUnit"))); assertEquals(true,manifest.get("nativeIsolatedChild")); assertEquals(true,manifest.get("coordinatorCwdUnchanged")); assertEquals(true,manifest.get("privateRebuiltUnix")); assertEquals("a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e",manifest.get("unixArchiveSha256"));
        hashes(root,manifest.get("inputHashes"),Set.of("compiler/test-fixtures/OriginalCurrentDirectoryAudit.hs","test/haskell-fixtures/OriginalCurrentDirectoryFixtures.hs","scripts/core_original_foreign.py","scripts/core-capabilities.json")); var artifacts=new HashSet<>(Set.of(prefix+"/oracle.json")); for(var stage:List.of("pre","post")) { artifacts.add(prefix+"/"+stage+".json"); for(var entry:operations.keySet()) artifacts.add(prefix+"/"+stage+"-"+entry+".audit.json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix+"/");
        var oracle=json(prefix+"/oracle.json"); var chdirRows=(List<Map<String,Object>>)oracle.get("chdirRows"); var getcwdRows=(List<Map<String,Object>>)oracle.get("getcwdRows"); assertEquals(11,chdirRows.size()); assertEquals(12,getcwdRows.size()); var chdirNames=new HashSet<Object>(); for(var row:chdirRows) chdirNames.add(row.get("name")); var getcwdNames=new HashSet<Object>(); for(var row:getcwdRows) getcwdNames.add(row.get("name")); assertEquals(11,chdirNames.size()); assertEquals(12,getcwdNames.size()); var processDirectory=Files.readSymbolicLink(Path.of("/proc/self/cwd"));
        for(var stage:List.of("pre","post")) for(var operationEntry:operations.entrySet()) {
            var entryName=operationEntry.getKey(); var operation=operationEntry.getValue(); var audit=json(prefix+"/"+stage+"-"+entryName+".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); var linked=with(CoreModules.reachable(source(stage),entryName),"instrument",true); var evidence=new ArrayCoreEvidence(linked,entryName); assertEquals(1,evidence.getBindings().size()); assertEquals(1,evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(operation,validate(original(entryName,stage))); assertEquals(manifest.get("unixUnit"),((Map<?,?>)((Map<?,?>)((Map<?,?>)original(entryName,stage).get(6)).get("foreignCall")).get("target")).get("unit"));
            for(var backend:List.of("ast","bytecode")) try(var context=context()) { context.initialize("thc"); context.enter(); try {
                var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); var executable=program(language,backend,linked); var entry=executable.entryTarget(entryName); var stdio=Language.currentState().getStdio();
                class Exercise { void run(boolean compiled) throws Exception {
                    for(var row:operation==OriginalStdioOp.CHDIR?chdirRows:getcwdRows) {
                        var base=setup(); assertEquals(0L,stdio.changeDirectory(pathAddress(base))); var terminated=NativeDirectoryOwner.pathBytes(base); var baseBytes=Arrays.copyOf(terminated,terminated.length-1); int longDepth=0;
                        try {
                            if(operation==OriginalStdioOp.GETCWD) switch((String)row.get("setup")) {
                                case "base" -> {} case "sub" -> assertEquals(0L,stdio.changeDirectory(address("sub"))); case "raw" -> assertEquals(0L,stdio.changeDirectory(address(new byte[]{-1,110}))); case "physical-link" -> assertEquals(0L,stdio.changeDirectory(address("link")));
                                case "renamed" -> { assertEquals(0L,stdio.changeDirectory(address("sub"))); Files.move(base.resolve("sub"),base.resolve("renamed")); }
                                case "renamed-ancestor" -> { assertEquals(0L,stdio.changeDirectory(address("parent/child"))); Files.move(base.resolve("parent"),base.resolve("moved")); }
                                case "deleted" -> { assertEquals(0L,stdio.changeDirectory(address("sub"))); Files.delete(base.resolve("sub")); }
                                case "long" -> { for(int i=0;i<((Long)oracle.get("longDepth")).intValue();i++) { var component=address(bytes(oracle,"longComponent")); assertEquals(0L,stdio.pathMode(OriginalStdioOp.MKDIR,component,448)); assertEquals(0L,stdio.changeDirectory(component)); longDepth++; } }
                                default -> fail("Unknown native CWD setup");
                            }
                            byte[] expectedName=row.get("cwdSuffix") instanceof List<?>?concat(baseBytes,bytes(row,"cwdSuffix")):Objects.equals(row.get("setup"),"base")?baseBytes:null;
                            int capacity=switch(Objects.toString(row.get("capacityKind"),"")) { case "exact" -> expectedName.length+1; case "short" -> expectedName.length; case "one" -> 1; case "zero" -> 0; default -> row.get("capacity") instanceof Long n?n.intValue():16384; }; int offset=row.get("offset") instanceof Long n?n.intValue():8; byte[] storage=new byte[offset+capacity+8]; Arrays.fill(storage,(byte)90); var output=ManagedAddress.fromByteArray(storage).plus(offset); assertEquals(-1L,stdio.close(-1)); long priorError=stdio.errno(); if(compiled) valid(entry); long before=((Number)executable.diagnostics().get("compiledEntries")).longValue(); Object actual;
                            if(operation==OriginalStdioOp.CHDIR) { var path=bytes(row,"path"); actual=Calls.target(entry,new Object[]{0L,Objects.equals(row.get("absolute"),true)?address(concat(baseBytes,new byte[]{47},path)):address(path)}); } else actual=Calls.target(entry,new Object[]{0L,output,(long)capacity});
                            // Successful libc errno/scratch tail is unspecified (notably its
                            // long-name fallback). THC deliberately preserves both.
                            boolean succeeded=operation==OriginalStdioOp.CHDIR?Objects.equals(row.get("status"),0L):Objects.equals(row.get("returnedNull"),false); assertEquals(succeeded?priorError:row.get("errno"),stdio.errno(),stage+"/"+backend+"/"+entryName+"/"+row.get("name")); if(compiled) { assertEquals(before+1,((Number)executable.diagnostics().get("compiledEntries")).longValue()); valid(entry); }
                            if(operation==OriginalStdioOp.CHDIR) { assertEquals(row.get("status"),actual); assertArrayEquals(expectedName,name(stdio)); } else { var returned=(ManagedAddress)actual; assertEquals(row.get("returnedNull"),returned.sameLocation(ManagedAddress.nullAddress())); assertEquals(row.get("returnedSameBuffer"),returned.sameLocation(output)); if(Objects.equals(row.get("returnedNull"),true)) { for(byte value:storage) assertEquals((byte)90,value,"Staging preserves output on failure"); } else { assertArrayEquals(expectedName,Arrays.copyOfRange(storage,offset,offset+expectedName.length)); assertEquals((byte)0,storage[offset+expectedName.length]); for(int i=0;i<offset;i++) assertEquals((byte)90,storage[i]); for(int i=offset+expectedName.length+1;i<storage.length;i++) assertEquals((byte)90,storage[i]); } }
                            released(language);
                        } finally { for(int i=0;i<longDepth;i++) { assertEquals(0L,stdio.changeDirectory(address(".."))); assertEquals(0L,stdio.unlinkAt(StdioHostAbi.load().getAtFdcwd(),address(bytes(oracle,"longComponent")),StdioHostAbi.load().getAtRemoveDir())); } assertEquals(0L,stdio.changeDirectory(pathAddress(base))); Files.setPosixFilePermissions(base.resolve("search-denied"),PosixFilePermissions.fromString("rwx------")); }
                    }
                }}
                var exercise=new Exercise(); exercise.run(false); entry.getClass().getMethod("compile",boolean.class).invoke(entry,true); valid(entry); exercise.run(true); assertEquals(0L,((Number)executable.diagnostics().get("unsupportedTraps")).longValue());
            } finally { context.leave(); } }
        }
        assertEquals(processDirectory,Files.readSymbolicLink(Path.of("/proc/self/cwd")));
    }
    @Test void originalOwnerHeadAbiStateAndOperandProofsRemainRequired() throws Exception {
        for(var operationEntry:operations.entrySet()) { var call=original(operationEntry.getKey()); assertEquals(operationEntry.getValue(),validate(call));
            for(var unit:List.of("main","ghc-internal","unix-2.8.7.0-inplace","unix-2.8.8.0-ABCD","unix-2.8.8.0-460b:forged")) { var bad=(List<Object>)copy(call); ((Map<String,Object>)((Map<?,?>)((Map<?,?>)bad.get(6)).get("foreignCall")).get("target")).put("unit",unit); assertThrows(RuntimeFault.class,()->validate(bad)); }
            for(var edit:List.of(list("safety","safe"),list("convention","capi"),list("arity",99L),list("suppliedArity",99L))) { var bad=(List<Object>)copy(call); ((Map<String,Object>)((Map<?,?>)bad.get(6)).get("foreignCall")).put((String)edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,()->validate(bad)); }
            var badResult=(List<Object>)copy(call); var metadata=(Map<?,?>)badResult.get(6); for(var rep:list(metadata.get("rep"),((Map<?,?>)metadata.get("foreignCall")).get("resultRep"))) { var proof=(Map<String,Object>)rep; proof.put("primReps",list("Int64Rep")); ((List<Map<String,Object>>)proof.get("components")).get(1).put("primReps",list("Int64Rep")); } assertThrows(RuntimeFault.class,()->validate(badResult));
            for(var backend:List.of("ast","bytecode")) try(var context=context()) { context.initialize("thc"); context.enter(); try { var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); for(int i=0;i<((List<?>)call.get(2)).size();i++) { int index=i; assertThrows(RuntimeFault.class,()->program(language,backend,rawModule(call,source("post"),index))); } var shadowed=raw(call); var body=single((List<Map<String,Object>>)shadowed.get("bindings"),ignored->true).get("expr"); var changed=single(foreignCalls(body),ignored->true); changed.set(1,list("var","p0",((List<?>)changed.get(1)).get(2))); assertThrows(RuntimeFault.class,()->program(language,backend,shadowed)); } finally { context.leave(); } }
        }
    }
    @Test void invalidStateCapacityAndStoragePrecedeDirectoryObservationAndPublication() throws Exception {
        for(var backend:List.of("ast","bytecode")) try(var context=context()) { context.initialize("thc"); context.enter(); try {
            var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); var base=setup(); var stdio=Language.currentState().getStdio(); assertEquals(0L,stdio.changeDirectory(pathAddress(base))); var chdir=program(language,backend,raw(original("pathChdir"))).entryTarget("entry"); var getcwd=program(language,backend,raw(original("pathGetCwd"))).entryTarget("entry"); byte[] storage=new byte[8192]; Arrays.fill(storage,(byte)90); var output=ManagedAddress.fromByteArray(storage).plus(8); assertEquals(-1L,stdio.close(-1)); long error=stdio.errno(); assertThrows(RuntimeFault.class,()->Calls.target(chdir,new Object[]{0L,address("sub"),9L})); var path=NativeDirectoryOwner.pathBytes(base); assertArrayEquals(Arrays.copyOf(path,path.length-1),name(stdio));
            class Invoke { Object call(ManagedAddress destination,long capacity,Object state) { return Calls.target(getcwd,new Object[]{0L,destination,capacity,state}); } Object call(ManagedAddress destination) { return call(destination,4096,thc.runtime.Unit.INSTANCE); } } var invoke=new Invoke();
            assertThrows(RuntimeFault.class,()->invoke.call(output,4096,9L)); for(long capacity:new long[]{-1,(long)Integer.MAX_VALUE+1}) assertThrows(RuntimeFault.class,()->invoke.call(output,capacity,thc.runtime.Unit.INSTANCE)); for(var bad:List.of(ManagedAddress.nullAddress(),ManagedAddress.fromByteArray(new byte[4095]),ManagedAddress.fromHex("5a".repeat(4096)))) assertThrows(RuntimeFault.class,()->invoke.call(bad)); var cells=ManagedAllocation.mutable(4096,8); cells.writeAddressByteOffset(8,output); assertThrows(RuntimeFault.class,()->invoke.call(ManagedAddress.fromAllocation(cells))); assertSame(output,cells.readAddressByteOffset(8)); assertEquals(error,stdio.errno()); for(byte value:storage) assertEquals((byte)90,value);
            var state=Language.currentState(); var nativeAddress=state.getNativeAllocations().malloc(8192); var alias=nativeAddress.plus(8); try { nativeAddress.fill(8192,90); assertSame(alias,invoke.call(alias)); for(long i=0;i<=7;i++) assertEquals(90L,nativeAddress.readWord8(i)); try(var second=context()) { second.initialize("thc"); second.enter(); try { var other=TruffleLanguage.LanguageReference.create(Language.class).get(null); var target=program(other,backend,raw(original("pathGetCwd"))).entryTarget("entry"); assertThrows(RuntimeFault.class,()->Calls.target(target,new Object[]{0L,alias,4096L,thc.runtime.Unit.INSTANCE})); } finally { second.leave(); } } } finally { state.getNativeAllocations().free(nativeAddress); } assertThrows(RuntimeFault.class,()->invoke.call(alias)); Files.setPosixFilePermissions(base.resolve("search-denied"),PosixFilePermissions.fromString("rwx------")); released(language);
        } finally { context.leave(); } }
    }
    @Test void existingRawServicesAndSafeOpenFollowRenamedDirectoryIdentity() throws Exception {
        try(var context=context()) { context.initialize("thc"); context.enter(); try {
            var stdio=Language.currentState().getStdio(); var abi=StdioHostAbi.load(); var original=Files.createDirectory(directory.resolve("original")); var moved=directory.resolve("moved"); Files.writeString(original.resolve("tmp"),"original"); Files.write(rawPath(original,new byte[]{-1,110}),new byte[]{42}); assertEquals(0L,stdio.changeDirectory(pathAddress(original))); Files.move(original,moved); Files.createDirectory(original); Files.writeString(original.resolve("tmp"),"replacement");
            for(var operation:List.of(OriginalStdioOp.OPEN,OriginalStdioOp.OPEN_SAFE,OriginalStdioOp.OPEN_INTERRUPTIBLE)) { long fd=stdio.open(address("tmp"),stdio.flagConstant(OriginalStdioOp.O_RDONLY),0,operation); assertTrue(fd>=0); byte[] bytes=new byte[8]; assertEquals(8L,stdio.read(fd,ManagedAddress.fromByteArray(bytes),8)); assertEquals("original",new String(bytes,StandardCharsets.UTF_8)); assertEquals(0L,stdio.close(fd)); }
            // Also exercise the Path-based private provider: /tmp is a host directory,
            // but context/tmp is a regular file and must not acquire a URI-added slash.
            try(var file=NativeFileProvider.current().open("tmp",0)) { assertEquals(8L,file.size()); } assertEquals(0L,stdio.access(address(new byte[]{-1,110}),0)); assertEquals(0L,stdio.pathMode(OriginalStdioOp.MKDIR,address("created"),448)); assertEquals(0L,stdio.pathMode(OriginalStdioOp.CHMOD,address("tmp"),384)); assertEquals(0L,stdio.symlink(address("./tmp"),address("new-link"))); byte[] target=new byte[32]; Arrays.fill(target,(byte)90); assertEquals(5L,stdio.readlink(address("new-link"),ManagedAddress.fromByteArray(target),32)); assertArrayEquals("./tmp".getBytes(StandardCharsets.UTF_8),Arrays.copyOfRange(target,0,5)); long size=PosixStat.execute(OriginalStdioOp.SIZEOF_STAT,ManagedAddress.nullAddress(),0); var image=ManagedAddress.fromByteArray(new byte[(int)size]); assertEquals(0L,stdio.statAt(abi.getAtFdcwd(),address("tmp"),image,0)); assertEquals(8L,PosixStat.execute(OriginalStdioOp.ST_SIZE,image,0)); assertEquals(0L,stdio.unlink(address("new-link"))); assertEquals(0L,stdio.unlinkAt(abi.getAtFdcwd(),address("created"),abi.getAtRemoveDir())); assertFalse(Files.exists(moved.resolve("created"))); assertEquals("replacement",Files.readString(original.resolve("tmp")));
        } finally { context.leave(); } }
    }
}
