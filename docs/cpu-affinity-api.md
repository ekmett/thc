# Public Haskell affinity queries

Depend on `thc:runtime` and import `THC` or `THC.Thread`. This small public sublibrary depends
only on `base`; it does not pull GHC's compiler API into applications. The wider
[runtime services API](runtime-services.md) adds typed observations with explicit
unavailable/disabled/denied status, without removing these original functions.

```haskell
import THC
import Control.Concurrent (newEmptyMVar, putMVar, takeMVar)

main = do
  support <- cpuAffinitySupport
  print support
  result <- newEmptyMVar
  _ <- forkOnWithAffinity 0 (putMVar result)
  accepted <- takeMVar result
  putStrLn ("Native affinity request accepted: " ++ show accepted)
```

`CpuAffinitySupport` distinguishes `NoCpuAffinity`, `AdvisoryCpuAffinity`, and
`PinnedCpuAffinity`. It describes the active runtime/provider, not whether a
particular OS request succeeded. `affinityApplied` reports whether the current
registered THC fork's initial request was accepted. Ordinary entries, unsupported
providers and rejected requests report `False`.

`forkOnWithAffinity :: Int -> (Bool -> IO ()) -> IO ThreadId` uses ordinary
`forkOn`, then queries acceptance in the child before calling its callback.
The callback always runs, even when affinity is unavailable or rejected. The
argument is a capability index, not a physical CPU identifier; normalization,
lazy child evaluation, masking and child exception behavior remain those of
`forkOn`. Acceptance on an advisory platform is advisory, and even accepted
pinning is not a promise against later OS/cpuset policy changes. This API does
not establish bound foreign TLS or a scoped pin of the calling thread.

The complete example is [THC.CpuAffinity](../examples/THC/CpuAffinity.hs). When
building it outside Cabal, use `ghc --make -XHaskell2010 -threaded -iruntime
examples/THC/CpuAffinity.hs runtime/cpu-affinity.c runtime/runtime-services.c
-main-is THC.CpuAffinity`.

The same source compiles and links under ordinary native GHC. Its tiny C shim
returns `NoCpuAffinity`/`False`: ordinary GHC `forkOn` by itself does not promise
physical CPU pinning. These values do not inspect GHC's separate `+RTS -qa`
option and do not claim that the host OS lacks an affinity facility.

THC recognizes only the two versioned ordinary `ccall unsafe` queries
`thc_cpu_affinity_v1_support` and `thc_cpu_affinity_v1_applied`, each returning
`CInt` in `IO`. Both lowering backends validate their actual GHC declaration and
state/result shape. They are runtime queries rather than CPP switches or an
environment-variable test, so native and THC execution use identical Haskell
source. These names take precedence over the native compatibility shim, whose
zero results are intentionally not the THC implementation.

Run the native compatibility check with `cabal test cpu-affinity-api
-fdevelopment`. JVM controls cover both backends, exact declaration negatives,
current-context state and actual forked-child observations.
