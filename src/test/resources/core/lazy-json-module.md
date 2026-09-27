# Indexed JSON runtime control

`lazy-json-module.json` is a handwritten synthetic control, not GHC-exported Core
or a native benchmark. Its entry selects two cold scalar functions and leaves an
unsupported definition unreachable. Expected results are the independent scalar
expressions `x + 1` for zero and `x + 2` otherwise.

`lazy-json-module.idx` was generated from the unchanged JSON by the Haskell/C
production writer, using its automatic host ISA selection:

```
thc-json-index lazy-json-module.json lazy-json-module.idx
```

The runtime test checks both source and sidecar hashes and requires the actual
sidecar-loading path. There is no Kotlin reference-encoder fallback. Regenerate
the sidecar with the production writer whenever the source changes.

Source SHA-256: `0d9dacecb7b3e8b617e01f7eca52a5017aba4a922d994c79f89416229841b1b6`

Sidecar SHA-256: `942c653564c3f767c0877d80befc725940c1d437cc4a8a24af91bd4867cbb158`
