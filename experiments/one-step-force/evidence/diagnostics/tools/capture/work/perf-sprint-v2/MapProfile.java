import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import jdk.jfr.*;
import jdk.jfr.consumer.*;
import org.graalvm.polyglot.*;

/** Diagnostic allocation/CPU sampling only: never use these elapsed times as throughput. */
public final class MapProfile {
  static long invoke(Value fn, long input) { return fn.execute(input).asLong(); }
  static long cycle(Value fn, long base, long[] oracle, int count) {
    long sum=0;
    for(int i=0;i<count;i++) { long value=invoke(fn,base+(i&15)); if(value!=oracle[i&15])throw new AssertionError("native oracle mismatch");sum+=value; }
    return sum;
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=4)throw new IllegalArgumentException("MODULES ORACLE_TSV OUT_DIR BACKEND");
    Path manifest=Path.of(args[0]).toAbsolutePath();
    var modules=Files.readAllLines(manifest).stream().filter(s->!s.isBlank()).map(s->manifest.getParent().resolve(s).normalize().toString()).toList();
    var oracleRows=Files.readAllLines(Path.of(args[1]));long[] oracle=new long[16];long base=10000;
    for(int i=0;i<16;i++){var f=oracleRows.get(i).split("\t");if(!f[0].equals("mapAggregate")||Long.parseLong(f[1])!=base+i)throw new AssertionError("oracle input");oracle[i]=Long.parseLong(f[2]);}
    Path out=Path.of(args[2]);Files.createDirectories(out);
    var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    if(!bean.isThreadAllocatedMemorySupported())throw new IllegalStateException("allocation counter unavailable");
    bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();
    Instant profileStart, profileEnd;
    try(Context context=thc.MainKt.executionContext()) {
      Value fn=thc.MainKt.loadEntry(context,modules,"mapAggregate",false,args[3]);
      cycle(fn,base,oracle,208);fn.invokeMember("compile");
      System.err.println("PHASE WARM BEGIN");long start=System.nanoTime(),calls=0,checksum=0;
      do {checksum+=cycle(fn,base,oracle,256);calls+=256;}while(calls<30000||System.nanoTime()-start<45_000_000_000L);
      System.err.println("PHASE WARM END calls="+calls+" elapsedNs="+(System.nanoTime()-start)+" checksum="+checksum);fn.invokeMember("compile");
      for(int s=1;s<=3;s++) {
        System.err.println("PHASE ALLOCATION "+s+" BEGIN");long before=bean.getThreadAllocatedBytes(thread);
        long value=cycle(fn,base,oracle,256);long bytes=bean.getThreadAllocatedBytes(thread)-before;
        System.err.println("PHASE ALLOCATION "+s+" END");System.out.println(s+"\t256\t"+base+"\t"+value+"\t"+bytes);
      }
      try(Recording recording=new Recording()) {
        recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle","1000/s");
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
        recording.setName("THC warmed Map allocation attribution");
        System.err.println("PHASE JFR BEGIN");recording.start();
        cycle(fn,base,oracle,256); // Settle JFR before accepting samples.
        profileStart=Instant.now();long value=cycle(fn,base,oracle,4096);profileEnd=Instant.now();recording.stop();
        Files.writeString(out.resolve("sample-window.txt"),profileStart+"\n"+profileEnd+"\n");
        System.err.println("PHASE JFR END calls=4096 checksum="+value+" threadId="+thread);recording.dump(out.resolve("map.jfr"));
      }
      System.err.println("PHASE VERIFY BEGIN");fn.invokeMember("compile");
      System.err.println("PHASE VERIFY END guestLastTierInstalled=true");
      System.err.println("diagnostics="+fn.getMember("diagnostics").asString());
    }
    Map<String,long[]> classes=new TreeMap<>(),sites=new TreeMap<>(),cpu=new TreeMap<>();
    try(RecordingFile recording=new RecordingFile(out.resolve("map.jfr"))) {
      while(recording.hasMoreEvents()) {
        RecordedEvent e=recording.readEvent();if(e.getStartTime().isBefore(profileStart)||e.getStartTime().isAfter(profileEnd))continue;String name=e.getEventType().getName();
        RecordedThread t=name.equals("jdk.ExecutionSample")?e.getThread("sampledThread"):e.getThread();
        if(t==null||t.getJavaThreadId()!=thread)continue;
        String site="no THC frame";
        if(e.getStackTrace()!=null)for(RecordedFrame f:e.getStackTrace().getFrames()) {
          String cls=f.getMethod().getType().getName();if(cls.startsWith("thc.")){site=cls+"."+f.getMethod().getName()+":"+f.getLineNumber();break;}
        }
        if(name.equals("jdk.ObjectAllocationSample")) {
          String cls=e.getClass("objectClass").getName();long weight=e.getLong("weight");
          long[] total=classes.computeIfAbsent(cls,k->new long[2]);total[0]++;total[1]+=weight;
          total=sites.computeIfAbsent(cls+"\t"+site,k->new long[2]);total[0]++;total[1]+=weight;
        } else if(name.equals("jdk.ExecutionSample"))cpu.computeIfAbsent(site,k->new long[2])[0]++;
      }
    }
    write(out.resolve("allocation-classes.tsv"),classes);write(out.resolve("allocation-sites.tsv"),sites);write(out.resolve("cpu-sites.tsv"),cpu);
  }
  static void write(Path file,Map<String,long[]> rows)throws Exception {
    var sorted=new ArrayList<>(rows.entrySet());sorted.sort((a,b)->Long.compare(b.getValue()[1]!=0?b.getValue()[1]:b.getValue()[0],a.getValue()[1]!=0?a.getValue()[1]:a.getValue()[0]));
    var lines=new ArrayList<String>();for(var row:sorted)lines.add(row.getKey()+"\t"+row.getValue()[0]+"\t"+row.getValue()[1]);Files.write(file,lines);
  }
}
