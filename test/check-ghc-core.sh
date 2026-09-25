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

"$ghc" -O2 -fforce-recomp -this-unit-id core-full-1-test -fwrite-if-simplified-core -odir "$scratch/with" -hidir "$scratch/with" -c "$scratch/Probe.hs"
"$ghc" -O2 -fforce-recomp -this-unit-id core-thin-1-test -odir "$scratch/without" -hidir "$scratch/without" -c "$scratch/Probe.hs"

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
  *' describe ghc-internal-'*)
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

# Synthetic package metadata selects genuine interfaces compiled above. This
# isolates the capability/identity controls from whether the host is patched.
cat > "$scratch/core-full-1-test.conf" <<EOF
name: core-full
version: 1
id: core-full-1-test
exposed: True
exposed-modules: Probe
import-dirs: "$scratch/with"
EOF
cat > "$scratch/core-thin-1-test.conf" <<EOF
name: core-thin
version: 1
id: core-thin-1-test
exposed: True
exposed-modules: Probe
import-dirs: "$scratch/without"
EOF
cat > "$scratch/core-facade-1-test.conf" <<EOF
name: core-facade
version: 1
id: core-facade-1-test
exposed: True
exposed-modules: Public from core-full-1-test:Probe
depends: core-full-1-test
EOF
cat > "$scratch/fixture-ghc-pkg" <<'SH'
#!/bin/sh
case " $* " in
  *' field core-full id '*) printf '%s\n' core-full-1-test ;;
  *' field core-thin id '*) printf '%s\n' core-thin-1-test ;;
  *' field core-facade id '*) printf '%s\n' core-facade-1-test ;;
  *' describe core-'*)
    for last do :; done
    cat "$CORE_FIXTURE/$last.conf"
    ;;
  *) exec "$REAL_GHC_PKG" "$@" ;;
esac
SH
chmod +x "$scratch/fixture-ghc-pkg"
check_packages() {
  GHC="$ghc" GHC_PKG="$scratch/fixture-ghc-pkg" REAL_GHC_PKG="$real_pkg" \
    CORE_FIXTURE="$scratch" "$runner" -f "$ghc" \
    --ghc-arg=-package --ghc-arg=ghc --ghc-arg=-package --ghc-arg=Cabal \
    scripts/check-ghc-core.hs check "$@"
}
reject_packages() {
  reason=$1
  shift
  if check_packages "$@" > "$scratch/output" 2>&1; then
    echo 'invalid package capability incorrectly accepted' >&2
    exit 1
  fi
  grep -q "$reason" "$scratch/output"
}
check_packages core-full > "$scratch/output"
grep -q 'complete Core present in 1 installed interfaces' "$scratch/output"
check_packages core-facade core-full core-full > "$scratch/output"
grep -q 'complete Core present in 1 installed interfaces' "$scratch/output"
cp "$scratch/core-facade-1-test.conf" "$scratch/facade.conf"
sed '/^depends:/d' "$scratch/facade.conf" > "$scratch/core-facade-1-test.conf"
reject_packages 'no concrete interface in its dependency closure for reexport' core-facade
cp "$scratch/facade.conf" "$scratch/core-facade-1-test.conf"
reject_packages 'mi_simplified_core is absent' core-full core-thin
printf 'depends: core-thin-1-test\n' >> "$scratch/core-full-1-test.conf"
reject_packages 'mi_simplified_core is absent' core-facade
# A flagged interface copied into another package is not that package's Core.
cp "$scratch/with/Probe.hi" "$scratch/without/Probe.hi"
reject_packages 'interface belongs to another unit' core-thin
mv "$scratch/with/Probe.hi" "$scratch/with/Other.hi"
reject_packages 'lacks installed interfaces for Probe' core-full
cp "$scratch/with/Other.hi" "$scratch/with/Probe.hi"
reject_packages 'interface module does not match its installed path' core-full
printf '%s\n' 'GHC Core preflight controls passed'
