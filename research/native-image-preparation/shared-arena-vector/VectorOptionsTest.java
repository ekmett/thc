/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.options.*;
import jdk.graal.compiler.vector.replacements.VectorIntrinsics;
import jdk.graal.compiler.vector.replacements.vectorapi.VectorAPIIntrinsics;
import org.graalvm.collections.EconomicMap;

/** The global switch's side effects must not accidentally qualify scalar fallbacks. */
public final class VectorOptionsTest {
    public static void main(String[] args) {
        EconomicMap<OptionKey<?>, Object> values = EconomicMap.create();
        VectorIntrinsics.Options.Vectorization.update(values, false);
        OptionValues disabled = new OptionValues(values);
        if (VectorAPIIntrinsics.intrinsificationSupported(disabled) ||
                VectorAPIIntrinsics.Options.OptimizeVectorAPI.getValue(disabled) ||
                GraalOptions.TargetVectorLowering.getValue(disabled))
            throw new AssertionError("Global Vectorization=false must also disable direct Vector API lowering");
        VectorAPIIntrinsics.Options.OptimizeVectorAPI.update(values, true);
        GraalOptions.TargetVectorLowering.update(values, true);
        OptionValues direct = new OptionValues(values);
        if (VectorIntrinsics.Options.Vectorization.getValue(direct) ||
                !VectorAPIIntrinsics.intrinsificationSupported(direct))
            throw new AssertionError("Explicit direct API lowering must not turn the global vectorization switch back on");
        System.out.println("PASS pinned vector option side effects and explicit direct-API lowering restoration");
    }
}
