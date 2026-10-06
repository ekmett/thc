// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pinned GHC name references. Ordinary names come from the interface itself;
 * built-in identities come from the compiler-generated, versioned catalogue. */
final class CoreHiNames {
    private CoreHiNames() {}
    private static final CoreHiReader.ModuleId TUPLE_MODULE = new CoreHiReader.ModuleId("ghc-internal", "GHC.Internal.Types");
    static CoreHiReader.ExternalName read(CoreHiReader reader, CoreHiReader.Cursor cursor) {
        long word = cursor.unsigned(32);
        if ((word & 0xc0000000L) == 0) {
            cursor.require(word < reader.names.size(), "name table index out of range: " + word);
            return reader.names.get((int)word);
        }
        cursor.require((word & 0xc0000000L) == 0x80000000L, "invalid name reference tag");
        var name = Catalogue.names.get(word);
        if (name == null) {
            int tag=(int)(word>>>22)&255, index=(int)word&0x3fffff;
            int sort=tag=='4'||tag=='7'?0:tag=='5'||tag=='8'?1:tag=='k'||tag=='m'?2:-1;
            if (sort>=0) {
                boolean tycon=tag=='4'||tag=='5'||tag=='k';
                int arity=index/(tycon?2:3),slot=index%(tycon?2:3);
                var base=tupleName(sort,arity,tycon?3:1);
                name=tycon&&slot==0?base:!tycon&&slot<2?tupleName(sort,arity,slot==0?1:0):
                        new CoreHiReader.ExternalName(base.module(),0,null,(tycon?"$tc":"$tc'")+base.occurrence());
            } else if(tag=='j') {
                int arity=index>>>8,position=index&255;
                if(position<arity)name=new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Classes"),0,null,"$p"+position+tupleName(2,arity,3).occurrence());
            } else if(tag=='z') {
                int arity=index>>>8,low=index&255;
                boolean tycon=(low&252)==252; int slot=low&3,alt=low>>>2;
                if(arity>=2&&slot< (tycon?2:3)) {
                    String occurrence=sumName(arity,tycon?-1:alt,tycon?3:1).occurrence();
                    name=new CoreHiReader.ExternalName(TUPLE_MODULE,slot==0?(tycon?3:1):0,null,
                            slot==(tycon?1:2)?(tycon?"$tc":"$tc'")+occurrence:!tycon&&slot==1?"$W"+occurrence:occurrence);
                }
            }
        }
        cursor.require(name!=null,"unknown GHC known-key name 0x"+Long.toHexString(word));
        return name;
    }
    static CoreHiReader.ExternalName tupleName(int sort,int arity,int namespace) {
        if(arity<0)throw new IllegalArgumentException("Negative tuple arity");
        String occurrence;
        if(sort==2)occurrence=(namespace==0?"$W":"")+(arity==0?"CUnit":arity==1?"CSolo":"CTuple"+arity);
        else if(namespace==3)occurrence=(arity==0?"Unit":arity==1?"Solo":"Tuple"+arity)+(sort==1?"#":"");
        else occurrence=arity==1?"MkSolo"+(sort==1?"#":""):sort==1?"(#"+",".repeat(Math.max(0,arity-1))+"#)":"("+",".repeat(Math.max(0,arity-1))+")";
        String module=sort==0?"GHC.Internal.Tuple":sort==1?"GHC.Internal.Types":"GHC.Internal.Classes";
        return new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal",module),namespace,null,occurrence);
    }
    static CoreHiReader.ExternalName unboxedTupleName(int arity,int namespace){return tupleName(1,arity,namespace);}
    static int unboxedTupleArity(CoreHiReader.ExternalName name) {
        if(!name.module().equals(TUPLE_MODULE)||name.namespace()!=0&&name.namespace()!=1)return -1;
        String occurrence=name.occurrence();
        if(occurrence.equals("(##)"))return 0;if(occurrence.equals("MkSolo#"))return 1;
        int arity=occurrence.length()-3;
        return arity>=2&&occurrence.equals(unboxedTupleName(arity,name.namespace()).occurrence())?arity:-1;
    }
    static Map<?,?> typeDeclaration(CoreHiReader.ExternalName name){return Metadata.tycons.get(name);}
    static Object signature(CoreHiReader.ExternalName name){return Metadata.signatures.get(name);}
    private static final class Metadata {
        static final Map<CoreHiReader.ExternalName,Map<?,?>> tycons;
        static final Map<CoreHiReader.ExternalName,Object> signatures;
        static final List<?> rules;
        static final Map<CoreHiReader.ExternalName,Map<?,?>> constructors;
        static {
            var document=resource("/thc/ghc-9.14.1-known-key-names.json");
            var cons=new HashMap<CoreHiReader.ExternalName,Map<?,?>>();var declarations=new HashMap<CoreHiReader.ExternalName,Map<?,?>>();var ids=new HashMap<CoreHiReader.ExternalName,Object>();
            for(Object raw:(List<?>)document.get("tycons")){var entry=(Map<?,?>)raw;declarations.put(CoreHiTypes.name(entry.get("name")),entry);for(Object c:(List<?>)entry.get("constructors")){var con=(Map<?,?>)c;cons.put(CoreHiTypes.name(con.get("name")),con);cons.put(CoreHiTypes.name(con.get("worker")),con);}}
            for(String key:List.of("primops","wiredIds"))for(Object raw:(List<?>)document.get(key)){var entry=(Map<?,?>)raw;ids.put(CoreHiTypes.name(entry.get("name")),entry.get("type"));}
            constructors=Map.copyOf(cons);tycons=Map.copyOf(declarations);signatures=Map.copyOf(ids);rules=document.get("axiomRules") instanceof List<?> xs?xs:List.of();
        }
    }

