// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import thc.runtime.CoreFloatingLiteral;
import static thc.CoreHiTypes.*;

/** Native interface syntax is admitted before owners are resolved. Declaration
 * providers do not need executable Core; each executable context owns its lowering state. */
final class CoreHiModule {
    private record Info(int arity,List<Boolean> marks,Integer join) {}
    private record Binder(String name,Object type,Info info,int offset,boolean typeVariable,Object multiplicity) {
        Binder(String name,Object type,Info info,int offset,boolean typeVariable){this(name,type,info,offset,typeVariable,null);}
    }
    private record Expr(int tag,List<Object> fields,int offset) {}
    private record Alt(int tag,Object discriminator,List<String> binders,Expr rhs) {}
    private record Group(boolean recursive,List<Definition> definitions) {}
    private record Definition(Binder binder,Expr rhs) {}
    private record Variable(String id,Ty type) {}
    private record Lowered(List<Object> expression,Ty type) {}
    private record Literal(String kind,Object value,Ty type) {}
    private record Constructor(CoreHiReader.ExternalName name,Ty result,List<CoreHiTypes.Variable> universal,
                               List<CoreHiTypes.Variable> existential,List<Ty> fields,Ty signature,
                               List<Ty> multiplicities,List<Bang> bangs,int tag,int offset,boolean newtype,
                               Map<CoreHiTypes.Variable,Ty> equalities) {
        Constructor(CoreHiReader.ExternalName name,Ty result,List<CoreHiTypes.Variable> universal,
                    List<CoreHiTypes.Variable> existential,List<Ty> fields,Ty signature,
                    List<Ty> multiplicities,List<Bang> bangs,int tag,int offset,boolean newtype) {
            this(name,result,universal,existential,fields,signature,multiplicities,bangs,tag,offset,newtype,Map.of());
        }
    }
    private record Bang(int tag,Co coercion) {}
    private record ClassProvider(Constructor dictionary,List<Parameter> parameters,List<CoreHiReader.ExternalName> selectors,
                                 boolean unary) {}
    private record ImplicitProvider(ClassProvider owner,int selector) {}
    record Admission(boolean containsDelimitedControl,boolean registrationObligations,boolean mainAlias,
                     boolean packageScalarDeclarations,boolean declarationOnly,List<String> foreignObligations) {
        Admission { foreignObligations=List.copyOf(foreignObligations); }
    }
    private final CoreHiReader reader;
    private final Function<String,CoreHiModule> dependencyModules;
    private final CoreHiTypes types;
    private final CoreHiTypes.Binary binary;
    private final Map<CoreHiReader.ExternalName,Declaration> typeDeclarations=new LinkedHashMap<>();
    private final Map<String,Axiom> typeAxioms=new LinkedHashMap<>();
    private final Map<String,CoreHiReader.ExternalName> pendingNewtypeAxioms=new LinkedHashMap<>();
    private final Set<String> derivingAxioms=new HashSet<>();
    private final Map<String,Constructor> constructors=new LinkedHashMap<>();
    private final Map<String,ImplicitProvider> implicitProviders=new LinkedHashMap<>();
    private final Map<CoreHiReader.ExternalName,ClassProvider> classes=new LinkedHashMap<>();
    private final Set<CoreHiReader.ExternalName> patterns=new LinkedHashSet<>();
    private final Map<String,Binder> declarations=new LinkedHashMap<>();
    private final Map<String,Definition> definitions=new LinkedHashMap<>();
    private final Map<String,Variable> topScope=new HashMap<>();
    private final Map<String,Map<String,Object>> lowered=new HashMap<>();
    private final List<Group> groups=new ArrayList<>();
    private final Set<CoreHiNames.TupleFamily> tuples=new LinkedHashSet<>();
    private final List<String> foreignObligations=new ArrayList<>();
    private boolean control,mainAlias,registration,scalarDeclarations;
    private long localOrdinal;
    private int declarationDepth;
    CoreHiModule(CoreHiReader reader){this(reader,ignored->null);}
    CoreHiModule(CoreHiReader reader,Function<String,CoreHiModule> dependencies){
        this.reader=reader;dependencyModules=Objects.requireNonNull(dependencies);
        binary=new CoreHiTypes.Binary(reader);types=new CoreHiTypes(this::declaration,this::axiom);
        var c = reader.cursor(reader.publicSections.get("annotations"),"interface annotations");
        c.require(c.count(1) == 0,"native interface annotations require package declaration transport");
        c.expectEnd();
        if (reader.simplifiedCore != null)
            c.require(reader.signatureOf == null && reader.sourceKind == 0,
                "signature or boot interface cannot provide executable retained Core");
        c=reader.cursor(reader.publicSections.get("declarations"),"interface declarations");
        int count=c.count(4);
        for(int i=0;i<count;i++){c.unsigned(64);c.unsigned(64);declaration(c);}
        c.expectEnd();
        if(reader.simplifiedCore!=null){
            c=reader.cursor(reader.simplifiedCore,"retained Core");count=c.count(2);
            for(int i=0;i<count;i++)groups.add(group(c,true,0,true));
            foreignProducts(c);
            c.expectEnd();
            for(Group group:groups)for(Definition definition:group.definitions){
                Binder b=definition.binder;String id=prefix()+b.name;mainAlias|=id.equals(CoreUnitDirectory.MAIN_ALIAS);
                c.require(definitions.putIfAbsent(id,definition)==null,"duplicate retained binding "+id);
                topScope.put(b.name,new Variable(id,types.read(b.type,Map.of())));
            }
        }
    }
    private void foreignProducts(CoreHiReader.Cursor c) {
        if (c.optional()) {
            String header = c.string(), source = c.string();
            if (!header.isEmpty()) foreignObligations.add("foreign header");
            if (!source.isEmpty()) foreignObligations.add("foreign source");
            registration |= !header.isEmpty() || !source.isEmpty();
            foreignLabels(c, true);
            foreignLabels(c, false);
        }
        int count = c.count(3);
        registration |= count != 0;
        for (int i = 0; i < count; i++) {
            int language = c.byteValue();
            c.require(language <= 6, "invalid foreign source language");
            String source = c.string(), extension = c.string();
            foreignObligations.add("foreign file " + language + ":" + source + " (" + extension + ")");
        }
    }
    private void foreignLabels(CoreHiReader.Cursor c, boolean initializer) {
        int count = c.count(5);
        registration |= count != 0;
        for (int i = 0; i < count; i++) {
            boolean kind = bool(c);
            c.require(kind == initializer, "foreign lifecycle label kind mismatch");
            var owner = reader.module(c);
            String name = reader.fastString(c);
            foreignObligations.add((initializer ? "initializer " : "finalizer ") +
                    owner.unit() + ":" + owner.name() + ":" + name);
        }
    }
    private String prefix(){return reader.module.unit()+":"+reader.module.name()+".";}
    boolean hasRetainedCore(){return reader.simplifiedCore!=null;}
    Admission admission(){return new Admission(control,registration,mainAlias,scalarDeclarations,!hasRetainedCore(),foreignObligations);}
    Set<String> declarationIds(){var ids=new LinkedHashSet<String>(declarations.keySet());for(var name:typeDeclarations.keySet())ids.add(CoreHiNames.id(name));ids.addAll(typeAxioms.keySet());ids.addAll(pendingNewtypeAxioms.keySet());ids.addAll(constructors.keySet());ids.addAll(implicitProviders.keySet());for(var name:patterns)ids.add(CoreHiNames.id(name));return Collections.unmodifiableSet(ids);}
    boolean containsSymbol(String id){return definitions.containsKey(id)||implicitProviders.containsKey(id);}
    Set<String> bindingIds(){var ids=new LinkedHashSet<>(definitions.keySet());ids.addAll(implicitProviders.keySet());return Collections.unmodifiableSet(ids);}
    private CoreHiModule owner(String id){return implicitProviders.containsKey(id)||declarations.containsKey(id)||typeDeclarations.keySet().stream().anyMatch(name->CoreHiNames.id(name).equals(id))||constructors.containsKey(id)||typeAxioms.containsKey(id)?this:dependencyModules.apply(id);}
    Declaration declaration(String id) {
        return typeDeclarations.values().stream().filter(d -> d.name().namespace() == 3 && CoreHiNames.id(d.name()).equals(id)).findFirst().orElse(null);
    }
    private Declaration declaration(CoreHiReader.ExternalName name){
        String id=CoreHiNames.id(name);Declaration found=typeDeclarations.get(name);if(found!=null){
            if(found.form().equals("newtype")&&found.axiom()==null&&derivingAxioms.add(id)){
                try{Axiom ax=typeAxioms.values().stream().filter(a->a.tycon().equals(name)).findFirst().orElse(null);if(ax==null)ax=types.newtypeAxiom(found);
                    typeAxioms.put(CoreHiNames.id(ax.name()),ax);found=new Declaration(found.name(),found.parameters(),found.kind(),found.resultKind(),found.roles(),found.form(),found.rhs(),ax);typeDeclarations.put(name,found);
                }finally{derivingAxioms.remove(id);}
            }
            return found;
        }
        var wired=CoreHiNames.typeDeclaration(name);
        if(wired!=null){found=types.declaration(wired);typeDeclarations.put(name,found);return found;}
        found=types.tupleDeclaration(name);if(found==null)found=types.sumDeclaration(name);if(found!=null){typeDeclarations.put(name,found);return found;}
        if (name.module().equals(reader.module)) return null;
        CoreHiModule module=dependencyModules.apply(id);return module==null?null:module.declaration(name);
    }
    private Axiom axiom(CoreHiReader.ExternalName name){
        String id=CoreHiNames.id(name);var found=typeAxioms.get(id);if(found!=null)return found;var pending=pendingNewtypeAxioms.get(id);if(pending!=null)return declaration(pending).axiom();
        // Compiler-owned axioms are kept with their actual owning TyCon metadata.
        for(Declaration d:typeDeclarations.values())if(d.axiom()!=null&&d.axiom().name().equals(name))return d.axiom();
        CoreHiModule module=dependencyModules.apply(CoreHiNames.id(name));return module==null?null:module.axiom(name);
    }
    Ty signature(String id){
        var implicit=implicitProviders.get(id);if(implicit!=null)return implicitSignature(implicit);
        var b=declarations.get(id);if(b!=null)return types.read(b.type,Map.of());
        var d=definitions.get(id);if(d!=null)return types.read(d.binder.type,Map.of());
        var c=constructors.get(id);return c==null?null:workerSignature(c);
    }
    Map<String,Object> metadata(){
        // Declaration-only metadata has no executable or startup admission.
        if(!foreignObligations.isEmpty())throw error(new Expr(-1,List.of(),0),"native foreign obligations require explicit package/native artifact admission: "+foreignObligations);
        return map("schema",1L,"ghc","9.14.1","unit",reader.module.unit(),"module",reader.module.name(),"boundary",hasRetainedCore()?"optimized-Core-after-Tidy-before-CorePrep":"interface-declarations","bindings",List.of(),"constructors",constructorLayouts());
    }
    List<Map<String,Object>> constructorLayouts(){
        var layouts=new ArrayList<Map<String,Object>>();
        for(Constructor constructor:new LinkedHashSet<>(constructors.values()))if(!constructor.newtype&&!unaryDictionary(constructor)&&CoreHiNames.tupleFamily(constructor.name)==null)layouts.add(constructorMetadata(constructor));
        for(var tuple:tuples)layouts.add(tupleMetadata(tuple));
        return List.copyOf(layouts);
    }
    private Map<String,Object> tupleMetadata(CoreHiNames.TupleFamily family){
        var name=CoreHiNames.tupleName(family.sort(),family.arity(),1);
        var layout=map("id",CoreHiNames.id(name),"name",name.occurrence(),"arity",(long)family.arity(),"tag",1L,"kind",family.sort()==1?"unboxed-tuple":"boxed");
        if(family.sort()!=1){var proof=map("kind","object","primReps",List.of("BoxedRep (Just Lifted)"),"evaluated",false);layout.put("fieldTypes",Collections.nCopies(family.arity(),proof));layout.put("fieldReps",Collections.nCopies(family.arity(),proof.get("primReps")));layout.put("fieldLifted",Collections.nCopies(family.arity(),true));layout.put("strictFields",Collections.nCopies(family.arity(),false));}
        return layout;
    }
    private Map<String,Object> constructorMetadata(Constructor constructor){
        WorkerFields layout=workerFields(constructor);var fields=new ArrayList<Ty>();var strict=new ArrayList<Boolean>();
        for(var v:constructor.existential)if(v.coercion()){fields.add(v.kind());strict.add(false);}fields.addAll(layout.fields);strict.addAll(layout.strict);
        var proofs=new ArrayList<Map<String,Object>>();var reps=new ArrayList<Object>();var lifted=new ArrayList<Object>();
        for(int i=0;i<fields.size();i++){Ty field=fields.get(i);var proof=rep(field,strict.get(i),new Expr(-1,List.of(),constructor.offset));proofs.add(proof);reps.add(proof.get("primReps"));lifted.add(lifted(field));}
        var sum = CoreHiNames.sumFamily(constructor.name);
        var metadata = map("id",CoreHiNames.id(constructor.name),"name",constructor.name.occurrence(),
            "arity",(long)fields.size(),"tag",(long)constructor.tag,"kind",sum != null ? "unboxed-sum" : "boxed",
            "fieldTypes",proofs,"fieldReps",reps,"fieldLifted",lifted,"strictFields",strict);
        if (sum != null) metadata.put("sumArity",(long)sum.arity());
        return metadata;
    }
    Map<String,Object> binding(String id){
        var implicit=implicitProviders.get(id);
        if(implicit!=null)return lowered.computeIfAbsent(id,ignored->implicitBinding(id,implicit));
        if(!hasRetainedCore()&&declarations.containsKey(id))reader.requireRetainedCore();
        var definition=definitions.get(id);return definition==null?null:lowered.computeIfAbsent(id,ignored->binding(definition,new Variable(id,signature(id)),topScope,Map.of()));
    }
    private Object type(CoreHiReader.Cursor c,int depth){return binary.type(c,depth);}
    private List<Ty> typeList(CoreHiReader.Cursor c,Map<String,CoreHiTypes.Variable> scope){int n=c.count(1);var result=new ArrayList<Ty>();for(int i=0;i<n;i++)result.add(types.read(type(c,0),scope));return List.copyOf(result);}
    private List<Parameter> parameters(CoreHiReader.Cursor c,Map<String,CoreHiTypes.Variable> scope){
        int n=c.count(2);var result=new ArrayList<Parameter>();
        for(int i=0;i<n;i++){CoreHiTypes.Variable v=types.binder(binary.binder(c,0),scope);scope.put(v.spelling(),v);int tag=c.byteValue();c.require(tag<=1,"invalid TyCon binder visibility");result.add(new Parameter(v,tag==0?List.of(0):List.of(1,visibility(c))));}
        return List.copyOf(result);
    }
    private List<Integer> roles(CoreHiReader.Cursor c){int n=c.count(1);var result=new ArrayList<Integer>();for(int i=0;i<n;i++){int role=c.byteValue();c.require(role>=1&&role<=3,"invalid role");result.add(role);}return List.copyOf(result);}
    private static int visibility(CoreHiReader.Cursor c){int v=c.byteValue();c.require(v<=2,"invalid forall visibility");return v;}
    private Ty piKind(List<Parameter> parameters,Ty result){for(int i=parameters.size()-1;i>=0;i--){var p=parameters.get(i);result=p.visibility().getFirst()==0?new Fun(0,many(),p.variable().kind(),result):new ForAll(p.variable(),p.visibility().get(1),result);}return result;}
    private static Ty many(){return con(new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Types"),1,null,"Many"),true);}
    private void declaration(CoreHiReader.Cursor c) { declaration(c,Map.of()); }
    private CoreHiReader.ExternalName declaration(CoreHiReader.Cursor c,Map<String,CoreHiTypes.Variable> enclosing) {
        c.require(declarationDepth<256,"interface declaration nesting exceeds limit");
        declarationDepth++;
        try{return readDeclaration(c,enclosing);}finally{declarationDepth--;}
    }
    private CoreHiReader.ExternalName readDeclaration(CoreHiReader.Cursor c,Map<String,CoreHiTypes.Variable> enclosing) {
        int offset=c.position(),tag=c.byteValue();
        var context = new ArrayList<Object>();
        if (tag == 5) { int count=c.count(1);for(int i=0;i<count;i++)context.add(type(c,0)); }
        var name=CoreHiNames.read(reader,c);String id=CoreHiNames.id(name);
        c.require(name.module().equals(reader.module),"declaration belongs to another module");
        if(tag==0){var body=reader.cursor(c.lazy(),"Id "+id);Object type=type(body,0);var marks=details(body,name);Info info=hasRetainedCore()?info(body,0):new Info(0,null,null);
            declarations.put(id,new Binder(id.substring(prefix().length()),type,new Info(info.arity,marks,null),offset,false));return name;}
        var scope=new HashMap<>(enclosing);
        if(tag==5||tag==8){classDeclaration(c,name,context,scope,offset,tag==8);return name;}
        if(tag==7){patternDeclaration(c,name);return name;}
        if(tag==6){var tc=(CoreHiReader.ExternalName)binary.tycon(c).getFirst();int role=c.byteValue();c.require(role>=1&&role<=3,"invalid axiom role");typeAxioms.put(id,new Axiom(name,tc,role,branches(c)));return name;}
        List<Integer> roles=tag==3?roles(c):List.of();
        if(tag==4&&c.optional())reader.fastString(c);
        List<Parameter> parameters=parameters(c,scope);Ty result=types.read(type(c,0),scope),rhs=null;String form;
        if(tag==3){form="synonym";rhs=types.read(type(c,0),scope);}
        else if(tag==4){form="family";int flavour=c.byteValue();c.require(flavour<=3,"invalid family flavour");if(flavour==2&&c.optional()){var ax=CoreHiNames.read(reader,c);typeAxioms.put(CoreHiNames.id(ax),new Axiom(ax,name,1,branches(c)));}int injective=c.byteValue();c.require(injective<=1,"invalid injectivity");if(injective==1){int n=c.count(1);for(int i=0;i<n;i++)bool(c);}}
        else if(tag==2){
            if(c.optional()){sourceText(c);if(c.optional()){sourceText(c);reader.fastString(c);}reader.fastString(c);}
            roles=roles(c);typeList(c,scope);int shape=c.byteValue();c.require(shape<=3,"invalid constructor form");form=shape==3?"newtype":"data";
            int n=shape==0?0:shape==3?1:c.count(1);
            for(int i=0;i<n;i++){Constructor con=constructor(c,name,parameters,scope,i+1,offset,shape==3);constructors.put(CoreHiNames.id(con.name),con);constructors.put(CoreHiNames.id(worker(con.name)),con);if(shape==3)rhs=con.fields.getFirst();}
            bool(c);int parent=c.byteValue();c.require(parent<=1,"invalid data parent");if(parent==1){CoreHiNames.read(reader,c);binary.tycon(c);binary.arguments(c,0);}
        } else throw unsupported(c,"declaration tag "+tag);
        Declaration declaration=new Declaration(name,parameters,piKind(parameters,result),result,roles,form,rhs,null);
        typeDeclarations.put(name,declaration);
        if(form.equals("newtype")){var axiomName=new CoreHiReader.ExternalName(name.module(),3,null,"N:"+name.occurrence());pendingNewtypeAxioms.put(CoreHiNames.id(axiomName),name);}
        return name;
    }
    private static CoreHiReader.ExternalName localName(CoreHiReader.ExternalName parent,int namespace,String occurrence) {
        return new CoreHiReader.ExternalName(parent.module(),namespace,null,occurrence);
    }
    private void classDeclaration(CoreHiReader.Cursor c,CoreHiReader.ExternalName name,List<Object> context,
                                  Map<String,CoreHiTypes.Variable> scope,int offset,boolean abstractClass) {
        List<Integer> roles=roles(c);
        List<Parameter> parameters=parameters(c,scope);
        Ty constraint=con(new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Types"),3,null,"Constraint"),false);
        typeDeclarations.put(name,new Declaration(name,parameters,piKind(parameters,constraint),constraint,roles,"abstract-class",null,null));
        int count=c.count(2);
        for(int i=0;i<count;i++){localNames(c);localNames(c);}
        if(abstractClass){
            classes.put(name,new ClassProvider(null,parameters,List.of(),false));
            return;
        }
        count=c.count(2);
        for(int i=0;i<count;i++){
            declaration(c,scope);
            if(c.optional())type(c,0);
        }
        var fields=new ArrayList<Ty>();
        for(Object raw:context)fields.add(types.read(raw,scope));
        var selectors=new ArrayList<CoreHiReader.ExternalName>();
        for(int i=0;i<context.size();i++)selectors.add(localName(name,0,"$p"+(i+1)+name.occurrence()));
        count=c.count(2);
        for(int i=0;i<count;i++){
            var method=CoreHiNames.read(reader,c);
            selectors.add(method);
            fields.add(types.read(type(c,0),scope));
            if(c.optional()){
                int kind=c.byteValue();c.require(kind<=1,"invalid default method specification");
                if(kind==1)type(c,0);
            }
        }
        formula(c,0);
        boolean unary=bool(c);
        var variables=parameters.stream().map(Parameter::variable).toList();
        Ty result=con(name,false,variables.stream().map(v->(Ty)new Var(v)).toArray(Ty[]::new));
        var constructorName=localName(name,1,"C:"+name.occurrence());
        var dictionary=new Constructor(constructorName,result,variables,List.of(),List.copyOf(fields),result,
            Collections.nCopies(fields.size(),many()),Collections.nCopies(fields.size(),new Bang(0,null)),1,offset,false);
        constructors.put(CoreHiNames.id(constructorName),dictionary);
        constructors.put(CoreHiNames.id(worker(constructorName)),dictionary);
        typeDeclarations.put(name,new Declaration(name,parameters,piKind(parameters,constraint),constraint,roles,unary?"unary-class":"data",null,null));
        var provider=new ClassProvider(dictionary,parameters,List.copyOf(selectors),unary);
        classes.put(name,provider);
        for(int i=0;i<selectors.size();i++)implicitProviders.put(CoreHiNames.id(selectors.get(i)),new ImplicitProvider(provider,i));
        if(unary)implicitProviders.put(CoreHiNames.id(worker(constructorName)),new ImplicitProvider(provider,-1));
    }
    // Source-level class and pattern declarations must be framed, but their
    // defaults and matcher recipes are not executable Core. Actual matcher and
    // builder bodies have ordinary binding identities.
    private void localNames(CoreHiReader.Cursor c) {
        int count=c.count(1);for(int i=0;i<count;i++)reader.fastString(c);
    }
    private void formula(CoreHiReader.Cursor c,int depth) {
        c.require(depth<256,"minimal-definition formula nesting exceeds interface limit");
        int tag=c.byteValue();c.require(tag<=3,"invalid minimal-definition formula");
        if(tag==0){reader.fastString(c);return;}
        if(tag==3){formula(c,depth+1);return;}
        int count=c.count(1);for(int i=0;i<count;i++)formula(c,depth+1);
    }
    private void specificationBinders(CoreHiReader.Cursor c) {
        int count=c.count(2);
        for(int i=0;i<count;i++){
            binary.binder(c,0);
            int specificity=c.byteValue();c.require(specificity<=1,"invalid binder specificity");
        }
    }
    private void patternDeclaration(CoreHiReader.Cursor c,CoreHiReader.ExternalName name) {
        bool(c);
        CoreHiNames.read(reader,c);bool(c);
        if(c.optional()){CoreHiNames.read(reader,c);bool(c);}
        specificationBinders(c);
        specificationBinders(c);
        for(int list=0;list<3;list++){int count=c.count(1);for(int i=0;i<count;i++)type(c,0);}
        type(c,0);
        int count=c.count(3);for(int i=0;i<count;i++)fieldLabel(c,false);
        patterns.add(name);
    }
    private ClassProvider constraintTupleProvider(CoreHiNames.TupleFamily family) {
        var name=CoreHiNames.tupleName(2,family.arity(),3);
        var existing=classes.get(name);if(existing!=null)return existing;
        Declaration declaration=declaration(name);
        Constructor dictionary=constructor(CoreHiNames.tupleName(2,family.arity(),1));
        var selectors=new ArrayList<CoreHiReader.ExternalName>();
        for(int i=0;i<family.arity();i++)selectors.add(localName(name,0,"$p"+i+name.occurrence()));
        var provider=new ClassProvider(dictionary,declaration.parameters(),List.copyOf(selectors),false);
        classes.put(name,provider);
        for(int i=0;i<selectors.size();i++)implicitProviders.put(CoreHiNames.id(selectors.get(i)),new ImplicitProvider(provider,i));
        return provider;
    }
    private boolean unaryDictionary(Constructor dictionary) {
        return classes.values().stream().anyMatch(provider->provider.unary&&provider.dictionary==dictionary);
    }
    private Ty implicitSignature(ImplicitProvider implicit) {
        ClassProvider provider=implicit.owner;
        Ty result=implicit.selector<0?provider.dictionary.result:provider.dictionary.fields.get(implicit.selector);
        Ty argument=implicit.selector<0?provider.dictionary.fields.getFirst():provider.dictionary.result;
        result=types.function(many(),argument,result);
        for(int i=provider.parameters.size()-1;i>=0;i--){Parameter parameter=provider.parameters.get(i);int visibility=parameter.visibility().getFirst()==0||parameter.visibility().get(1)==0?1:parameter.visibility().get(1);result=new ForAll(parameter.variable(),visibility,result);}
        return result;
    }
    private Map<String,Object> implicitBinding(String id,ImplicitProvider implicit) {
        ClassProvider provider=implicit.owner;
        var scope=new HashMap<String,CoreHiTypes.Variable>();
        for(var parameter:provider.parameters)scope.put(parameter.variable().spelling(),parameter.variable());
        Ty signature=implicitSignature(implicit);
        Ty rho=signature;while(rho instanceof ForAll forall)rho=forall.body();
        Ty argument=((Fun)rho).argument();
        Binder dictionary=new Binder("$dictionary",argument,new Info(0,null,null),provider.dictionary.offset,false);
        Expr value=new Expr(0,values(dictionary.name),provider.dictionary.offset);
        Expr body=value;
        if(!provider.unary){
            var binders=new ArrayList<String>();for(int i=0;i<provider.dictionary.fields.size();i++)binders.add("$field"+i);
            String selected=binders.get(implicit.selector);
            Expr selectedValue=coercionType(provider.dictionary.fields.get(implicit.selector))?
                new Expr(2,values(values("varco",selected)),provider.dictionary.offset):new Expr(0,values(selected),provider.dictionary.offset);
            body=new Expr(6,values(value,"$case",List.of(new Alt(1,provider.dictionary.name,binders,selectedValue))),provider.dictionary.offset);
        }
        Expr lambda=new Expr(4,values(dictionary,body),provider.dictionary.offset);
        String occurrence=implicit.selector<0?worker(provider.dictionary.name).occurrence():provider.selectors.get(implicit.selector).occurrence();
        Binder binder=new Binder(occurrence,signature,new Info(1,null,null),provider.dictionary.offset,false);
        return binding(new Definition(binder,lambda),new Variable(id,signature),topScope,scope);
    }
    private static CoreHiReader.ExternalName worker(CoreHiReader.ExternalName name){return new CoreHiReader.ExternalName(name.module(),0,null,name.occurrence());}
    private List<Branch> branches(CoreHiReader.Cursor c){
        int n=c.count(7);var result=new ArrayList<Branch>();
        for(int i=0;i<n;i++){var scope=new HashMap<String,CoreHiTypes.Variable>();var variables=new ArrayList<CoreHiTypes.Variable>();
            for(int sort=0;sort<3;sort++){int size=c.count(1);for(int j=0;j<size;j++){Object raw;if(sort<2)raw=values(reader.fastString(c),false,type(c,0));else{type(c,0);String spelling=reader.fastString(c);raw=values(spelling,true,type(c,0));}var v=types.binder(raw,scope);scope.put(v.spelling(),v);if(sort!=1)variables.add(v);}}
            var lhs=new ArrayList<Ty>();for(Object raw:binary.arguments(c,0))lhs.add(types.read(((List<?>)raw).get(1),scope));List<Integer> roles=roles(c);Ty rhs=types.read(type(c,0),scope);int incompatible=c.count(0);for(int j=0;j<incompatible;j++)c.count(0);result.add(new Branch(variables,roles,lhs,rhs));}
        return List.copyOf(result);
    }
    private Constructor constructor(CoreHiReader.Cursor c,CoreHiReader.ExternalName parent,List<Parameter> parameters,Map<String,CoreHiTypes.Variable> parentScope,int tag,int offset,boolean newtype){
        var name=CoreHiNames.read(reader,c);boolean wrapper=bool(c);bool(c);var scope=new HashMap<>(parentScope);var existential=new ArrayList<CoreHiTypes.Variable>();int n=c.count(1);
        for(int i=0;i<n;i++){var v=types.binder(binary.binder(c,0),scope);scope.put(v.spelling(),v);existential.add(v);}
        n=c.count(2);var userBinders=new ArrayList<Parameter>();
        for(int i=0;i<n;i++){
            List<?> raw=(List<?>)binary.binder(c,0);var v=scope.get((String)raw.getFirst());
            c.require(v!=null,"constructor user binder has no universal/existential identity");
            userBinders.add(new Parameter(v,List.of(1,visibility(c))));
        }
        n=c.count(2);var equalities=new LinkedHashMap<CoreHiTypes.Variable,Ty>();for(int i=0;i<n;i++){String var=reader.fastString(c);equalities.put(scope.get(var),types.read(type(c,0),scope));}
        List<Ty> context=typeList(c,scope);n=c.count(2);var fields=new ArrayList<Ty>();var multiplicities=new ArrayList<Ty>();var impl=new ArrayList<Bang>();
        fields.addAll(context);multiplicities.addAll(Collections.nCopies(context.size(),many()));impl.addAll(Collections.nCopies(context.size(),new Bang(0,null)));
        for(int i=0;i<n;i++){multiplicities.add(types.read(type(c,0),scope));fields.add(types.read(type(c,0),scope));}
        int labels=c.count(3);for(int i=0;i<labels;i++)fieldLabel(c,false);
        int bangs=c.count(1);for(int i=0;i<bangs;i++){int bang=c.byteValue();c.require(bang<=3,"invalid constructor bang");impl.add(new Bang(bang,bang==3?types.readCo(binary.coercion(c,0),scope):null));}
        if(bangs==0)impl.addAll(Collections.nCopies(n,new Bang(0,null)));c.require(impl.size()==fields.size(),"constructor implementation bang count mismatch");
        int source=c.count(2);for(int i=0;i<source;i++){c.require(c.byteValue()<=2&&c.byteValue()<=2,"invalid source bang");}
        var universals=parameters.stream().map(Parameter::variable).toList();Ty result=con(parent,false,universals.stream().map(v->(Ty)new Var(v)).toArray(Ty[]::new));Ty signature=result;
        for(int i=fields.size()-1;i>=0;i--)signature=new Fun(0,multiplicities.get(i),fields.get(i),signature);
        for(int i=existential.size()-1;i>=0;i--)signature=new ForAll(existential.get(i),1,signature);
        for(int i=universals.size()-1;i>=0;i--)signature=new ForAll(universals.get(i),1,signature);
        // Promotion uses source arguments and the original refined result,
        // never the flattened worker representation or equality evidence.
        Ty promotedResult = substitute(result, equalities);
        var promotedParameters = new ArrayList<>(userBinders);
        for (Ty field : fields.subList(context.size(),fields.size())) {
            var v = new CoreHiTypes.Variable("$promoted",field,false);
            promotedParameters.add(new Parameter(v,List.of(0)));
        }
        var promotedRoles = new ArrayList<Integer>();
        for (var v : universals) promotedRoles.add(v.coercion()?3:1);
        for (var v : existential) promotedRoles.add(v.coercion()?3:1);
        promotedRoles.addAll(Collections.nCopies(fields.size(),2));
        typeDeclarations.put(name,new Declaration(name,promotedParameters,piKind(promotedParameters,promotedResult),promotedResult,promotedRoles,"promoted",null,null));
        return new Constructor(name,result,universals,existential,List.copyOf(fields),signature,List.copyOf(multiplicities),List.copyOf(impl),tag,offset,newtype,Collections.unmodifiableMap(new LinkedHashMap<>(equalities)));
    }
    private Constructor constructor(CoreHiReader.ExternalName name){
        String id=CoreHiNames.id(name);Constructor found=constructors.get(id);if(found!=null)return found;if(declarations.containsKey(id))return null;
        var family=CoreHiNames.tupleFamily(name);
        if(family!=null){var parent=CoreHiNames.tupleName(family.sort(),family.arity(),3);Declaration decl=declaration(parent);var variables=decl.parameters().stream().map(Parameter::variable).toList();int start=family.sort()==1?family.arity():0;
            var fields=variables.subList(start,variables.size()).stream().map(v->(Ty)new Var(v)).toList();Ty result=con(parent,false,variables.stream().map(v->(Ty)new Var(v)).toArray(Ty[]::new));Ty signature=result;
            for(int i=fields.size()-1;i>=0;i--)signature=new Fun(0,many(),fields.get(i),signature);for(int i=variables.size()-1;i>=0;i--)signature=new ForAll(variables.get(i),1,signature);
            found=new Constructor(CoreHiNames.tupleName(family.sort(),family.arity(),1),result,variables,List.of(),fields,signature,Collections.nCopies(fields.size(),many()),Collections.nCopies(fields.size(),new Bang(0,null)),1,0,false);constructors.put(id,found);tuples.add(family);return found;
        }
        var sum = CoreHiNames.sumFamily(name);
        if (sum != null && sum.alternative() >= 0) {
            var parent = CoreHiNames.sumName(sum.arity(),-1,3);
            Declaration declaration = declaration(parent);
            var variables = declaration.parameters().stream().map(Parameter::variable).toList();
            Ty field = new Var(variables.get(sum.arity()+sum.alternative()));
            Ty result = con(parent,false,variables.stream().map(v -> (Ty)new Var(v)).toArray(Ty[]::new));
            Ty signature = new Fun(0,many(),field,result);
            for (int i = variables.size()-1; i >= 0; i--) signature = new ForAll(variables.get(i),1,signature);
            var dataName = CoreHiNames.sumName(sum.arity(),sum.alternative(),1);
            found = new Constructor(dataName,result,variables,List.of(),List.of(field),signature,List.of(many()),List.of(new Bang(0,null)),sum.alternative()+1,0,false);
            constructors.put(id,found);
            return found;
        }
        Map<?,?> wired=CoreHiNames.constructor(name);
        if(wired!=null){var scope=new HashMap<String,CoreHiTypes.Variable>();var universal=new ArrayList<CoreHiTypes.Variable>();var existential=new ArrayList<CoreHiTypes.Variable>();
            for(Object raw:(List<?>)wired.get("universal")){var v=types.binder(raw,scope);scope.put(v.spelling(),v);universal.add(v);}for(Object raw:(List<?>)wired.get("existential")){var v=types.binder(raw,scope);scope.put(v.spelling(),v);existential.add(v);}
            var fields=((List<?>)wired.get("fields")).stream().map(raw->types.read(raw,scope)).toList();Ty signature=types.read(wired.get("type"),Map.of()),result=signature;while(result instanceof ForAll forall)result=forall.body();while(result instanceof Fun fun)result=fun.result();
            found=new Constructor(CoreHiTypes.name(wired.get("name")),result,universal,existential,fields,signature,((List<?>)wired.get("multiplicities")).stream().map(m->types.read(m,scope)).toList(),((List<?>)wired.get("strictFields")).stream().skip(existential.stream().filter(CoreHiTypes.Variable::coercion).count()).map(b->new Bang((Boolean)b?1:0,null)).toList(),((Number)wired.get("tag")).intValue(),0,false);constructors.put(id,found);return found;
        }
        var module=dependencyModules.apply(id);return module==null||module==this?null:module.constructor(name);
    }
    private CoreHiReader.ExternalName fieldLabel(CoreHiReader.Cursor c, boolean requireSelector) {
        bool(c); // DuplicateRecordFields affects name lookup, not retained Core execution.
        boolean hasSelector = bool(c);
        c.require(!requireSelector || hasSelector, "record field has no selector");
        var selector = CoreHiNames.read(reader, c);
        c.require(selector.namespace() == 4 && selector.module().equals(reader.module), "invalid record field identity");
        return selector;
    }
    private List<Boolean> details(CoreHiReader.Cursor c, CoreHiReader.ExternalName declaration) {
        int tag = c.byteValue();
        if (tag == 0 || tag == 3) return null;
        if (tag == 1) {
            int parent=c.byteValue();c.require(parent<=1,"invalid record selector parent");
            if(parent==0){
                var tycon=binary.tycon(c);
                c.require(((CoreHiReader.ExternalName)tycon.getFirst()).namespace()==3,"invalid record selector type parent");
            } else {
                c.require(c.byteValue()==7,"record pattern parent is not a pattern synonym");
                var name=CoreHiNames.read(reader,c);
                patternDeclaration(c,name);
            }
            var first=CoreHiNames.read(reader,c);
            c.require(first.namespace()==1,"invalid record selector constructor identity");
            bool(c); // Naughty selectors retain their actual ordinary declarations/bodies.
            var selector=fieldLabel(c,true);
            c.require(selector.fieldParent().equals(first.occurrence()),"record selector constructor identity mismatch");
            c.require(declaration==null||declaration.equals(selector),"record selector declaration identity mismatch");
            return null;
        }
        if (tag != 2) throw unsupported(c, "IdDetails tag " + tag);
        int count = c.count(1);
        var marks = new ArrayList<Boolean>(count);
        for (int i = 0; i < count; i++) marks.add(bool(c));
        return List.copyOf(marks);
    }
    private Info info(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "IdInfo nesting exceeds native Core limit");
        int arity = 0, count = c.count(1);
        for (int i = 0; i < count; i++) switch (c.byteValue()) {
            case 0 -> arity = c.count(0);
            case 1 -> { int divergence = c.byteValue(); c.require(divergence <= 2, "invalid demand divergence");
                int demands = c.count(1); for (int j = 0; j < demands; j++) demand(c, depth + 1); }
            case 2 -> {
                bool(c);
                int tag = c.byteValue();
                if(tag==1){int binders=c.count(1);for(int j=0;j<binders;j++)lambdaBinder(c,depth+1);int expressions=c.count(1);for(int j=0;j<expressions;j++)expr(c,depth+1,false);break;}
                c.require(tag==0,"invalid unfolding tag");
                c.require(c.byteValue() <= 3, "invalid unfolding source");
                c.require(c.byteValue() <= 15, "invalid unfolding cache");
                int guidance = c.byteValue(); c.require(guidance <= 1, "invalid unfolding guidance");
                if (guidance == 1) { c.count(0); bool(c); bool(c); }
                expr(c, depth + 1, false); // retained RHS is the execution authority
            }
            case 3 -> { sourceText(c);
                int spec = c.byteValue(); c.require(spec <= 4, "invalid inline specification");
                if (spec != 0) sourceText(c);
                if (c.optional()) c.count(0);
                int activation = c.byteValue(); c.require(activation <= 4, "invalid inline activation");
                if (activation >= 3) { sourceText(c); c.signed(); }
                c.require(c.byteValue() <= 1, "invalid rule match info"); }
            case 4 -> {}
            case 6 -> { c.count(0); cpr(c, depth + 1); }
            case 7 -> { int tag = c.byteValue(); switch (tag) {
                case 0 -> c.count(0); case 1 -> { bool(c); bool(c); }
                case 2 -> CoreHiNames.read(reader, c); case 3 -> bool(c); case 4 -> {}
                default -> throw unsupported(c, "lambda-form info tag " + tag);
            } }
            case 8 -> tagInfo(c, depth + 1);
            default -> throw unsupported(c, "IdInfo item");
        }
        return new Info(arity, null, null);
    }
    private void sourceText(CoreHiReader.Cursor c) { if (c.optional()) reader.fastString(c); }
    private void demand(CoreHiReader.Cursor c, int depth) {
        int card = c.byteValue(); c.require(card <= 5, "invalid demand cardinality");
        if (card != 0 && card != 5) subDemand(c, depth + 1);
    }
    private void subDemand(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "demand nesting exceeds native Core limit");
        switch (c.byteValue()) {
            case 0 -> { bool(c); c.require(c.byteValue() <= 5, "invalid polymorphic demand cardinality"); }
            case 1 -> { c.require(c.byteValue() <= 5, "invalid call demand cardinality"); subDemand(c, depth + 1); }
            case 2 -> { bool(c); int count = c.count(1); for (int i = 0; i < count; i++) demand(c, depth + 1); }
            default -> throw unsupported(c, "sub-demand tag");
        }
    }
    private void cpr(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "CPR nesting exceeds native Core limit");
        int tag = c.byteValue(); c.require(tag <= 3, "invalid CPR tag");
        if (tag >= 2) c.count(0);
        if (tag == 3) { int count = c.count(1); for (int i = 0; i < count; i++) cpr(c, depth + 1); }
    }
    private void tagInfo(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "tag signature nesting exceeds native Core limit");
        int tag = c.byteValue(); c.require(tag >= 1 && tag <= 4, "invalid tag signature");
        if (tag == 2) { int count = c.count(1); for (int i = 0; i < count; i++) tagInfo(c, depth + 1); }
    }
    private Binder lambdaBinder(CoreHiReader.Cursor c, int depth) {
        int offset = c.position(), tag = c.byteValue();
        if (tag == 0) {
            Object multiplicity=type(c,depth+1);
            String name = reader.fastString(c);
            return new Binder(name, type(c, depth + 1), new Info(0, null, null), offset, false,multiplicity);
        }
        c.require(tag == 1, "invalid lambda binder tag");
        return new Binder(reader.fastString(c), type(c, depth + 1), new Info(0, null, null), offset, true);
    }
    private Binder bindingBinder(CoreHiReader.Cursor c, boolean top, int depth) {
        int offset = c.position();
        if (top) {
            int tag = c.byteValue();
            if (tag == 1) {
                var name = CoreHiNames.read(reader, c);

                Binder declaration = declarations.get(CoreHiNames.id(name));
                c.require(declaration != null, "retained global binder has no IfaceId declaration " + CoreHiNames.id(name));
                return declaration;
            }
            c.require(tag == 0, "invalid retained top binder tag");
        }
        String name = reader.fastString(c);
        Object type = type(c, depth + 1);
        Info info = info(c, depth + 1);
        if (top) return new Binder(name, type, new Info(info.arity, details(c, null), null), offset, false);
        int join = c.byteValue(); c.require(join <= 1, "invalid join point tag");
        return new Binder(name, type, new Info(info.arity, null, join == 1 ? c.count(0) : null), offset, false);
    }
    private Group group(CoreHiReader.Cursor c, boolean top, int depth, boolean executionFacts) {
        int tag = c.byteValue(); c.require(tag <= 1, "invalid binding group tag");
        int count = tag == 0 ? 1 : c.count(2);
        c.require(count > 0, "empty recursive binding group");
        var definitions = new ArrayList<Definition>(count);
        for (int i = 0; i < count; i++) {
            Binder binder = bindingBinder(c, top, depth + 1);
            if (top) c.require(c.byteValue()==1,"retained IfUseUnfoldingRhs requires an available public unfolding");
            definitions.add(new Definition(binder, expr(c, depth + 1, executionFacts)));
        }
        return new Group(tag == 1, List.copyOf(definitions));
    }
    private Expr expr(CoreHiReader.Cursor c, int depth, boolean executionFacts) {
        c.require(depth < 256, "expression nesting exceeds native Core limit");
        int offset = c.position(), tag = c.byteValue();
        if (tag == 8) {
            tick(c, depth + 1);
            return expr(c, depth + 1, executionFacts);
        }
        List<Object> fields = switch (tag) {
            case 0 -> values(reader.fastString(c));
            case 1 -> values(type(c, depth + 1));
            case 3 -> {
                int sort=c.byteValue();c.require(sort<=2,"invalid tuple sort");
                int count=c.count(1);if (executionFacts) tuples.add(new CoreHiNames.TupleFamily(sort,count));
                var components = new ArrayList<Expr>(count);
                for (int i = 0; i < count; i++) components.add(expr(c, depth + 1, executionFacts));
                yield values(sort,List.copyOf(components));
            }
            case 4 -> {
                Binder binder = lambdaBinder(c, depth + 1);
                bool(c); yield values(binder, expr(c, depth + 1, executionFacts));
            }
            case 5 -> values(expr(c, depth + 1, executionFacts), expr(c, depth + 1, executionFacts));
            case 6 -> {
                Expr scrutinee = expr(c, depth + 1, executionFacts); String name = reader.fastString(c);
                int count = c.count(3); var alts = new ArrayList<Alt>(count);
                c.require(count > 0, "IfaceCase has no alternatives");
                for (int i = 0; i < count; i++) {
                    int alt = c.byteValue(); c.require(alt <= 2, "invalid alternative tag");
                    Object discriminator = alt == 1 ? CoreHiNames.read(reader, c) : alt == 2 ? literal(c) : null;
                    if (alt == 1) {
                        var constructorName = (CoreHiReader.ExternalName) discriminator;
                        c.require(constructorName.namespace() == 1,"invalid data alternative identity");
                        if (executionFacts) rememberCompilerConstructor(constructorName);
                    }
                    int binders = c.count(1); var names = new ArrayList<String>(binders);
                    for (int j = 0; j < binders; j++) names.add(reader.fastString(c));
                    alts.add(new Alt(alt, discriminator, List.copyOf(names), expr(c, depth + 1, executionFacts)));
                }
                yield values(scrutinee, name, List.copyOf(alts));
            }
            case 7 -> values(group(c, false, depth + 1, executionFacts), expr(c, depth + 1, executionFacts));
            case 9 -> values(literal(c));
            case 12 -> values(expr(c,depth+1,executionFacts),binary.coercion(c,depth+1));
            case 2 -> values(binary.coercion(c,depth+1));
            case 13 -> values(expr(c,depth+1,executionFacts),type(c,depth+1));
            case 14 -> {Object rr=type(c,depth+1);int torc=c.byteValue();c.require(torc<=1,"invalid rubbish type/constraint");yield values(values("con",new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Prim"),3,null,torc==0?"TYPE":"CONSTRAINT"),false,List.of(0),List.of(values(0,rr))));}
            case 10 -> {
                String call = foreignCall(c);
                if (executionFacts) { scalarDeclarations = true; foreignObligations.add(call); }
                yield values(call, type(c, depth + 1));
            }
            case 11 -> {
                var name = CoreHiNames.read(reader, c);

                if (executionFacts) {
                    control|=CoreHiNames.isPrimop(name)&&Set.of("prompt#","control0#").contains(name.occurrence());
                    rememberCompilerConstructor(name);
                }
                yield values(name);
            }
            default -> throw unsupported(c, "expression tag " + tag);
        };
        return new Expr(tag, fields, offset);
    }
    /** Compiler families have no ordinary interface owner. Reserve every
     * referenced constructor before metadata publication, including case-only
     * alternatives; ordinary constructors remain owned by their declarations. */
    private void rememberCompilerConstructor(CoreHiReader.ExternalName name) {
        var selector=CoreHiNames.constraintTupleSelector(name);
        if(selector!=null)constraintTupleProvider(selector.family());
        else if (CoreHiNames.constructor(name) != null || CoreHiNames.tupleFamily(name) != null || CoreHiNames.sumFamily(name) != null)
            constructor(name);
    }
    /** THC.Plugin erases ticks; only optional display data consumes their notes.
     * Decode the complete payload without promoting breakpoint captures to code. */
    private void tick(CoreHiReader.Cursor c, int depth) {
        int tag = c.byteValue();
        switch (tag) {
            case 0 -> { reader.module(c); c.signed(); } // HPC module/index.
            case 1 -> {
                int centre = c.byteValue(); c.require(centre <= 1, "invalid cost centre tag");
                if (centre == 0) {
                    int flavour = c.byteValue(); c.require(flavour <= 1, "invalid cost centre flavour");
                    if (flavour == 1) {
                        long indexed = c.signed(); c.require(indexed >= 0 && indexed <= 4, "invalid indexed cost centre flavour");
                        c.signed();
                    }
                    reader.fastString(c);
                }
                reader.module(c);
                bool(c); bool(c); // Counting and scoping flags follow the exporter erasure law.
            }
            case 2 -> {
                reader.fastString(c);
                for (int i = 0; i < 4; i++) c.signed(); // Source span coordinates.
                reader.fastString(c);
            }
            case 3 -> {
                reader.module(c); c.signed();
                int count = c.count(1);
                for (int i = 0; i < count; i++) expr(c, depth + 1, false);
            }
            default -> throw unsupported(c, "tick tag " + tag);
        }
    }

    private Literal literal(CoreHiReader.Cursor c) {
        int tag = c.byteValue();
        if (tag == 0) {
            long point = c.unsigned(32);
            c.require(point <= 0x10ffff, "out-of-range GHC Char literal");
            return new Literal("char", Long.toString(point), con(new CoreHiReader.ExternalName(
                    new CoreHiReader.ModuleId("ghc-internal", "GHC.Internal.Prim"), 3, null, "Char#"), false));
        }
        if (tag == 1) {
            int size = c.count(1);
            c.require(size <= Integer.MAX_VALUE / 2, "byte literal exceeds hexadecimal string size");
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) c.byteValue();
            return new Literal("string-bytes", HexFormat.of().formatHex(bytes), primitive("AddrRep"));
        }
        if (tag == 3 || tag == 4) {
            BigInteger numerator = integer(c), denominator = integer(c);
            c.require(denominator.signum() >= 0 || numerator.signum() == 0, "negative GHC Rational denominator");
            long bits = floatingBits(numerator, denominator, tag == 3 ? 24 : 53, tag == 3 ? 8 : 11);
            return new Literal(tag == 3 ? "float" : "double", tag == 3 ? new CoreFloatingLiteral.Single((int) bits) :
                    new CoreFloatingLiteral.Double(bits), primitive(tag == 3 ? "FloatRep" : "DoubleRep"));
        }
        c.require(tag == 6, "unsupported native Core literal tag " + tag);
        int number = c.byteValue(); c.require(number >= 1 && number <= 10, "unsupported numeric literal type " + number);
        BigInteger value = integer(c);
        String[] kinds = {"", "int", "int8", "int16", "int32", "int64", "word", "word8", "word16", "word32", "word64"};
        String[] reps = {"", "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep", "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep"};
        int width = switch (number) { case 2, 7 -> 8; case 3, 8 -> 16; case 4, 9 -> 32; default -> 64; };
        BigInteger bound = BigInteger.ONE.shiftLeft(number < 6 ? width - 1 : width);
        c.require(value.compareTo(number < 6 ? bound.negate() : BigInteger.ZERO) >= 0 && value.compareTo(bound) < 0,
                "out-of-range scalar literal");
        return new Literal(kinds[number], value.toString(), primitive(reps[number]));
    }

    private BigInteger integer(CoreHiReader.Cursor c) {
        int integerTag = c.byteValue();
        c.require(integerTag <= 2, "invalid GHC Integer tag");
        BigInteger value;
        if (integerTag == 0) value = BigInteger.valueOf(c.signed());
        else {
            int size = c.count(1);
            c.require(size > 0, "empty GHC Integer magnitude");
            byte[] magnitude = new byte[size];
            for (int i = size - 1; i >= 0; i--) magnitude[i] = (byte) c.byteValue();
            c.require(magnitude[0] != 0, "noncanonical GHC Integer magnitude");
            value = new BigInteger(1, magnitude);
            if (integerTag == 1) value = value.negate();
            c.require(value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                    "noncanonical large GHC Integer");
        }
        return value;
    }
    private static long floatingBits(BigInteger numerator, BigInteger denominator, int precision, int exponentBits) {
        int fraction = precision - 1, maximum = (1 << (exponentBits - 1)) - 1, minimum = 1 - maximum;
        long sign = numerator.signum() < 0 ? 1L << (fraction + exponentBits) : 0;
        long infinity = ((1L << exponentBits) - 1) << fraction;
        // GHC's rationalToFloat/Double defines denominator zero explicitly; Rational carries no signed zero.
        if (denominator.signum() == 0) return numerator.signum() == 0 ? infinity | 1L << (fraction - 1) : sign | infinity;
        if (numerator.signum() == 0) return 0;
        numerator = numerator.abs();
        int exponent = numerator.bitLength() - denominator.bitLength();
        int subnormalUnit = minimum - fraction;
        // The exact floor exponent is either this bit-length difference or one less. Bound shifts first.
        if (exponent > maximum + 1) return sign | infinity;
        if (exponent < subnormalUnit - 1) return sign;
        int comparison = exponent >= 0 ? numerator.compareTo(denominator.shiftLeft(exponent)) :
                numerator.shiftLeft(-exponent).compareTo(denominator);
        if (comparison < 0) exponent--;
        if (exponent > maximum) return sign | infinity;
        int unit = Math.max(exponent, minimum) - fraction;
        BigInteger dividend = unit < 0 ? numerator.shiftLeft(-unit) : numerator;
        BigInteger divisor = unit < 0 ? denominator : denominator.shiftLeft(unit);
        var division = dividend.divideAndRemainder(divisor);
        BigInteger mantissa = division[0];
        int rounding = division[1].shiftLeft(1).compareTo(divisor);
        if (rounding > 0 || rounding == 0 && mantissa.testBit(0)) mantissa = mantissa.add(BigInteger.ONE);
        // Subnormal units encode directly, including rounding into the smallest normal value.
        if (exponent < minimum) return sign | mantissa.longValueExact();
        if (mantissa.bitLength() > precision) { mantissa = mantissa.shiftRight(1); exponent++; }
        if (exponent > maximum) return sign | infinity;
        return sign | ((long) (exponent + maximum) << fraction) | (mantissa.longValueExact() - (1L << fraction));
    }

    private record WorkerFields(List<Ty> fields,List<Ty> multiplicities,List<Boolean> strict) {
        WorkerFields {fields=List.copyOf(fields);multiplicities=List.copyOf(multiplicities);strict=List.copyOf(strict);}
    }
    private WorkerFields workerFields(Constructor constructor) {
        return workerFields(constructor,new HashSet<>());
    }
    private WorkerFields workerFields(Constructor constructor,Set<CoreHiReader.ExternalName> active) {
        if (!active.add(constructor.name))
            throw new IllegalArgumentException("Cyclic unpacked worker layout " + CoreHiNames.id(constructor.name));
        var fields = new ArrayList<Ty>();
        var multiplicities = new ArrayList<Ty>();
        var strict = new ArrayList<Boolean>();
        try {
            // Parsing reserves source facts only. Evidence kinds and unpacked
            // layouts may depend on declarations appearing later or elsewhere.
            for (var equality : constructor.equalities.entrySet()) {
                fields.add(types.nominalEquality(new Var(equality.getKey()),equality.getValue()));
                multiplicities.add(many());
                strict.add(false);
            }
            for (int i = 0; i < constructor.fields.size(); i++) {
                Ty field = constructor.fields.get(i);
                Ty multiplicity = constructor.multiplicities.get(i);
                Bang bang = constructor.bangs.get(i);
                if (bang.tag < 2) {
                    fields.add(field);
                    multiplicities.add(multiplicity);
                    strict.add(bang.tag == 1);
                    continue;
                }
                if (bang.coercion != null) field = types.endpoints(bang.coercion).get(1);
                Ty normalized = types.view(field,false);
                if (!(normalized instanceof Con con))
                    throw new IllegalArgumentException("UNPACK field has no declared data TyCon " + field);
                List<Constructor> choices = constructors(con.name());
                if (choices.isEmpty())
                    throw new IllegalArgumentException("UNPACK field lacks its data constructors " + CoreHiNames.id(con.name()));
                var payloads = new ArrayList<Ty>();
                for (Constructor child : choices) {
                    WorkerFields layout = workerFields(child,active);
                    var substitution = new HashMap<CoreHiTypes.Variable,Ty>();
                    for (int j = 0; j < child.universal.size(); j++)
                        substitution.put(child.universal.get(j),con.arguments().get(j).type());
                    var instantiated = layout.fields.stream().map(t -> substitute(t,substitution)).toList();
                    if (choices.size() == 1) {
                        fields.addAll(instantiated);
                        for (Ty m : layout.multiplicities)
                            multiplicities.add(types.multiplyMultiplicity(multiplicity,substitute(m,substitution)));
                        strict.addAll(layout.strict);
                    } else {
                        payloads.add(instantiated.size() == 1 ? instantiated.getFirst() : aggregate(1,instantiated));
                    }
                }
                if (choices.size() != 1) {
                    fields.add(aggregate(2,payloads));
                    multiplicities.add(multiplicity);
                    strict.add(true);
                }
            }
        } finally {
            active.remove(constructor.name);
        }
        return new WorkerFields(fields,multiplicities,strict);
    }
    private Ty aggregate(int sort,List<Ty> components){int n=components.size();var args=new ArrayList<Arg>();for(Ty type:components)args.add(new Arg(types.runtimeRep(type),1));for(Ty type:components)args.add(new Arg(type,0));var name=sort==1?CoreHiNames.tupleName(1,n,3):new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Types"),3,null,"Sum"+n+"#");return new Con(name,false,new Sort(sort,n,sort==1?1:0),args);}
    private List<Constructor> constructors(CoreHiReader.ExternalName parent){
        var wired=CoreHiNames.typeDeclaration(parent);if(wired!=null){var result=new ArrayList<Constructor>();for(Object raw:(List<?>)wired.get("constructors"))result.add(constructor(CoreHiTypes.name(((Map<?,?>)raw).get("name"))));return List.copyOf(result);}
        var family=CoreHiNames.tupleFamily(parent);if(family!=null)return List.of(constructor(CoreHiNames.tupleName(family.sort(),family.arity(),1)));
        CoreHiModule module=owner(CoreHiNames.id(parent));if(module==null)return List.of();return new LinkedHashSet<>(module.constructors.values()).stream().filter(c->c.result instanceof Con con&&con.name().equals(parent)).toList();
    }
    private Ty workerSignature(Constructor constructor){
        if(constructor.newtype)return constructor.signature;
        WorkerFields layout=workerFields(constructor);Ty signature=constructor.result;
        for(int i=layout.fields.size()-1;i>=0;i--)signature=types.function(layout.multiplicities.get(i),layout.fields.get(i),signature);
        for(int i=constructor.existential.size()-1;i>=0;i--)signature=new ForAll(constructor.existential.get(i),1,signature);
        for(int i=constructor.universal.size()-1;i>=0;i--)signature=new ForAll(constructor.universal.get(i),1,signature);
        return signature;
    }
    private Map<String,Object> binding(Definition definition,Variable variable,Map<String,Variable> scope,Map<String,CoreHiTypes.Variable> typeScope){
        Lowered rhs=lower(definition.rhs,scope,typeScope);var record=binder(definition.binder,variable,false);
        record.put("arity",(long)definition.binder.info.arity);record.put("expr",rhs.expression);record.put("rep",metadata(rhs.expression).get("rep"));
        int arity=rhs.expression.getFirst().equals("lam")?((List<?>)rhs.expression.get(1)).size():0;
        var marks=definition.binder.info.marks;var strict=new ArrayList<Boolean>();for(int i=0;i<arity;i++)strict.add(marks!=null&&i<marks.size()&&marks.get(i));
        record.put("entryStrict",strict);record.put("entryStrictSource",strict.contains(true)?"ghc-id":"none");
        if(arity>0){metadata(rhs.expression).put("entryStrict",strict);metadata(rhs.expression).put("entryStrictSource",record.get("entryStrictSource"));}
        if (definition.binder.info.join != null) {
            int valueArity = 0;
            Expr remaining = definition.rhs;
            var prefixScope = new HashMap<>(scope);
            var prefixTypes = new HashMap<>(typeScope);
            for (int i = 0; i < definition.binder.info.join; i++) {
                if (remaining.tag != 4) throw error(remaining,"join arity exceeds raw lambda prefix");
                Binder b = (Binder) remaining.fields.getFirst();
                Ty type = types.read(b.type,prefixTypes);
                if (b.typeVariable || coercionType(type)) {
                    var v = new CoreHiTypes.Variable(b.name,type,!b.typeVariable);
                    prefixTypes.put(b.name,v);
                    if (v.coercion()) {
                        prefixScope.put(b.name,new Variable(local(),type));
                        valueArity++;
                    }
                } else {
                    prefixScope.put(b.name,new Variable(local(),type));
                    valueArity++;
                }
                remaining = (Expr) remaining.fields.get(1);
            }
            // A raw join prefix may leave further lambdas. Its result carrier
            // belongs to that remaining expression, not the flattened body.
            Lowered result = lower(remaining,prefixScope,prefixTypes);
            record.put("joinValueArity",(long)valueArity);
            metadata(rhs.expression).put("joinValueArity",(long)valueArity);
            record.put("joinResultRep",metadata(result.expression).get("rep"));
        }
        return record;
    }
    private boolean coercionType(Ty type){Ty viewed=types.view(type,false);return viewed instanceof Con con&&con.name().module().name().equals("GHC.Internal.Prim")&&Set.of("~#","~R#","~P#").contains(con.name().occurrence());}
    private Map<String,Object> binder(Binder binder,Variable variable,boolean evaluated){
        var info=map("cbvEligible",binder.info.marks!=null||binder.info.join!=null);if(binder.info.marks!=null)info.put("cbvMarks",binder.info.marks);if(binder.info.join!=null)info.put("joinArity",(long)binder.info.join);
        return map("id",variable.id,"name",binder.name,"type",entryType(variable.type),"lifted",lifted(variable.type),"coercion",coercionType(variable.type),"rep",rep(variable.type,evaluated,new Expr(-1,List.of(),binder.offset)),"info",info);
    }
    private String local(){return "\u0000hi-local:"+prefix()+localOrdinal++;}
    private boolean unaryIdentity(Expr head) {
        if(head.tag!=11)return false;
        var name=(CoreHiReader.ExternalName)head.fields.getFirst();
        String id=CoreHiNames.id(name);
        var implicit=implicitProviders.get(id);
        if(implicit==null&&CoreHiNames.signature(name)==null&&CoreHiNames.constructor(name)==null&&CoreHiNames.tupleFamily(name)==null&&CoreHiNames.sumFamily(name)==null){
            CoreHiModule provider=owner(id);
            if(provider!=null)implicit=provider.implicitProviders.get(id);
        }
        return implicit!=null&&implicit.owner.unary;
    }
    private Lowered lower(Expr e,Map<String,Variable> scope,Map<String,CoreHiTypes.Variable> typeScope){
        return switch(e.tag){
            case 0->{var variable=scope.get((String)e.fields.getFirst());if(variable==null)throw error(e,"unbound retained local "+e.fields.getFirst());yield expression(variable.type,e,"var",variable.id);}
            case 11->{var name=(CoreHiReader.ExternalName)e.fields.getFirst();String id=CoreHiNames.id(name);Object wired=CoreHiNames.signature(name);
                var implicit=implicitProviders.get(id);
                if(implicit!=null){
                    // Algorithmic compiler families have no .hi body owner.
                    // Expand their first-class template here, as THC.Wired does;
                    // user class selectors retain their real declaration owner.
                    var family=CoreHiNames.tupleFamily(implicit.owner.dictionary.name);
                    if(family!=null&&family.sort()==2){
                        @SuppressWarnings("unchecked") var body=(List<Object>)implicitBinding(id,implicit).get("expr");
                        yield new Lowered(body,implicitSignature(implicit));
                    }
                    yield expression(implicitSignature(implicit),e,"var",id);
                }
                if(wired!=null){Ty type=types.read(wired,Map.of());
                    if(name.equals(new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Prim"),0,null,"realWorld#"))||name.equals(new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Prim"),0,null,"proxy#")))yield expression(type,e,"void");
                    if(CoreHiNames.isPrimop(name))yield expression(type,e,"prim",name.occurrence());
                }
                Constructor constructor=wired!=null&&CoreHiNames.constructor(name)==null?null:constructor(name);Ty type=constructor==null?(wired==null?null:types.read(wired,Map.of())):workerSignature(constructor);
                CoreHiModule defining=null;
                if(type==null){defining=owner(id);type=defining==null?null:defining.signature(id);}
                if(type==null)throw error(e,"missing declaration of "+id);
                if(implicitProviders.containsKey(id))yield expression(type,e,"var",id);
                if(constructor!=null&&CoreHiNames.constructor(name)==null&&CoreHiNames.tupleFamily(name)==null&&CoreHiNames.sumFamily(name)==null)
                    defining=owner(CoreHiNames.id(constructor.name));
                if(defining!=null&&defining.implicitProviders.containsKey(id))yield expression(type,e,"var",id);
                if(constructor!=null&&!constructor.newtype)yield expression(type,e,"con",CoreHiNames.id(constructor.name),(long)(workerFields(constructor).fields.size()+constructor.existential.stream().filter(CoreHiTypes.Variable::coercion).count()));
                yield expression(type,e,"var",id);
            }
            case 3->{int sort=(Integer)e.fields.getFirst();var args=new ArrayList<List<Object>>();var componentTypes=new ArrayList<Ty>();for(Object raw:(List<?>)e.fields.get(1)){Lowered value=lower((Expr)raw,scope,typeScope);args.add(value.expression);componentTypes.add(value.type);}
                int n=args.size();var name=CoreHiNames.tupleName(sort,n,1);tuples.add(new CoreHiNames.TupleFamily(sort,n));var typeArgs=new ArrayList<Arg>();
                if(sort==1)for(Ty component:componentTypes)typeArgs.add(new Arg(types.runtimeRep(component),1));for(Ty component:componentTypes)typeArgs.add(new Arg(component,0));
                Ty type=new Con(CoreHiNames.tupleName(sort,n,3),false,new Sort(1,n,sort),typeArgs),signature=type;
                for(int i=n-1;i>=0;i--)signature=new Fun(0,many(),componentTypes.get(i),signature);
                Lowered head=expression(signature,e,"con",CoreHiNames.id(name),(long)n);
                yield expression(type,e,"app",head.expression,args,componentTypes.stream().map(this::lifted).toList(),false,false);
            }
            case 12->{Lowered body=lower((Expr)e.fields.getFirst(),scope,typeScope);Co co=types.readCo(e.fields.get(1),typeScope);var endpoints=types.endpoints(co);Ty target=endpoints.get(1);
                if(!types.equal(body.type,endpoints.getFirst()))throw error(e,"cast source type differs from coercion endpoint");
                var expression=new ArrayList<>(body.expression);var info=new LinkedHashMap<>(metadata(body.expression));boolean evaluated=Boolean.TRUE.equals(((Map<?,?>)info.get("rep")).get("evaluated"));info.put("rep",rep(target,evaluated,e));expression.set(expression.size()-1,info);yield new Lowered(expression,target);
            }
            case 2->{Co co=types.readCo(e.fields.getFirst(),typeScope);yield expression(types.coercionType(co),e,"void");}
            case 9->{Literal literal=(Literal)e.fields.getFirst();yield expression(literal.type,e,"lit",literal.kind,literal.value);}
            case 4 -> {
                var inner = new HashMap<>(scope);
                var innerTypes = new HashMap<>(typeScope);
                var parameters = new ArrayList<Map<String,Object>>();
                var prefix = new ArrayList<Object>();
                Expr body = e;
                while (body.tag == 4) {
                    Binder b = (Binder) body.fields.getFirst();
                    Ty type = types.read(b.type,innerTypes);
                    if (b.typeVariable || coercionType(type)) {
                        var v = new CoreHiTypes.Variable(b.name,type,!b.typeVariable);
                        innerTypes.put(b.name,v);
                        prefix.add(v);
                        if (v.coercion()) {
                            Variable value = new Variable(local(),type);
                            inner.put(b.name,value);
                            parameters.add(binder(b,value,true));
                        }
                    } else {
                        Variable v = new Variable(local(),type);
                        inner.put(b.name,v);
                        parameters.add(binder(b,v,false));
                        Ty multiplicity = b.multiplicity == null ? many() : types.read(b.multiplicity,innerTypes);
                        prefix.add(new Fun(0,multiplicity,type,type));
                    }
                    body = (Expr) body.fields.get(1);
                }
                Lowered result = lower(body,inner,innerTypes);
                Ty type = result.type;
                for (int i = prefix.size()-1; i >= 0; i--) {
                    Object p = prefix.get(i);
                    if (p instanceof CoreHiTypes.Variable v) type = types.lambdaType(v,type);
                    else {
                        Fun f = (Fun) p;
                        type = types.function(f.multiplicity(),f.argument(),type);
                    }
                }
                if (parameters.isEmpty()) yield new Lowered(result.expression,type);
                Lowered lambda = expression(type,e,"lam",parameters,result.expression);
                metadata(lambda.expression).put("resultRep",metadata(result.expression).get("rep"));
                yield lambda;
            }
            case 5 -> {
                var rawArgs=new ArrayList<Expr>();
                Expr headExpr=e;
                while(headExpr.tag==5){
                    rawArgs.add((Expr)headExpr.fields.get(1));
                    headExpr=(Expr)headExpr.fields.getFirst();
                }
                Collections.reverse(rawArgs);
                Lowered head=lower(headExpr,scope,typeScope);
                Ty result=head.type;
                var args=new ArrayList<List<Object>>();
                var lifted=new ArrayList<Object>();
                for(Expr arg:rawArgs){
                    Ty supplied;
                    if(arg.tag==1)supplied=types.read(arg.fields.getFirst(),typeScope);
                    else{
                        Lowered value=lower(arg,scope,typeScope);
                        supplied=arg.tag==2?new CoTy(types.readCo(arg.fields.getFirst(),typeScope)):value.type;
                        args.add(value.expression);
                        lifted.add(arg.tag==2?false:lifted(value.type));
                    }
                    result=types.piApply(result,List.of(supplied));
                }
                if(args.isEmpty()){
                    var erased=new ArrayList<>(head.expression);
                    var info=new LinkedHashMap<>(metadata(head.expression));
                    info.put("rep",rep(result,Boolean.TRUE.equals(((Map<?,?>)info.get("rep")).get("evaluated")),e));
                    erased.set(erased.size()-1,info);
                    yield new Lowered(erased,result);
                }
                if(unaryIdentity(headExpr)){
                    // CoreToStg/THC.Wired erase BOTH the constructor and selector.
                    // The payload is kept once, with all remaining applications;
                    // nominal dictionary types need not equal the erased types.
                    var payload=new ArrayList<>(args.removeFirst());
                    lifted.removeFirst();
                    if(!args.isEmpty())payload=values("app",payload,args,lifted,false,false,map("rep",map("kind","unknown","evaluated",false)));
                    else{
                        var info=new LinkedHashMap<>(metadata(payload));
                        info.put("rep",map("kind","unknown","evaluated",false));
                        payload.set(payload.size()-1,info);
                    }
                    yield new Lowered(payload,result);
                }
                yield expression(result,e,"app",head.expression,args,lifted,false,false);
            }
            case 6->{
                Lowered scrutinee=lower((Expr)e.fields.getFirst(),scope,typeScope);
                Binder b=new Binder((String)e.fields.get(1),scrutinee.type,new Info(0,null,null),e.offset,false);
                Variable v=new Variable(local(),scrutinee.type);
                var inner=new HashMap<>(scope);
                inner.put(b.name,v);
                var alts=new ArrayList<List<Object>>();
                Ty result=null;
                for(Object raw:(List<?>)e.fields.get(2)){
                    Alt alt=(Alt)raw;
                    var branch=new HashMap<>(inner);
                    var branchTypes=new HashMap<>(typeScope);
                    var params=new ArrayList<Map<String,Object>>();
                    var ids=new ArrayList<String>();
                    Object discriminator=null;
                    if(alt.tag==1){
                        var name=(CoreHiReader.ExternalName)alt.discriminator;
                        Constructor con=constructor(name);
                        if(con==null)throw error(e,"missing constructor declaration "+CoreHiNames.id(name));
                        discriminator=CoreHiNames.id(name);
                        var substitution=new HashMap<CoreHiTypes.Variable,Ty>();
                        var coSubstitution=new HashMap<CoreHiTypes.Variable,Co>();
                        Ty seen=types.view(scrutinee.type,false);
                        if(!(seen instanceof Con viewed))throw error(e,"data alternative lacks TyCon scrutinee");
                        int n=con.universal.size();
                        if(viewed.arguments().size()<n)throw error(e,"constructor universal argument count mismatch");
                        for(int i=0;i<n;i++)substitution.put(con.universal.get(i),viewed.arguments().get(i).type());
                        int next=0;
                        for(var existential:con.existential){
                            if(next>=alt.binders.size())throw error(e,"missing existential alternative binder");
                            String spelling=alt.binders.get(next++);
                            var fresh=new CoreHiTypes.Variable(spelling,substitute(existential.kind(),substitution,coSubstitution),existential.coercion());
                            branchTypes.put(spelling,fresh);
                            if(existential.coercion()){
                                coSubstitution.put(existential,new Co("varco",List.of(fresh)));
                                Binder evidence=new Binder(spelling,fresh.kind(),new Info(0,null,null),alt.rhs.offset,false);
                                Variable ev=new Variable(local(),fresh.kind());
                                branch.put(spelling,ev);
                                ids.add(ev.id);
                                params.add(binder(evidence,ev,true));
                            }
                            else substitution.put(existential,new Var(fresh));
                        }
                        WorkerFields layout=workerFields(con);
                        int fieldIndex=0;
                        for(Ty rawField:layout.fields){
                            if(next>=alt.binders.size())throw error(e,"constructor field binder count mismatch");
                            String spelling=alt.binders.get(next++);
                            Ty fieldType=substitute(rawField,substitution,coSubstitution);
                            Binder field=new Binder(spelling,fieldType,new Info(0,null,null),alt.rhs.offset,false);
                            Variable fv=new Variable(local(),fieldType);
                            branch.put(spelling,fv);
                            ids.add(fv.id);
                            params.add(binder(field,fv,layout.strict.get(fieldIndex++)));
                            if(coercionType(fieldType))branchTypes.put(spelling,new CoreHiTypes.Variable(spelling,fieldType,true));
                        }
                        if(next!=alt.binders.size())throw error(e,"constructor field binder count mismatch");
                    }
                    Lowered rhs=lower(alt.rhs,branch,branchTypes);
                    if(result==null)result=rhs.type;
                    if(alt.tag==2){
                        Literal lit=(Literal)alt.discriminator;
                        discriminator=values(lit.kind,lit.value);
                    }
                    alts.add(values(alt.tag==0?"default":alt.tag==1?"data":"lit",discriminator,ids,rhs.expression,map("binders",params)));
                }
                Lowered selected=expression(result,e,"case",scrutinee.expression,v.id,alts);
                metadata(selected.expression).put("binder",binder(b,v,true));
                metadata(selected.expression).put("resultRep",rep(result,false,e));
                yield selected;
            }
            case 7->{Group group=(Group)e.fields.getFirst();var inner=new HashMap<>(scope);var variables=new ArrayList<Variable>();for(Definition definition:group.definitions){Variable v=new Variable(local(),types.read(definition.binder.type,typeScope));variables.add(v);inner.put(definition.binder.name,v);}var bindings=new ArrayList<Map<String,Object>>();for(int i=0;i<group.definitions.size();i++)bindings.add(binding(group.definitions.get(i),variables.get(i),group.recursive?inner:scope,typeScope));Lowered body=lower((Expr)e.fields.get(1),inner,typeScope);yield expression(body.type,e,"let",group.recursive,bindings,body.expression);}
            case 13->{Lowered body=lower((Expr)e.fields.getFirst(),scope,typeScope);Ty result=types.read(e.fields.get(1),typeScope);yield expression(result,e,"case",body.expression,local(),List.of());}
            case 14->{Ty type=types.read(e.fields.getFirst(),typeScope);yield expression(type,e,"rubbish");}
            default->throw error(e,"executable expression form "+e.tag+" has no runtime transport");
        };
    }
    private Lowered expression(Ty type,Expr origin,Object...fields){var expression=values(fields);boolean evaluated=fields[0].equals("lam")||fields[0].equals("lit")||fields[0].equals("void");expression.add(map("rep",rep(type,evaluated,origin)));return new Lowered(expression,type);}
    private String entryType(Ty type){
        // These are the existing exporter entry-boundary facts, not an IO representation rule.
        Ty normalized=types.view(type,false);
        if(normalized instanceof Con con&&con.name().module().equals(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Types"))&&con.name().occurrence().equals("IO")&&con.arguments().size()==1){
            Ty argument=types.view(con.arguments().getFirst().type(),false);if(argument instanceof Con unit&&unit.name().equals(CoreHiNames.tupleName(0,0,3)))return "IO ()";
        }
        if(normalized instanceof Con con&&con.name().equals(new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Prim"),3,null,"State#"))&&con.arguments().size()==1){
            Ty argument=types.view(con.arguments().getFirst().type(),false);if(argument instanceof Con world&&world.name().equals(new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Prim"),3,null,"RealWorld")))return "State# RealWorld";
        }
        return null;
    }
    private Object lifted(Ty type){return types.lifted(type);}
    private Map<String,Object> rep(Ty type,boolean evaluated,Expr location){try{return types.representation(type,evaluated);}catch(IllegalArgumentException failure){throw error(location,failure.getMessage());}}
    private static Ty primitive(String rep){String name=rep.substring(0,rep.length()-3)+"#";return con(new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Prim"),3,null,name),false);}
    private String foreignCall(CoreHiReader.Cursor c){int target=c.byteValue();c.require(target<=1,"invalid C call target");String symbol="dynamic";if(target==0){sourceText(c);symbol=reader.fastString(c);if(c.optional())reader.fastString(c);bool(c);}int convention=c.byteValue(),safety=c.byteValue();c.require(convention<=4&&safety<=2,"invalid foreign calling convention or safety");return "foreign call "+symbol;}
    @SuppressWarnings("unchecked") private static Map<String,Object> metadata(List<Object> expression) {
        return (Map<String,Object>) expression.getLast();
    }
    private static boolean bool(CoreHiReader.Cursor c) {
        int tag = c.byteValue(); c.require(tag <= 1, "invalid boolean tag " + tag); return tag == 1;
    }
    private static IllegalArgumentException unsupported(CoreHiReader.Cursor c, String syntax) {
        c.require(false, "unsupported native Core " + syntax);
        throw new AssertionError();
    }
    private IllegalArgumentException error(Expr origin, String message) {
        return unsupported(reader.cursor(new CoreHiReader.Section(origin.offset, origin.offset), "Core lowering " + reader.module.name()), message);
    }
    private static LinkedHashMap<String,Object> map(Object... fields) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private static ArrayList<Object> values(Object... fields) { return new ArrayList<>(Arrays.asList(fields)); }
}
