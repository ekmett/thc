import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import jdk.jfr.consumer.*;
/** Offline JFR summarizer: allocation weights and execution sample counts stay separate. */
public final class ProfileHotStacks {
 static String frame(RecordedFrame f){return f.getMethod().getType().getName()+"."+f.getMethod().getName()+":"+f.getLineNumber()+"@bci"+f.getBytecodeIndex()+"["+f.getType()+"]";}
 static void add(Map<String,long[]> map,String key,long weight){long[] r=map.computeIfAbsent(key,k->new long[2]);r[0]++;r[1]+=weight;}
 static void write(Path path,Map<String,long[]> map,int limit,boolean byWeight)throws Exception{
  var rows=new ArrayList<>(map.entrySet());rows.sort((a,b)->Long.compare(b.getValue()[byWeight?1:0],a.getValue()[byWeight?1:0]));var lines=new ArrayList<String>();int i=0;
  for(var r:rows){if(i++==limit)break;lines.add(r.getValue()[0]+"\t"+r.getValue()[1]+"\t"+r.getKey());}Files.write(path,lines);
 }
 public static void main(String[] args)throws Exception{
  Path out=Path.of(args[0]);var window=Files.readAllLines(out.resolve("sample-window.txt"));Instant start=Instant.parse(window.get(0)),end=Instant.parse(window.get(1));
  Matcher match=Pattern.compile("PHASE JFR END calls=4096 checksum=-?\\d+ threadId=(\\d+)").matcher(Files.readString(out.resolve("run.log")));if(!match.find())throw new IllegalStateException("Profile thread identity missing");long thread=Long.parseLong(match.group(1));
  Map<String,long[]> execution=new HashMap<>(),leaves=new HashMap<>(),allocations=new HashMap<>(),origins=new HashMap<>();long executionSamples=0,allocationSamples=0,totalWeight=0,truncatedExecution=0,truncatedAllocation=0;
  try(RecordingFile recording=new RecordingFile(out.resolve("map.jfr"))){while(recording.hasMoreEvents()){
   RecordedEvent event=recording.readEvent();String name=event.getEventType().getName();boolean cpu=name.equals("jdk.ExecutionSample"),alloc=name.equals("jdk.ObjectAllocationSample");if(!cpu&&!alloc)continue;
   if(event.getStartTime().isBefore(start)||event.getStartTime().isAfter(end))continue;RecordedThread t=cpu?event.getThread("sampledThread"):event.getThread();if(t==null||t.getJavaThreadId()!=thread)continue;
   var frames=new ArrayList<String>();String origin="no THC frame";if(event.getStackTrace()!=null)for(var f:event.getStackTrace().getFrames()){
    frames.add(frame(f));if(origin.equals("no THC frame")&&f.getMethod().getType().getName().startsWith("thc."))origin=frame(f);
   }
   String stack=String.join(" <- ",frames.subList(0,Math.min(64,frames.size())));boolean truncated=event.getStackTrace()!=null&&event.getStackTrace().isTruncated();
   if(cpu){executionSamples++;if(truncated)truncatedExecution++;add(execution,stack,0);add(leaves,frames.isEmpty()?"empty":frames.get(0),0);}
   else{allocationSamples++;if(truncated)truncatedAllocation++;long weight=event.getLong("weight");totalWeight+=weight;String cls=event.getClass("objectClass").getName();add(allocations,cls+"\t"+stack,weight);add(origins,cls+"\t"+origin,weight);}
  }}
  write(out.resolve("execution-hot-stacks.tsv"),execution,100,false);write(out.resolve("execution-leaves.tsv"),leaves,100,false);write(out.resolve("allocation-hot-stacks.tsv"),allocations,250,true);write(out.resolve("allocation-first-thc-frame.tsv"),origins,250,true);
  String stats="{\n  \"threadId\": "+thread+",\n  \"executionSamples\": "+executionSamples+",\n  \"allocationSamples\": "+allocationSamples+",\n  \"allocationWeightBytes\": "+totalWeight+",\n  \"executionTruncatedStacks\": "+truncatedExecution+",\n  \"allocationTruncatedStacks\": "+truncatedAllocation+",\n  \"uniqueExecutionStacks\": "+execution.size()+",\n  \"uniqueAllocationStacks\": "+allocations.size()+",\n  \"retainedFramesPerTextStack\": 64,\n  \"executionTopN\": 100,\n  \"allocationTopN\": 250\n}\n";Files.writeString(out.resolve("hot-stack-statistics.json"),stats);System.out.print(stats);
 }
}
