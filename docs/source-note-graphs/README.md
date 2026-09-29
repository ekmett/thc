# Frozen source-note and local-join graphs

This directory retains compiler captures for `RepresentationAudit.joinLoop`.
[manifest.json](manifest.json), [runtime-manifest.json](runtime-manifest.json)
and [runtime-v2-manifest.json](runtime-v2-manifest.json) identify their inputs.
They describe those frozen builds, not the current runtime.

[Topology comparison](topology-comparison.json) and
[loop audit](join-graph-audit.json) retain node/edge/CFG observations. The
[raw BGV archive](selected-bgv.tar.xz),
[frozen runtime sources](frozen-runtime-sources.tar.xz) and
[Core input](RepresentationAudit.json) support reconstruction.

To reparse the saved graphs with GraalVM 25.3.4.1:

```sh
export JAVA_HOME=/path/to/graalvm-jdk-25
bash docs/source-note-graphs/reparse.sh /tmp/thc-source-graphs
```

The `*-source` tools retain the capture-time paths and configuration; they are
not current-checkout launch commands. For new captures, use
[graph inspection](../graph-inspection.md). Static allocation sites and compiler
node counts do not determine execution frequency or throughput.
