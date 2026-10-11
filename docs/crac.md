# Checkpoint before main

The JVM launcher accepts `--checkpoint-executable` with the module list and entry
identities used by `--run-executable`, leaving guest arguments for restore. It reads every Core binding transitively reachable from
the main and shutdown entries, including cold branches and required runtime
dependencies. It closes the CBD readers and drains their idle mapping cache
before calling `jdk.crac.Core.checkpointRestore()`.

Before checkpointing, THC creates the owning Truffle context and constructs the
callable main/shutdown entry. It lowers
reachable Core to executable nodes using the selected AST or bytecode backend,
prepares their compilation profiles and attempts Graal compilation of every
retained target. It does not invoke guest main or evaluate CAFs as warmup.

The example requests a 200,000-node Graal budget for precompilation through the existing launcher option. Ordinary launcher defaults are unchanged.

Compilation reports distinguish installed code from failed, rejected, cancelled
or invalidated attempts. A small graph budget can leave some targets interpreted;
the report must not be read as a promise that all code compiled. Targets created
later by guest execution are outside the prepared inventory.

Guest arguments and native stdin/stdout/stderr are initialized after restore.
The checkpoint contains prepared callable code, not a started guest IO lifecycle.
Native package linkage may run package constructors; constructors that require
guest IO cannot be assumed safe to run during preparation.

Before invoking main, THC verifies that the retained installed targets still have
the same valid code addresses and installation counts. It does not repair lost
installations by recompiling. Only execution-time thread ownership and signal
binding remain before dispatching the top-level handler. CBD files are not
reopened, and reachable bindings do not need another Core-to-node conversion.
Actual code survival and first-call compiled execution require a real restore;
a successful compilation or callback simulation alone does not establish them.

This requires a **CRaC-capable Jam JDK** and a working Linux checkpoint engine.
The current pinned Jam release does not provide CRaC. The launcher fails before
reading application inputs when `jdk.crac.Core` is absent. Boundary tests with
an injected callback establish resource release and subsequent execution only;
they do not qualify an actual checkpoint or restored Jam collector.

With a compatible provider, use the installed JVM launcher:

```sh
JAVA_OPTS='--add-modules=jdk.crac -XX:CRaCCheckpointTo=/absolute/checkpoint -Dpolyglot.compiler.MaximumGraalGraphSize=200000' \
  build/install/thc/bin/thc --checkpoint-executable \
  @/absolute/packages.json main::Main.main RUNTIME_SHUTDOWN_ENTRY

"$JAVA_HOME/bin/java" -XX:CRaCRestoreFrom=/absolute/checkpoint \
  thc.CracExecutable program-name arg1
```

Take the module list and exact entry identities from the ordinary executable
launcher produced by `thc build`. Supply the program name and fresh guest
arguments to `thc.CracExecutable` on each restore. The first command exits at
the checkpoint; a successful restore resumes before guest startup. A checkpoint
failure aborts the launch rather than running main as a fallback.

Use a literal checkpoint destination; THC's native backing ownership does not
support `%` destination templates. Extracted native libraries remain named while
their loader handles can be cached. Ordinary runs remove those files at JVM exit,
not when a guest context closes. Before a real checkpoint, THC transfers their
cleanup to the image and writes `<checkpoint>.native-libraries.json` beside the
image directory. The sidecar records their original paths, sizes, hashes and file
identities. The backing files must remain at those paths with the same inodes;
copying them elsewhere and replacing the originals does not preserve loader
identity. Restored JVM exit and a failed restore do not delete image-owned files.
New extractions after restore retain ordinary JVM cleanup.
Checkpoint and restore callbacks cannot extract new THC native libraries while
ownership transfer is pending; initialize them before entering the checkpoint
operation or after it returns. THC rejects late extraction instead of omitting
backing files from image ownership.

After removing an image and stopping all of its restored processes, explicitly
remove its sidecar-listed backing files and then the sidecar. Keep a backing file
if another retained image also references it. Check the recorded identity before
deleting a path, so a replaced file is not mistaken for image-owned backing.
A failure before any image inventory exists returns the files to ordinary
cleanup and removes the sidecar. If an inventory exists, THC conservatively
retains the sidecar and files even when checkpointing reports a failure; remove
those alongside the discarded partial image.

This initial path makes no promise of rebinding environment variables or
relocating native package libraries:
the saved working-directory path, those libraries and the JVM's runtime
dependencies must remain available after
restore. Checkpoint files contain process memory and should be kept private.
