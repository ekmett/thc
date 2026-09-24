#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -eu

ghc=${GHC:-ghc}
ghc=$(command -v "$ghc")
runner=${RUN_GHC:-runghc}
scratch=$(mktemp -d "${TMPDIR:-/tmp}/thc-ghc-core.XXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
mkdir "$scratch/with" "$scratch/without"
cat > "$scratch/Probe.hs" <<'HS'
module Probe (entry) where
{-# NOINLINE worker #-}
{-# OPAQUE entry #-}
worker :: Int -> Int
worker x = x + 7
entry :: Int -> Int
entry x = worker x
HS

"$ghc" -O2 -fforce-recomp -fwrite-if-simplified-core -odir "$scratch/with" -hidir "$scratch/with" -c "$scratch/Probe.hs"
"$ghc" -O2 -fforce-recomp -odir "$scratch/without" -hidir "$scratch/without" -c "$scratch/Probe.hs"

probe() {
  GHC="$ghc" "$runner" -f "$ghc" --ghc-arg=-package --ghc-arg=ghc --ghc-arg=-package --ghc-arg=Cabal scripts/check-ghc-core.hs probe-interface "$1"
}
probe "$scratch/with/Probe.hi" > "$scratch/output"
grep -q 'interface contains simplified Core' "$scratch/output"
if probe "$scratch/without/Probe.hi" > "$scratch/output" 2>&1; then
  echo 'unflagged interface incorrectly accepted' >&2
  exit 1
fi
grep -q 'mi_simplified_core is absent' "$scratch/output"

printf 'not an interface\n' > "$scratch/malformed.hi"
if probe "$scratch/malformed.hi" > "$scratch/output" 2>&1; then
  echo 'malformed interface incorrectly accepted' >&2
  exit 1
fi

cat > "$scratch/other-ghc-pkg" <<'SH'
#!/bin/sh
printf '/tmp/a-different-ghc-package-database\n'
SH
chmod +x "$scratch/other-ghc-pkg"
if GHC="$ghc" GHC_PKG="$scratch/other-ghc-pkg" "$runner" -f "$ghc" --ghc-arg=-package --ghc-arg=ghc --ghc-arg=-package --ghc-arg=Cabal scripts/check-ghc-core.hs check > "$scratch/output" 2>&1; then
  echo 'mismatched ghc-pkg incorrectly accepted' >&2
  exit 1
fi
grep -q 'different global package databases' "$scratch/output"

# ghc-pkg's package-description format quotes paths containing spaces.  The
# package stays the same; only its import-dir spelling passes through a symlink.
real_pkg=$(dirname "$ghc")/ghc-pkg
real_import=$("$real_pkg" --global --no-user-package-db --expand-pkgroot describe ghc-internal |
  awk '/^import-dirs:/ { getline; sub(/^[[:space:]]+/, ""); print; exit }')
ln -s "$real_import" "$scratch/import dir with spaces"
cat > "$scratch/space-ghc-pkg" <<'SH'
#!/bin/sh
case " $* " in
  *' describe ghc-internal '*)
    "$REAL_GHC_PKG" "$@" | awk -v replacement="$SPACE_IMPORT" '
      /^import-dirs:/ { print; getline; print "    \"" replacement "\""; next }
      { print }
    '
    ;;
  *) exec "$REAL_GHC_PKG" "$@" ;;
esac
SH
chmod +x "$scratch/space-ghc-pkg"
GHC="$ghc" GHC_PKG="$scratch/space-ghc-pkg" REAL_GHC_PKG="$real_pkg" \
  SPACE_IMPORT="$scratch/import dir with spaces" "$runner" -f "$ghc" \
  --ghc-arg=-package --ghc-arg=ghc --ghc-arg=-package --ghc-arg=Cabal \
  scripts/check-ghc-core.hs advisory > "$scratch/output" 2> "$scratch/advisory-error"
grep -q '/space-ghc-pkg$' "$scratch/output"
printf '%s\n' 'GHC Core preflight controls passed'
