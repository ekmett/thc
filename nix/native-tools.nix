# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{ lib, haskell, fetchurl, runCommand }:
let
  packages = haskell.packages.ghc9141.override {
    overrides = self: super: {
      # Production tools consume these libraries, not upstream test executables,
      # profiling variants or Haddock output. THC acceptance is separate.
      mkDerivation = args: super.mkDerivation (args // {
        doCheck = false;
        doHaddock = false;
        enableLibraryProfiling = false;
        enableExecutableProfiling = false;
      });
      # Match THC's Cabal bound and the frozen project index. Keep the source
      # hash explicit instead of relaxing the bound to nixpkgs' older default.
      aeson = self.callCabal2nix "aeson" (runCommand "aeson-2.3.2.0-source" {
        src = fetchurl {
        url = "https://hackage.haskell.org/package/aeson-2.3.2.0/aeson-2.3.2.0.tar.gz";
        sha256 = "c30d187d60fb81b0f93f72442ee6b1abd2709fc3b5e3befcf4173f6b30983458";
        };
      } ''
        mkdir -p "$out"
        tar -xzf "$src" --strip-components=1 -C "$out"
      '') {};
    };
  };
  generated = packages.callCabal2nix "thc" ../. {};
in haskell.lib.overrideCabal generated (old: {
  doCheck = false;
  doHaddock = false;
  enableLibraryProfiling = false;
  enableExecutableProfiling = false;
  enableSharedLibraries = true;
  configureFlags = (old.configureFlags or []) ++ [
    "--ghc-options=-fwrite-if-simplified-core"
  ];
})
