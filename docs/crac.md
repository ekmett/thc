# Checkpoint before main

The JVM launcher accepts `--checkpoint-executable` with the same fixed arguments
as `--run-executable`. It reads every Core binding transitively reachable from
the main and shutdown entries, including cold branches and required runtime
dependencies. It closes the CBD readers and drains their idle mapping cache
before calling `jdk.crac.Core.checkpointRestore()`.

The checkpoint contains decoded Core. It does not run guest code or warm up main.
After restore, THC creates a fresh Truffle context, loads the detached Core without
reopening CBD, and invokes the ordinary one-shot main/shutdown wrapper. AST and
bytecode backends use their normal lowering paths after restore.

This requires a **CRaC-capable Jam JDK** and a working Linux checkpoint engine.
The current pinned Jam release does not provide CRaC. The launcher fails before
reading application inputs when `jdk.crac.Core` is absent. Boundary tests with
an injected callback establish resource release and subsequent execution only;
they do not qualify an actual checkpoint or restored Jam collector.

With a compatible provider, use the installed JVM launcher:

```sh
JAVA_OPTS='--add-modules=jdk.crac -XX:CRaCCheckpointTo=/absolute/checkpoint' \
  build/install/thc/bin/thc --checkpoint-executable \
  @/absolute/packages.json main::Main.main RUNTIME_SHUTDOWN_ENTRY \
  -- program-name arg1

"$JAVA_HOME/bin/java" -XX:CRaCRestoreFrom=/absolute/checkpoint
```

Take the module list, exact entry identities and fixed guest arguments from the
ordinary executable launcher produced by `thc build`. The first command exits at
the checkpoint; a successful restore resumes before guest startup. A checkpoint
failure aborts the launch rather than running main as a fallback.

Guest arguments are fixed at checkpoint time. This initial path makes no promise
of rebinding environment variables or relocating native package libraries:
those libraries and the JVM's runtime dependencies must remain available after
restore. Checkpoint files contain process memory and should be kept private.
