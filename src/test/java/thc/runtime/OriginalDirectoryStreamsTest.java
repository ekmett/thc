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
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Genuine Unix declarations, with the selected glibc readdir ownership contract. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named="os.arch",matches="amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalDirectoryStreamsTest {
    @TempDir Path directory;
    private final File root=new File(System.getProperty("thc.projectRoot"));
    private final String prefix="build/original-directory-streams";
    private final Map<String,OriginalStdioOp> operations=new LinkedHashMap<>();
    OriginalDirectoryStreamsTest() { operations.put("directoryOpen",OriginalStdioOp.OPENDIR); operations.put("directoryFdOpen",OriginalStdioOp.FDOPENDIR); operations.put("directoryClose",OriginalStdioOp.CLOSEDIR); operations.put("directoryRead",OriginalStdioOp.READDIR); operations.put("directoryName",OriginalStdioOp.DIRENT_NAME); operations.put("directoryFree",OriginalStdioOp.FREE_DIRENT); }
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>)Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> source(String stage) throws Exception { return json(prefix+"/"+stage+".json"); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private ManagedAddress address(byte[] bytes) { return ManagedAddress.fromByteArray(Arrays.copyOf(bytes,bytes.length+1)); }
    private ManagedAddress path(Path path) { return ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path)); }
    private List<Long> bytes(ManagedAddress pointer) { var result=new ArrayList<Long>(); long length=pointer.cStringLength(); for(long i=0;i<length;i++) result.add(pointer.readWord8(i)); return result; }
    private Context context() { return NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST); }
    private ExecutableProgram program(Language language,String backend,Map<String,Object> module) { return backend.equals("ast")?new Program(language,module):new BytecodeProgram(language,module); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<Object> original(String name) throws Exception { return original(name,"post"); }
    private List<Object> original(String name,String stage) throws Exception { return single(foreignCalls(CoreModules.reachable(source(stage),name)),ignored->true); }
    private OriginalStdioOp validate(List<Object> call) { var reps=new ArrayList<Object>(); for(var arg:(List<List<Object>>)call.get(2)) { var metadata=CoreRepresentations.metadata(arg); reps.add(metadata==null?null:metadata.get("rep")); } return CoreOriginalStdio.validate(call.get(6),reps,(List<?>)call.get(3),((Map<?,?>)call.get(6)).get("rep")); }
    private Path setup() throws Exception {
        var base=Files.createTempDirectory(directory,"case-"); Files.setPosixFilePermissions(base,PosixFilePermissions.fromString("rwx------")); Files.writeString(base.resolve("file"),"unchanged"); Files.createDirectory(base.resolve("sub")); Files.createDirectory(base.resolve("denied")); var prefix=NativeDirectoryOwner.pathBytes(base); var raw=Arrays.copyOf(prefix,prefix.length+2); raw[prefix.length-1]=47; raw[prefix.length]=-1; raw[prefix.length+1]=110; Files.createDirectory(NativeDirectoryOwner.bytesPath(raw)); Files.createSymbolicLink(base.resolve("link"),Path.of("sub")); Files.createSymbolicLink(base.resolve("dangling"),Path.of("missing")); Files.setPosixFilePermissions(base.resolve("denied"),Set.of()); return base;
    }
    @Test void genuineStreamsMatchNativeAtEveryFirstInstalledEntry() throws Exception {
        var manifest=json(prefix+"/manifest.json"); assertEquals(new ArrayList<>(operations.keySet()),manifest.get("entries")); assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest.get("unixUnit"))); assertEquals(true,manifest.get("privateRebuiltUnix")); hashes(root,manifest.get("inputHashes"),Set.of("compiler/test-fixtures/OriginalDirectoryStreamsAudit.hs","test/haskell-fixtures/OriginalDirectoryStreamsFixtures.hs","scripts/core_original_foreign.py","scripts/core-capabilities.json")); var artifacts=new HashSet<>(Set.of(prefix+"/oracle.json",prefix+"/unix-source.json")); for(var stage:List.of("pre","post")) { artifacts.add(prefix+"/"+stage+".json"); for(var name:operations.keySet()) artifacts.add(prefix+"/"+stage+"-"+name+".audit.json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix+"/");
        var oracle=json(prefix+"/oracle.json"); assertEquals(8L,oracle.get("pointerBytes")); assertEquals(true,oracle.get("processCwdUnchanged")); var pathRows=(List<Map<String,Object>>)oracle.get("pathRows"); var fdRows=(List<Map<String,Object>>)oracle.get("fdRows"); var specialRows=(List<Map<String,Object>>)oracle.get("specialRows"); assertEquals(12,pathRows.size()); assertEquals(5,fdRows.size()); assertEquals(3,specialRows.size()); var processDirectory=Files.readSymbolicLink(Path.of("/proc/self/cwd"));
        for(var stage:List.of("pre","post")) {
            for(var operation:operations.entrySet()) { var name=operation.getKey(); var audit=json(prefix+"/"+stage+"-"+name+".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); var evidence=new ArrayCoreEvidence(CoreModules.reachable(source(stage),name),name); assertEquals(1,evidence.getBindings().size()); assertEquals(1,evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(operation.getValue(),validate(original(name,stage))); assertEquals(manifest.get("unixUnit"),((Map<?,?>)((Map<?,?>)((Map<?,?>)original(name,stage).get(6)).get("foreignCall")).get("target")).get("unit")); }
            for(var backend:List.of("ast","bytecode")) try(var context=context()) { context.initialize("thc"); context.enter(); try {
                var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); var executable=program(language,backend,with(source(stage),"instrument",true)); var targets=new LinkedHashMap<String,RootCallTarget>(); for(var name:operations.keySet()) targets.put(name,executable.entryTarget(name)); var stdio=Language.currentState().getStdio();
                class Exercise {
                    boolean compiled;
                    Object invoke(String name,Object... args) throws Exception { var target=targets.get(name); if(compiled) valid(target); long before=((Number)executable.diagnostics().get("compiledEntries")).longValue(); Object[] packet=new Object[args.length+1]; packet[0]=0L; System.arraycopy(args,0,packet,1,args.length); var result=Calls.target(target,packet); if(compiled) { assertEquals(before+1,((Number)executable.diagnostics().get("compiledEntries")).longValue(),stage+"/"+backend+"/"+name); valid(target); } var handoff=language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getArguments().retainedReferences()); assertEquals(0,handoff.getResults().retainedReferences()); return result; }
                    List<Map<String,Object>> drain(ManagedAddress stream,long seed) throws Exception {
                        var result=new ArrayList<Map<String,Object>>(); for(int i=0;i<64;i++) { var storage=ManagedAddress.fromAllocation(ManagedAllocation.mutable(24,8)); storage.fill(24,165); var output=storage.plus(8); output.writeAddressElementIndex(0,ManagedAddress.nullAddress()); stdio.setErrno(seed); var status=invoke("directoryRead",stream,output); long error=stdio.errno(); var entry=output.readAddressElementIndex(0); boolean isNull=entry.sameLocation(ManagedAddress.nullAddress()); List<Long> name=null; boolean freePreserved=true; if(!isNull) { var pointer=(ManagedAddress)invoke("directoryName",entry); name=bytes(pointer); assertEquals(0L,invoke("directoryFree",entry)); freePreserved=name.equals(bytes(pointer)); } boolean guards=true; for(long j=0;j<=7;j++) guards &= storage.readWord8(j)==165L&&storage.readWord8(j+16)==165L; result.add(map("status",status,"errno",error,"null",isNull,"name",name,"guardsIntact",guards,"freePreserved",freePreserved)); if(isNull) return result; } fail("Directory stream did not reach EOF"); return result;
                    }
                    // Enumeration order is unspecified. Preserve every observation while
                    // comparing entries by raw name, including the separate EOF row.
                    Map<Object,Map<String,Object>> keyed(List<Map<String,Object>> values) { var result=new LinkedHashMap<Object,Map<String,Object>>(); for(var value:values) result.put(value.get("name"),value); return result; }
                    void compareReads(Object expected,List<Map<String,Object>> actual) { var rows=(List<Map<String,Object>>)expected; assertEquals(rows.size(),keyed(rows).size()); assertEquals(actual.size(),keyed(actual).size()); assertEquals(keyed(rows),keyed(actual)); }
                    void checkOpened(Map<String,Object> expected,ManagedAddress stream,long openError) throws Exception { assertEquals(expected.get("null"),stream.sameLocation(ManagedAddress.nullAddress())); assertEquals(expected.get("errno"),openError); if(Objects.equals(expected.get("null"),false)) { compareReads(expected.get("reads"),drain(stream,0)); stdio.setErrno(9); assertEquals(expected.get("closeStatus"),invoke("directoryClose",stream)); assertEquals(expected.get("closeErrno"),stdio.errno()); } }
                    void run() throws Exception {
                        for(var row:pathRows) { var base=setup(); try { assertEquals(0L,stdio.changeDirectory(path(base))); var values=(List<Long>)row.get("path"); byte[] suffix=new byte[values.size()]; for(int i=0;i<suffix.length;i++) suffix[i]=values.get(i).byteValue(); byte[] name=suffix; if(Objects.equals(row.get("absolute"),true)) { var prefix=NativeDirectoryOwner.pathBytes(base); name=Arrays.copyOf(prefix,prefix.length+suffix.length); name[prefix.length-1]=47; System.arraycopy(suffix,0,name,prefix.length,suffix.length); } stdio.setErrno(9); var stream=(ManagedAddress)invoke("directoryOpen",address(name)); checkOpened((Map<String,Object>)row.get("result"),stream,stdio.errno()); } finally { Files.setPosixFilePermissions(base.resolve("denied"),PosixFilePermissions.fromString("rwx------")); } }
                        for(var row:fdRows) { var base=setup(); try { var name=row.get("name"); long fd=Objects.equals(name,"invalid")?-1L:stdio.open(path(Objects.equals(name,"regular")?base.resolve("file"):base),stdio.flagConstant(OriginalStdioOp.O_RDONLY),0); Long alias=Objects.equals(name,"alias")?stdio.duplicate(fd):null; if(Objects.equals(name,"closed")) assertEquals(0L,stdio.close(fd)); stdio.setErrno(9); var stream=(ManagedAddress)invoke("directoryFdOpen",fd); long error=stdio.errno(); if(alias!=null) assertEquals(0L,stdio.close(alias)); boolean preserved=!(stream.sameLocation(ManagedAddress.nullAddress())&&Objects.equals(name,"regular"))||stdio.close(fd)==0L; assertEquals(row.get("failureFdPreserved"),preserved); checkOpened((Map<String,Object>)row.get("result"),stream,error); } finally { Files.setPosixFilePermissions(base.resolve("denied"),PosixFilePermissions.fromString("rwx------")); } }
                        for(var row:specialRows) { var base=setup(); var moved=base.resolveSibling(base.getFileName()+"-renamed"); try { var stream=(ManagedAddress)invoke("directoryOpen",path(Objects.equals(row.get("name"),"deleted")?base.resolve("sub"):base)); assertFalse(stream.sameLocation(ManagedAddress.nullAddress())); if(Objects.equals(row.get("name"),"renamed")) Files.move(base,moved); if(Objects.equals(row.get("name"),"deleted")) Files.delete(base.resolve("sub")); compareReads(row.get("reads"),drain(stream,Objects.equals(row.get("name"),"sticky-eof")?9:0)); assertEquals(row.get("closeStatus"),invoke("directoryClose",stream)); } finally { if(Files.exists(moved)) Files.move(moved,base); Files.setPosixFilePermissions(base.resolve("denied"),PosixFilePermissions.fromString("rwx------")); } }
                        assertEquals(0,NativeFileProvider.current().getDirectoryStreams().liveCount());
                    }
                }
                var exercise=new Exercise(); exercise.run(); for(var target:targets.values()) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); } exercise.compiled=true; exercise.run(); assertEquals(0L,((Number)executable.diagnostics().get("unsupportedTraps")).longValue());
            } finally { context.leave(); } }
        }
        assertEquals(processDirectory,Files.readSymbolicLink(Path.of("/proc/self/cwd")));
    }
    @Test void originalUnitCapiOwnerSafetyAndStoredOperandsStayAuthoritative() throws Exception {
        for(var operation:operations.entrySet()) { var call=original(operation.getKey()); var op=operation.getValue(); assertEquals(op,validate(call)); for(var unit:List.of("main","ghc-internal","unix-2.8.7.0-inplace","unix-2.8.8.0-ABCD","unix-2.8.8.0-460b:forged")) { var bad=(List<Object>)copy(call); ((Map<String,Object>)((Map<?,?>)((Map<?,?>)bad.get(6)).get("foreignCall")).get("target")).put("unit",unit); assertThrows(RuntimeFault.class,()->validate(bad)); } String convention=List.of(OriginalStdioOp.OPENDIR,OriginalStdioOp.FDOPENDIR).contains(op)?"ccall":"capi";
            for(var edit:List.of(list("safety","safe"),list("convention",convention),list("arity",99L),list("suppliedArity",99L))) { var bad=(List<Object>)copy(call); ((Map<String,Object>)((Map<?,?>)bad.get(6)).get("foreignCall")).put((String)edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,()->validate(bad)); }
            for(var backend:List.of("ast","bytecode")) try(var context=context()) { context.initialize("thc"); context.enter(); try { var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); for(int i=0;i<((List<?>)call.get(2)).size();i++) { int index=i; assertThrows(RuntimeFault.class,()->program(language,backend,rawModule(call,source("post"),index))); } var shadowed=rawModule(call,source("post")); var body=single((List<Map<String,Object>>)shadowed.get("bindings"),ignored->true).get("expr"); var changed=single(foreignCalls(body),ignored->true); changed.set(1,list("var","p0",((List<?>)changed.get(1)).get(2))); assertThrows(RuntimeFault.class,()->program(language,backend,shadowed)); } finally { context.leave(); } }
        }
    }
    @Test void invalidStateCannotOpenAdvanceOrCloseAnOriginalStream() throws Exception {
        for(var backend:List.of("ast","bytecode")) try(var context=context()) { context.initialize("thc"); context.enter(); try { var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); var service=NativeFileProvider.current().getDirectoryStreams(); var stdio=Language.currentState().getStdio(); var stream=stdio.openDirectory(path(directory)); var output=ManagedAddress.fromAllocation(ManagedAllocation.mutable(8,8)); assertEquals(0L,service.read(stream,output)); var entry=output.readAddressElementIndex(0); var beforeName=bytes(service.name(entry)); stdio.setErrno(9);
            for(var operation:operations.entrySet()) { var target=program(language,backend,rawModule(original(operation.getKey()),source("post"))).entryTarget("entry"); Object[] operands=switch(operation.getValue()) { case OPENDIR -> new Object[]{path(directory)}; case FDOPENDIR -> new Object[]{-1L}; case READDIR -> new Object[]{stream,output}; case CLOSEDIR -> new Object[]{stream}; default -> new Object[]{entry}; }; Object[] packet=new Object[operands.length+2]; packet[0]=0L; System.arraycopy(operands,0,packet,1,operands.length); packet[packet.length-1]=7L; assertThrows(RuntimeFault.class,()->Calls.target(target,packet)); assertEquals(9L,stdio.errno()); assertEquals(beforeName,bytes(service.name(entry))); assertEquals(1,service.liveCount()); } assertEquals(0L,service.closeStream(stream));
        } finally { context.leave(); } }
    }
}
