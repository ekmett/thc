# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{ lib, buildFHSEnv, haskell, cabal-install, jam
, name ? "thc-toolchain", extraPackages ? [] }:
assert haskell.compiler.ghc9141.version == "9.14.1";
assert lib.versions.majorMinor cabal-install.version == "3.16";
buildFHSEnv {
  inherit name;
  multiArch = false;
  targetPkgs = pkgs: (with pkgs; [
    bashInteractive coreutils findutils gnused gnugrep gawk diffutils
    git curl cacert gnutar gzip xz unzip zip patch which file
    gnumake cmake ninja pkg-config python3
    haskell.compiler.ghc9141 cabal-install
    llvmPackages_18.clang llvmPackages_18.llvm
    gcc.cc.lib gmp zlib libffi ncurses
  ]) ++ extraPackages;
  extraOutputsToInstall = [ "dev" ];
  profile = ''
    export JAVA_HOME=${jam}
    export GRAALVM_HOME=${jam}
    export PATH=${jam}/bin:$PATH
    export _JAVA_SR_SIGNUM=64
    export SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt
  '';
  runScript = "bash";
}
