# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{ closureInfo, python3, proot, bash
, nativeTools, jvm, jam, toolchain }:
let
  compiler = nativeTools.compiler;
  dependencies = closureInfo {
    rootPaths = builtins.filter (dependency: dependency != null)
      (nativeTools.propagatedBuildInputs ++ nativeTools.buildInputs);
  };
in nativeTools.overrideAttrs (old: {
  nativeBuildInputs = (old.nativeBuildInputs or []) ++ [ python3 proot ];
  preInstall = (old.preInstall or "") + ''
    # Save the builder's real dependency registrations before Cabal changes its
    # packageConfDir to the output DB. No package identity or path is rewritten.
    mkdir -p "$out/lib/thc/package.conf.d"
    cp "$packageConfDir/"*.conf "$out/lib/thc/package.conf.d/"
  '';
  postInstall = (old.postInstall or "") + ''
    cp "$out/lib/ghc-9.14.1/lib/package.conf.d/"*.conf "$out/lib/thc/package.conf.d/"
    ${compiler}/bin/ghc-pkg --no-user-package-db --package-db "$out/lib/thc/package.conf.d" recache
    ${compiler}/bin/ghc-pkg --no-user-package-db --package-db "$out/lib/thc/package.conf.d" check
    mkdir -p "$out/libexec/thc"
    mv "$out/bin/thc" "$out/libexec/thc/thc-driver"
    cat > "$out/bin/thc" <<WRAPPER
#!${bash}/bin/bash
set -euo pipefail
exec ${proot}/bin/proot -R ${toolchain.fhsenv} -b /nix -b "\$PWD" \\
  -b "\''${TMPDIR:-/tmp}" -b "\''${TMPDIR:-/tmp}:/tmp" -w "\$PWD" \\
  /usr/bin/env PATH="${jam}/bin:/usr/bin:\$PATH" LD_LIBRARY_PATH=/usr/lib:/usr/lib64 \\
  JAVA_HOME=${jam} GRAALVM_HOME=${jam} _JAVA_SR_SIGNUM=64 \\
  GHC=${compiler}/bin/ghc GHC_PKG=${compiler}/bin/ghc-pkg \\
  SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt \\
  "$out/libexec/thc/thc-driver" "\$@"
WRAPPER
    chmod +x "$out/bin/thc"
  '';
  # Construction follows stripping/RPATH fixup: provenance describes final bytes.
  postFixup = (old.postFixup or "") + ''
    # The distribution contains supplier identity libraries: preserve their bytes.
    mkdir -p "$out/lib/thc/jvm"
    cp -a ${jvm}/. "$out/lib/thc/jvm/"
    runtimeUnit=$(${compiler}/bin/ghc-pkg --no-user-package-db \
      --package-db "$out/lib/thc/package.conf.d" find-module THC.Internal.Exception --simple-output --show-unit-ids)
    ${proot}/bin/proot -r ${toolchain.fhsenv} \
      -b /nix -b /proc -b /dev -b "$PWD" -b "$TMPDIR" -b "$TMPDIR:/tmp" -w "$PWD" \
      /usr/bin/env PATH="/usr/bin:$PATH" LD_LIBRARY_PATH=/usr/lib:/usr/lib64 \
      "$out/libexec/thc/thc-driver" export-installed-unit "$runtimeUnit" \
      --with-ghc ${compiler}/bin/ghc --with-ghc-pkg ${compiler}/bin/ghc-pkg \
      --interface-helper "$out/bin/thc-interface" --package-db "$out/lib/thc/package.conf.d" \
      --target-layout "$PWD/src/driver/cbits/target-layout.c" \
      --cache-dir "$TMPDIR/runtime-cache" --dist-dir "$TMPDIR/runtime-export"
    roots=()
    while IFS= read -r dependency; do roots+=(--dependency-root "$dependency"); done < ${dependencies}/store-paths
    ${python3}/bin/python3 -B bin/plugin.py --root "$PWD" --install "$out" \
      --installed-package-db "$out/lib/thc/package.conf.d" \
      --runtime-support "$TMPDIR/runtime-export/packages.json" \
      --interface-helper "$out/bin/thc-interface" --compact "$out/bin/thc-compact" \
      --runtime "$out/lib/thc/jvm/bin/thc" \
      --ghc ${compiler}/bin/ghc --ghc-pkg ${compiler}/bin/ghc-pkg "''${roots[@]}"
  '';
  passthru = (old.passthru or {}) // { inherit jvm jam toolchain; };
  meta = (old.meta or {}) // { mainProgram = "thc"; };
})
