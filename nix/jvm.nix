# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{ lib, stdenvNoCC, gradle-packages, jdk25, python3, git, proot, fetchzip, jam, toolchain
, revision ? "unversioned" }:
let
  # The same revision as nih/pinned/ghc-9.14.1. Runtime cbits consume the
  # original RTS translation unit and ghc-internal headers from this source.
  ghcSource = fetchzip {
    url = "https://github.com/ghc/ghc/archive/902339d332fb4ce2b3c87dcac1ee6495d41ad886.tar.gz";
    sha256 = "00hkvqkivsl62ahqqmcmgrwp6zknl5di9s2qsb1yl00jykgy15hc";
  };
  wrapper = builtins.readFile ../nih/gradle/wrapper/gradle-wrapper.properties;
  version = builtins.head (builtins.match ".*gradle-([0-9.]+)-bin.zip.*" wrapper);
  checksum = builtins.head (builtins.match ".*distributionSha256Sum=([0-9a-f]+).*" wrapper);
  gradle = (gradle-packages.mkGradle {
    inherit version;
    hash = builtins.convertHash { hash = checksum; hashAlgo = "sha256"; toHashFormat = "sri"; };
    # The Nix dependency recorder uses this keytool for its temporary CA.
    # JAVA_HOME below selects unchanged Jam for Gradle and all Java compilation.
    defaultJava = jdk25;
  }).wrapped;
in stdenvNoCC.mkDerivation (final: {
  pname = "thc-jvm";
  version = "0.1.0.0";
  src = ../.;
  postPatch = ''
    # Git archives retain an empty directory for the submodule gitlink.
    if [ -d nih/pinned/ghc-9.14.1 ]; then rmdir nih/pinned/ghc-9.14.1; fi
    ln -s ${ghcSource} nih/pinned/ghc-9.14.1
  '';
  nativeBuildInputs = [ gradle python3 git proot ];
  JAVA_HOME = jam;
  _JAVA_SR_SIGNUM = "64";
  mitmCache = gradle.fetchDeps {
    pkg = final.finalPackage;
    data = ./gradle-deps.json;
    useBwrap = false;
  };
  gradleBuildTask = "installDist";
  gradleUpdateTask = "installDist";
  gradleFlags = [ "--max-workers=2" "-Pthc.docsRevision=${revision}" ];
  # Keep the supplier tree byte-identical. The JVM and javac run within the
  # same FHS filesystem used by the development image, without privileged mounts.
  preBuild = ''
    gradle() {
      local flagsArray=()
      concatTo flagsArray gradleFlags gradleFlagsArray
      local binds=(-b /nix -b /proc -b /dev -b "$PWD" -b "$TMPDIR" -b "$TMPDIR:/tmp" -b "$GRADLE_USER_HOME")
      if [ -n "''${MITM_CACHE_CERT_DIR:-}" ]; then
        binds+=(-b "$MITM_CACHE_CERT_DIR")
      fi
      ${proot}/bin/proot -r ${toolchain.fhsenv} "''${binds[@]}" -w "$PWD" \
        /usr/bin/env PATH="/usr/bin:$PATH" LD_LIBRARY_PATH=/usr/lib:/usr/lib64 \
        ${gradle}/bin/gradle "''${flagsArray[@]}" "$@"
    }
  '';
  doCheck = false;
  installPhase = ''
    runHook preInstall
    mkdir -p "$out"
    cp -a build/install/thc/. "$out/"
    runHook postInstall
  '';
  dontFixup = true;
})
