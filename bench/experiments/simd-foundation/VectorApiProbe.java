// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.*;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.Context;
import thc.Language;
public final class VectorApiProbe {
 static final class VectorRoot extends RootNode {
  VectorRoot(Language l) { super(l); }
  public String getName() { return "simd-int64x2-probe"; }
  public Object execute(VirtualFrame f) {
   long a=(long)f.getArguments()[0], b=(long)f.getArguments()[1];
   LongVector x=LongVector.broadcast(LongVector.SPECIES_128,a).withLane(1,b);
   LongVector y=LongVector.broadcast(LongVector.SPECIES_128,b^123).withLane(1,a+91);
   LongVector sum=x.add(y);
   LongVector z=sum.lanewise(VectorOperators.XOR,sum.lanewise(VectorOperators.LSHL,3));
   return z.lane(0)*7 ^ z.lane(1)*11;
  }
 }
 static long oracle(long a,long b) { long x=a+(b^123),y=b+(a+91);return (x^(x<<3))*7 ^ (y^(y<<3))*11; }
 public static void main(String[] args) throws Exception {
  try(Context c=Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").option("engine.SingleTierCompilationThreshold","10000000").build()) {
   c.initialize("thc");c.enter();try {
    Language l=TruffleLanguage.LanguageReference.create(Language.class).get(null);
    RootCallTarget t=new VectorRoot(l).getCallTarget();
    long[] xs={Long.MIN_VALUE,-3000000001L,-1,0,1,9000000003L,Long.MAX_VALUE};
    for(int phase=0;phase<2;phase++) {
      for(long a:xs)for(long b:xs)if(!t.call(a,b).equals(oracle(a,b)))throw new AssertionError();
      if(phase==0)t.getClass().getMethod("compile",boolean.class).invoke(t,true);
    }
    if(!(boolean)t.getClass().getMethod("isValidLastTier").invoke(t))throw new AssertionError("compiled target invalid after execution");
    System.out.println("PASS 49 input pairs, exact compiled target valid, species="+LongVector.SPECIES_128);
   }finally{c.leave();}
  }
 }
}
