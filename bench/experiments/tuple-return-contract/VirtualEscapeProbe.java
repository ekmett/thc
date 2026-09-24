// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import thc.Language;
public final class VirtualEscapeProbe {
    static final class Escape extends RootNode {
        Escape(Language language) { super(language); }
        public Object execute(VirtualFrame f) { Object result=new Object(); CompilerDirectives.ensureVirtualized(result); return result; }
        public String getName() { return "assert-virtual-escape"; }
    }
    public static void main(String[] args) throws Exception {
        try(Context c=Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation","false")
          .option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build()) {
            c.initialize("thc"); c.enter();
            try {
                RootCallTarget t=new Escape(TruffleLanguage.LanguageReference.create(Language.class).get(null)).getCallTarget();
                for(int i=0;i<40;i++) t.call();
                try { t.getClass().getMethod("compile",boolean.class).invoke(t,true); throw new AssertionError("escape unexpectedly compiled"); }
                catch(java.lang.reflect.InvocationTargetException e) { String m=e.getCause().toString(); if(!m.contains("should not be materialized")) throw e; System.out.println("PASS virtual escape hard failure: "+m.replace('\n',' ')); }
            } finally { c.leave(); }
        }
    }
}
