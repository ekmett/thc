#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -eu
scratch=$(mktemp -d "${TMPDIR:-/tmp}/thc toolchain.XXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
mkdir -p "$scratch/install A/bin" "$scratch/install B/bin" "$scratch/shim" "$scratch/db A" "$scratch/db B"

cat > "$scratch/install A/bin/ghc-9.14.1" <<'SH'
#!/bin/sh
if [ "$0" != "$TEST_SELECTED_GHC" ]; then
  echo 'compiler invocation spelling changed' >&2
  exit 31
fi
case "$1" in
  --numeric-version) printf '9.14.1\n' ;;
  --print-global-package-db) printf '%s\n' "$TEST_DB_A" ;;
  *) exit 9 ;;
esac
SH
cat > "$scratch/install A/bin/ghc-pkg-9.14.1" <<'SH'
#!/bin/sh
case "$1" in
  --version) printf 'GHC package manager version 9.14.1\n' ;;
  --global) printf '%s\n    ghc-internal-9.1401.0\n' "$TEST_DB_A" ;;
  *) exit 9 ;;
esac
SH
cat > "$scratch/install A/bin/ghc-pkg" <<'SH'
#!/bin/sh
case "$1" in
  --version) printf 'GHC package manager version 9.14.1\n' ;;
  --global) printf '%s\n    ghc-internal-9.1401.0\n' "$TEST_DB_B" ;;
  *) exit 9 ;;
esac
SH
cp "$scratch/install A/bin/ghc-pkg" "$scratch/install B/bin/ghc-pkg-9.14.1"
chmod +x "$scratch/install A/bin/"* "$scratch/install B/bin/"*
ln -s "$scratch/install A/bin/ghc-9.14.1" "$scratch/shim/ghc"
ln -s "$scratch/install A/bin/ghc-pkg-9.14.1" "$scratch/compatible pkg"
export TEST_DB_A="$scratch/db A" TEST_DB_B="$scratch/db B" TEST_SELECTED_GHC="$scratch/shim/ghc"

# Preserve the selected compiler symlink while finding its companion in
# installation A; the versioned sibling wins over the incompatible fallback.
(
  unset GHC_PKG
  GHC="$scratch/shim/ghc"
  . bin/toolchain.sh
  [ "$GHC" = "$scratch/shim/ghc" ]
  [ "$GHC_PKG" = "$(realpath "$scratch/install A/bin/ghc-pkg-9.14.1")" ]
)

# A same-version package manager from another installation is still wrong.
if (
  GHC="$scratch/shim/ghc"
  GHC_PKG="$scratch/install B/bin/ghc-pkg-9.14.1"
  . bin/toolchain.sh
) > "$scratch/output" 2>&1; then
  echo 'different global package databases incorrectly accepted' >&2
  exit 1
fi
grep -q 'different global package databases' "$scratch/output"

# A failed package query is a failure even if it printed the expected DB path.
cat > "$scratch/failing-ghc-pkg" <<'SH'
#!/bin/sh
case "$1" in
  --version) printf 'GHC package manager version 9.14.1\n' ;;
  --global) printf '%s\n' "$TEST_DB_A"; exit 29 ;;
  *) exit 9 ;;
esac
SH
chmod +x "$scratch/failing-ghc-pkg"
if (
  GHC="$scratch/shim/ghc"
  GHC_PKG="$scratch/failing-ghc-pkg"
  . bin/toolchain.sh
) > "$scratch/output" 2>&1; then
  echo 'failed ghc-pkg query incorrectly accepted' >&2
  exit 1
fi
grep -q 'could not list ghc-internal' "$scratch/output"

# An explicit compatible path, including spaces, remains supported.
(
  GHC="$scratch/shim/ghc"
  GHC_PKG="$scratch/compatible pkg"
  . bin/toolchain.sh
  [ "$GHC_PKG" = "$scratch/compatible pkg" ]
)

cat > "$scratch/cabal stub" <<'SH'
#!/bin/sh
printf '%s\n' "$@" > "$TEST_CABAL_ARGS"
exit 27
SH
chmod +x "$scratch/cabal stub"
if GHC="$scratch/shim/ghc" GHC_PKG= CABAL="$scratch/cabal stub" \
   TEST_CABAL_ARGS="$scratch/cabal-args" bin/build-compiler.sh > "$scratch/output" 2>&1; then
  echo 'Cabal stub unexpectedly succeeded' >&2
  exit 1
fi
grep -Fxq -e "--with-compiler=$scratch/shim/ghc" "$scratch/cabal-args"
grep -Fxq -e "--with-hc-pkg=$(realpath "$scratch/install A/bin/ghc-pkg-9.14.1")" "$scratch/cabal-args"
printf '%s\n' 'Compiler toolchain selection controls passed'
