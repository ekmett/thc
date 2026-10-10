# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{ lib, stdenvNoCC, fetchurl, pin }:
assert pin.transport.kind == "github-release-asset";
stdenvNoCC.mkDerivation {
  pname = "thc-jam-graalvm";
  version = pin.producer.releaseTag;
  src = fetchurl {
    url = pin.transport.url;
    sha256 = pin.transport.tarSha256;
  };
  sourceRoot = "graalvm";
  dontConfigure = true;
  dontBuild = true;
  # THC verifies the supplier's complete installation digest. Run these original
  # bytes in an FHS environment instead of rewriting ELF interpreters or RPATHs.
  dontFixup = true;
  installPhase = ''
    mkdir -p "$out"
    cp -a . "$out/"
  '';
  passthru = { inherit pin; };
  meta = {
    description = "Unmodified pinned Jam GraalVM distribution for THC";
    homepage = "https://github.com/ekmett/jam";
    platforms = [ "x86_64-linux" ];
    sourceProvenance = [ lib.sourceTypes.binaryNativeCode ];
  };
}
