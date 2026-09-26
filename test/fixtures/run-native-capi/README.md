# Native C/CAPI translation-unit fixture

This ordinary Cabal library exercises the generic package-native producer:

- Two Haskell modules use different CAPI headers that each define a private
  `private_helper`. Their retained stubs must remain separate translation units.
- Two declared C sources also use private helpers with the same name. Both must
  be acquired and linked without conflating their internal symbols.
- Both Haskell modules import `native_first`; the component needs one adapter
  for that repeated declaration, not duplicate exported definitions.
- The native oracle includes unsigned wraparound. The five unboxed probe roots
  expose the same calls without requiring a full `Main`/Handle dependency closure.

Build and run `oracle` with native GHC for independent expected results. Acquire
the project through the ordinary driver (`thc run` with `--exe
run-native-capi:exe:oracle`), then load its emitted `packages.json` to exercise
`NativeLeft.leftProbe#`, `NativeLeft.firstProbe#`, `NativeRight.rightProbe#`,
`NativeRight.firstAgainProbe#`, and `NativeRight.secondProbe#`. Each takes an
`Int#` and returns an `Int#`, preserving the underlying `Word64` bits.

For example, input `42` produces `45`, `53`, `49`, `53`, and `59` respectively;
input `-1` produces `2`, `10`, `6`, `10`, and `16`. The two module headers and two
C sources deliberately must not be combined into one C translation unit.

This fixture establishes acquisition and narrow guest-call behavior; successful
native execution alone does not establish acceptance of the executable's entire
boot-library closure or successful guest compilation.
