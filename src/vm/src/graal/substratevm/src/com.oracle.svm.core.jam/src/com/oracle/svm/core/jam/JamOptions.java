// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import com.oracle.svm.shared.option.HostedOptionKey;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionType;

final class JamOptions {
    @Option(help = "Jam SIMD copy workers. Object scanning stays on the VM operation thread.", type = OptionType.Expert)
    static final HostedOptionKey<Integer> JamWorkers = new HostedOptionKey<>(1);
}