    record TupleFamily(int sort,int arity) {}
    record ConstraintSelector(TupleFamily family,int position) {}
    static ConstraintSelector constraintTupleSelector(CoreHiReader.ExternalName name) {
        if(name.namespace()!=0||!name.module().equals(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Classes")))return null;
        String occurrence=name.occurrence();
        if(!occurrence.startsWith("$p"))return null;
        int end=2;while(end<occurrence.length()&&Character.isDigit(occurrence.charAt(end)))end++;
        if(end==2)return null;
        try{
            int position=Integer.parseInt(occurrence.substring(2,end));
            var family=tupleFamily(new CoreHiReader.ExternalName(name.module(),3,null,occurrence.substring(end)));
            return family!=null&&family.sort==2&&position>=0&&position<family.arity?new ConstraintSelector(family,position):null;
        }catch(NumberFormatException malformed){return null;}
    }
    static TupleFamily tupleFamily(CoreHiReader.ExternalName name) {
        String module=name.module().name();
        if(!name.module().unit().equals("ghc-internal"))return null;
        int sort=module.equals("GHC.Internal.Tuple")?0:module.equals("GHC.Internal.Types")?1:module.equals("GHC.Internal.Classes")?2:-1;
        if(sort<0)return null;
        String occurrence=name.occurrence();
        if(sort==2&&name.namespace()==0&&occurrence.startsWith("$W"))occurrence=occurrence.substring(2);
        for(int small=0;small<2;small++)if(occurrence.equals(tupleName(sort,small,sort==2?1:name.namespace()).occurrence()))return new TupleFamily(sort,small);
        if(name.namespace()==3){String prefix=sort==2?"CTuple":"Tuple",suffix=sort==1?"#":"";
            if(occurrence.startsWith(prefix)&&occurrence.endsWith(suffix)){String digits=occurrence.substring(prefix.length(),occurrence.length()-suffix.length());
                try {int n=Integer.parseInt(digits);if(n>=2&&occurrence.equals(tupleName(sort,n,3).occurrence()))return new TupleFamily(sort,n);}catch(NumberFormatException ignored){}}
        } else if(name.namespace()==0||name.namespace()==1){
            if(sort==2&&occurrence.startsWith("CTuple")){try{int n=Integer.parseInt(occurrence.substring(6));if(n>=2)return new TupleFamily(sort,n);}catch(NumberFormatException ignored){}}
            int n=occurrence.length()-(sort==1?3:1);
            if(n>=2&&occurrence.equals(tupleName(sort,n,name.namespace()).occurrence()))return new TupleFamily(sort,n);
        }
        return null;
    }
    /** Pinned mkSumDataConOcc: bars are separated from each other, while
     * the underscore is adjacent to its nearest bars. */
    static CoreHiReader.ExternalName sumName(int arity,int alternative,int namespace) {
        if (arity < 2 || alternative < -1 || alternative >= arity)
            throw new IllegalArgumentException("Invalid unboxed sum family");
        String occurrence = alternative == -1 ? "Sum"+arity+"#" :
            "(# " + String.join(" ",Collections.nCopies(alternative,"|")) + "_" +
                String.join(" ",Collections.nCopies(arity-alternative-1,"|")) + " #)";
        if (alternative >= 0 && namespace == 0) occurrence = "$W"+occurrence;
        return new CoreHiReader.ExternalName(TUPLE_MODULE,namespace,null,occurrence);
    }
    record SumFamily(int arity,int alternative) {}
    static SumFamily sumFamily(CoreHiReader.ExternalName name){
        if(!name.module().equals(TUPLE_MODULE))return null;String occurrence=name.occurrence();
        if(name.namespace()==3&&occurrence.startsWith("Sum")&&occurrence.endsWith("#")){try{int n=Integer.parseInt(occurrence.substring(3,occurrence.length()-1));return n>=2?new SumFamily(n,-1):null;}catch(NumberFormatException ignored){return null;}}
        if(name.namespace()==0&&occurrence.startsWith("$W"))occurrence=occurrence.substring(2);
        if((name.namespace()==0||name.namespace()==1)&&occurrence.startsWith("(# ")&&occurrence.endsWith(" #)")){String middle=occurrence.substring(3,occurrence.length()-3).replace(" ","");int underscore=middle.indexOf('_');if(underscore<0)return null;int n=middle.length();if(n>=2&&middle.substring(0,underscore).chars().allMatch(c->c=='|')&&middle.substring(underscore+1).chars().allMatch(c->c=='|'))return new SumFamily(n,underscore);}
        return null;
    }
    static Map<?,?> constructor(CoreHiReader.ExternalName name){return Metadata.constructors.get(name);}
    static List<?> wiredAxiomRules(){return Metadata.rules;}
    static String id(CoreHiReader.ExternalName name) {
        // GHC.Types.Name.Occurrence.occNameMangledFS preserves field namespaces.
        String occurrence = name.namespace() == 4
                ? "$fld:" + name.fieldParent() + ":" + name.occurrence() : name.occurrence();
        return name.module().unit() + ":" + name.module().name() + "." + occurrence;
    }

    static CoreHiReader.ExternalName knownKey(char tag, int index) {
        var name = Catalogue.names.get(0x80000000L | (long) tag << 22 | index);
        if (name == null) throw new IllegalStateException("Missing pinned GHC key " + tag + index);
        return name;
    }

    static boolean isPrimop(CoreHiReader.ExternalName name) { return Catalogue.primops.contains(name); }

    private static Map<?, ?> resource(String path) {
        try (var stream = CoreHiNames.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing pinned GHC metadata: " + path);
            var document = (Map<?, ?>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            if (!Objects.equals(document.get("schema"), 1L) || !Objects.equals(document.get("ghc"), "9.14.1"))
                throw new IllegalStateException("Incompatible pinned GHC metadata: " + path);
            return document;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read pinned GHC metadata: " + path, failure);
        }
    }

    private static final class Catalogue {
        static final Map<Long, CoreHiReader.ExternalName> names;
        static final Set<CoreHiReader.ExternalName> primops;
        static {
            var identities = new HashMap<Long, CoreHiReader.ExternalName>();
            var primitives = new HashSet<CoreHiReader.ExternalName>();
            var document = resource("/thc/ghc-9.14.1-known-key-names.json");
            for (Object raw : (List<?>) document.get("names")) {
                var entry = (Map<?, ?>) raw;
                int namespace = ((Number) entry.get("namespace")).intValue();
                if (namespace < 0 || namespace > 4) throw new IllegalStateException("Invalid known-key namespace");
                var module = new CoreHiReader.ModuleId((String) entry.get("unit"), (String) entry.get("module"));
                var name = new CoreHiReader.ExternalName(module, namespace, (String) entry.get("fieldParent"), (String) entry.get("occurrence"));
                long word = ((Number) entry.get("nameWord")).longValue();
                if ((word & 0xffffffffc0000000L) != 0x80000000L || identities.putIfAbsent(word, name) != null)
                    throw new IllegalStateException("Invalid or duplicate GHC known key: " + word);
                if (Objects.equals(entry.get("category"), "primop")) primitives.add(name);
            }
            // Algorithmic tuple, sum and constraint families are decoded by read().
            names = Map.copyOf(identities);
            primops = Set.copyOf(primitives);
        }
    }

}
