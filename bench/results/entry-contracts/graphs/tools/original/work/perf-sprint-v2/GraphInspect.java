import java.io.*;
import java.lang.reflect.Array;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import jdk.graal.compiler.graphio.parsing.*;
import jdk.graal.compiler.graphio.parsing.model.*;

/** Reads actual Graal BGV graphs with the parser shipped in this Graal JDK. */
public final class GraphInspect {
    private final Path out;
    private final Pattern selected;
    private final List<Map<String,Object>> summaries = new ArrayList<>();
    private int graphIndex;
    GraphInspect(Path out, String select) { this.out=out; this.selected=Pattern.compile(select); }
    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("GraphInspect input.bgv output-directory [graph-name-regex]");
        Path input=Path.of(args[0]); Path out=Path.of(args[1]); Files.createDirectories(out);
        String select=args.length>2 ? args[2] : "(?i).*(after.*(high|mid|low|lir)|trufflefinal|final schedule|before lir).*";
        GraphDocument document = new GraphDocument();
        try(var stream=Files.newInputStream(input)) {
            BinarySource source=new BinarySource(input.toString(), Channels.newChannel(stream));
            new BinaryReader(source,new ModelBuilder(document,null)).parse();
        }
        GraphInspect reader=new GraphInspect(out,select);
        reader.walk(document,"");
        Map<String,Object> index=new LinkedHashMap<>();
        index.put("source",input.toAbsolutePath().toString());
        index.put("selection",select); index.put("graphCount",reader.graphIndex);
        index.put("graphs",reader.summaries);
        writeJson(out.resolve("index.json"),index);
        System.out.println("Read "+reader.graphIndex+" graphs; selected "+reader.summaries.stream().filter(x->x.containsKey("file")).count()+". Index: "+out.resolve("index.json"));
    }
    private void walk(Folder folder,String parent) throws IOException {
        for(FolderElement item:folder.getElements()) {
            if(item instanceof InputGraph graph) process(graph,parent);
            else if(item instanceof Folder child) walk(child,parent.isEmpty()?item.getName():parent+" / "+item.getName());
        }
    }
    private void process(InputGraph graph,String group) throws IOException {
        int ordinal=graphIndex++;
        Map<String,Long> histogram=new TreeMap<>();
        for(InputNode node:graph.getNodes()) histogram.merge(className(node),1L,Long::sum);
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("ordinal",ordinal); summary.put("dumpId",graph.getDumpId());
        summary.put("group",group); summary.put("name",graph.getName()); summary.put("graphType",graph.getGraphType());
        summary.put("nodes",graph.getNodeCount()); summary.put("edges",graph.getEdgeCount()); summary.put("blocks",graph.getBlocks().size());
        summary.put("nodeClassCounts",histogram); summary.put("properties",graph.getProperties().toMap(new LinkedHashMap<>()));
        if(selected.matcher(graph.getName()).find()) {
            String base=String.format(Locale.ROOT,"graph-%05d",ordinal);
            summary.put("file",base+".json"); summary.put("cfg",base+"-cfg.dot");
            Map<String,Object> full=new LinkedHashMap<>(summary);
            List<Map<String,Object>> nodes=new ArrayList<>();
            for(InputNode node:graph.getNodes()) {
                Map<String,Object> data=new LinkedHashMap<>(); data.put("id",node.getId()); data.put("nodeClass",className(node));
                InputBlock block=graph.getBlock(node); data.put("block",block==null?null:block.getName());
                data.put("properties",boundedProperties(node)); nodes.add(data);
            }
            List<Map<String,Object>> edges=new ArrayList<>();
            for(InputEdge edge:graph.getEdges()) {
                Map<String,Object> data=new LinkedHashMap<>(); data.put("from",edge.getFrom()); data.put("to",edge.getTo());
                data.put("label",edge.getLabel()); data.put("type",edge.getType()); data.put("fromIndex",(int)edge.getFromIndex());
                data.put("toIndex",(int)edge.getToIndex()); data.put("listIndex",edge.getListIndex()); edges.add(data);
            }
            List<Map<String,Object>> blocks=new ArrayList<>();
            for(InputBlock block:graph.getBlocks()) {
                Map<String,Object> data=new LinkedHashMap<>(); data.put("name",block.getName());
                data.put("nodes",block.getNodes().stream().map(InputNode::getId).toList());
                data.put("successors",block.getSuccessors().stream().map(InputBlock::getName).sorted().toList()); blocks.add(data);
            }
            full.put("nodes",nodes); full.put("edges",edges); full.put("blocks",blocks);
            writeJson(out.resolve(base+".json"),full);
            Files.writeString(out.resolve(base+"-cfg.dot"),dot(graph));
        }
        summaries.add(summary);
    }
    // Full positions remain in the BGV; this diagnostic view bounds repeated source stacks.
    private static Map<String,Object> boundedProperties(InputNode node) {
        Map<String,Object> props=node.getProperties().toMap(new LinkedHashMap<>());
        for (var entry:props.entrySet()) {
            if(entry.getKey().toLowerCase(Locale.ROOT).contains("sourceposition")) {
                String value=String.valueOf(entry.getValue());
                String[] lines=value.split("\\n",13); StringBuilder shortValue=new StringBuilder();
                for(int i=0;i<Math.min(lines.length,12);i++) {
                    if(i>0)shortValue.append('\n');String line=lines[i];
                    shortValue.append(line.length()>1024?line.substring(0,1024)+" [source-note detail truncated]":line);
                }
                if(lines.length>12)shortValue.append("\n[remaining source stack retained in BGV]");
                entry.setValue(shortValue.toString());
            }
        }
        return props;
    }
    private static String className(InputNode n) {
        return n.getNodeClass()==null ? n.getProperties().getString("class","unknown") : n.getNodeClass().className;
    }
    private static String shortClass(InputNode n) { String s=className(n); return s.substring(s.lastIndexOf('.')+1); }
    private static String dot(InputGraph graph) {
        StringBuilder out=new StringBuilder("digraph cfg {\n  graph [rankdir=TB]; node [shape=box,fontname=\"Menlo\",fontsize=10];\n");
        out.append("  label=").append(json(graph.getName())).append(";\n");
        for(InputBlock block:graph.getBlocks()) {
            StringBuilder label=new StringBuilder("B").append(block.getName());
            for(InputNode node:block.getNodes()) {
                label.append("\n").append(node.getId()).append(" ").append(shortClass(node));
                Object stamp=node.getProperties().get("stamp"); if(stamp!=null) label.append(" ").append(stamp);
                Object target=node.getProperties().get("targetMethod"); if(target!=null) label.append(" ").append(target);
            }
            out.append("  ").append(json("B"+block.getName())).append(" [label=").append(json(label.toString())).append("];\n");
            for(InputBlock successor:block.getSuccessors()) out.append("  ").append(json("B"+block.getName())).append(" -> ").append(json("B"+successor.getName())).append(";\n");
        }
        return out.append("}\n").toString();
    }
    private static void writeJson(Path path,Object value) throws IOException { Files.writeString(path,json(value)+"\n"); }
    private static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof String || value instanceof Character) return quote(value.toString());
        if(value instanceof Number || value instanceof Boolean)return value.toString();
        if(value instanceof InputGraph graph)return json(Map.of("nestedGraphName",graph.getName(),"dumpId",graph.getDumpId()));
        if(value instanceof Map<?,?> map) { List<String> items=new ArrayList<>(); for(var e:map.entrySet())items.add(quote(String.valueOf(e.getKey()))+":"+json(e.getValue()));return "{"+String.join(",",items)+"}"; }
        if(value instanceof Iterable<?> iterable) { List<String> items=new ArrayList<>();for(Object v:iterable)items.add(json(v));return "["+String.join(",",items)+"]"; }
        if(value.getClass().isArray()) { List<String> items=new ArrayList<>();for(int i=0;i<Array.getLength(value);i++)items.add(json(Array.get(value,i)));return "["+String.join(",",items)+"]"; }
        return quote(value.toString());
    }
    private static String quote(String s) {
        StringBuilder result=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) { char c=s.charAt(i);switch(c) {
            case '\\':result.append("\\\\");break;case '"':result.append("\\\"");break;
            case '\n':result.append("\\n");break;case '\r':result.append("\\r");break;case '\t':result.append("\\t");break;
            default: if(c<32) result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else result.append(c);
        }}
        return result.append('"').toString();
    }
}
