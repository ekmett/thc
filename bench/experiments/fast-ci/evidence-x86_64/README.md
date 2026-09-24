# Local Fast CI measurements

The warm run at `7e0cacadeb0a32a638355401b0b64fdc4a6a1bc0` took
69.763327 seconds, with 44 fresh JUnit cases in each of default and dense modes.
This was a Linux x86-64 local run with the pinned toolchains already installed,
no source change, and restored native inputs and Gradle compilation caches.
It is not a hosted Actions latency measurement.

`summary.md` and `timings.json` are unchanged copies of that run's receipts.
`raw.tar.gz` retains 36 original command records, logs, identities, selections,
timings and JUnit XML files for the cold preparation, cache and warm runs.
`raw.SHA256SUMS` records their original bytes. The archive omits duplicate
generated HTML/CSS/JavaScript and Gradle binary reports; the previous full
directory remains in commit `f47052626e7aceea5d8eb476d696238d53e69e13`.

Verify the packaged files with `sha256sum -c SHA256SUMS`. To verify the raw
records, extract the archive into an empty temporary directory and run
`sha256sum -c /absolute/path/to/raw.SHA256SUMS` there.

These are warm smoke measurements, not evidence that the later full-suite run
passed. That run's failures and diagnostic output are retained separately.
