// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Wrapper plumbing only. A stub process is not evidence of native guest execution. */
public final class NativeRuntimeTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("REPO OUTPUT required");
        Path repo = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        Path image = output.resolve("stub-image");
        Files.writeString(image, """
            #!/usr/bin/env bash
            printf '%s\\n' "$@" > "$STUB_ARGUMENTS"
            printf '%s\\n' "$THC_BACKEND" > "$STUB_BACKEND"
            printf '42\\n'
            printf 'stub diagnostics\\n' >&2
            exit "${STUB_STATUS:-0}"
            """);
        if (!image.toFile().setExecutable(true)) throw new AssertionError("Cannot execute stub");
        Path captured = output.resolve("arguments"), backend = output.resolve("backend");
        class Run {
            int run(int status, String... arguments) throws Exception {
                var command = new ArrayList<>(List.of("bash", repo.resolve("bin/native-runtime").toString()));
                command.addAll(List.of(arguments));
                var process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output.resolve("wrapper.log").toFile());
                var env = process.environment();
                env.put("THC_NATIVE_RUNTIME_IMAGE", image.toString());
                env.put("THC_BACKEND", "ast");
                env.put("STUB_ARGUMENTS", captured.toString());
                env.put("STUB_BACKEND", backend.toString());
                env.put("STUB_STATUS", Integer.toString(status));
                return process.start().waitFor();
            }
        }
        var run = new Run();
        if (run.run(0, "run", "@a path/packages.json", "unit:Module.entry", "-7", "--compile") != 0
                || !Files.readAllLines(captured).equals(List.of("@a path/packages.json", "unit:Module.entry", "-7", "--compile"))
                || !Files.readString(backend).equals("ast\n"))
            throw new AssertionError("run must preserve arguments and runtime backend selection");
        Path passed = output.resolve("passed");
        if (run.run(0, "check", passed.toString(), "@packages.json", "entry", "7", "42") != 0
                || !Files.readAllLines(captured).equals(List.of("@packages.json", "entry", "7", "--compile"))
                || !Files.readString(passed.resolve("status")).equals("0\n")
                || !Files.readString(passed.resolve("stderr")).equals("stub diagnostics\n"))
            throw new AssertionError("check must use --compile and retain output/status");
        if (run.run(0, "check", passed.toString(), "@packages.json", "entry", "7", "42") == 0)
            throw new AssertionError("check must not overwrite existing evidence");
        if (run.run(0, "check", output.resolve("wrong-result").toString(), "@packages.json", "entry", "7", "43") == 0)
            throw new AssertionError("wrong result must fail");
        Path failed = output.resolve("failed");
        if (run.run(17, "check", failed.toString(), "@packages.json", "entry", "7", "42") != 17
                || !Files.readString(failed.resolve("status")).equals("17\n"))
            throw new AssertionError("native failure status must be preserved");
        System.out.println("PASS argument/backend forwarding, exact result, failure status and fresh evidence; no native image executed");
    }
}
