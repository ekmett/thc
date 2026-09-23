# Boxed-value cache evidence

The construction-time Int/Char cache is experimental and defaults off. This same-JAR comparison found less allocation, with no demonstrated throughput win. See [the report](../../../docs/boxed-values.md).

- [throughput/](throughput/): all 45 measured windows, per-process logs, command/configuration records, checksums, power samples and validation.
- [allocation/](allocation/): separate exact executing-thread allocation samples (including host entry), compact JFR summaries and capture configuration. Raw JFR files are omitted; their hashes are in [raw-artifacts.json](allocation/raw-artifacts.json).
- [graphs/](graphs/README.md): selected lookup/fold/worker counts, actual scheduled cache-hit/miss SSA and control-flow nodes, source/target identities and original BGV hashes. Raw BGV files are not bundled here.
- [validation/](validation/): 153 default / 153 enabled / 41 combined-array / 153 compact-header compatibility test XML and logs, all 18 Map inputs before and after requested compilation in four configurations, the frozen v7 source/binary hash manifest, runtime patch and fixture-cleanup evidence. The later cleanup changes test fixtures and metadata lookup only; it does not change the measured JAR.
- [ghc-sources/provenance.json](ghc-sources/provenance.json): GHC release file/blob/SHA identities, source locations, parsed ranges and concise behavioral summaries. No raw GHC source excerpts are copied.

Original machine paths in capture records identify the inputs used. They are provenance, not required local directories. Full runtime binaries and the full Map corpus are not duplicated here. The recorded JAR SHA256 is `a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d`.

Run from any checkout location, without a JVM or the original work directories:

```sh
python3 bench/results/boxed-values/verify.py
```

The verifier checks the publication hashes, reuses the recorded harness's checksum/window/log guards, recomputes medians and checks test totals. It cannot re-establish host conditions or omitted binary contents from their hashes. Profile elapsed times are not throughput measurements. The selected graphs establish a local cache-hit allocation saving, but not the cause of the observed timing variation.
