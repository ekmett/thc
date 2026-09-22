#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
out="$root/build/compiler"
mkdir -p "$out"
case "$(uname -s)" in Darwin) suffix=dylib ;; *) suffix=so ;; esac
"$GHC" --make -O1 -dynamic -shared -fPIC -package ghc -package bytestring -package directory -package filepath \
  -this-unit-id thc-core-plugin-0.1 -hisuf dyn_hi -osuf dyn_o -icompiler -odir "$out" -hidir "$out" \
  compiler/Thc/Plugin.hs -o "$out/libHSthc-core-plugin-0.1-ghc$version.$suffix"
if [ ! -d "$out/package.conf.d" ]; then
  "$GHC_PKG" init "$out/package.conf.d"
fi
depends=""
for pkg in ghc base bytestring directory filepath; do
  pkg_id=$("$GHC_PKG" field "$pkg" id --simple-output)
  depends="$depends $pkg_id"
done
cat > "$out/thc-core-plugin.conf" <<CONF
name: thc-core-plugin
version: 0.1
id: thc-core-plugin-0.1
key: thc-core-plugin-0.1
exposed: True
exposed-modules: Thc.Plugin
import-dirs: $out
library-dirs: $out
dynamic-library-dirs: $out
hs-libraries: HSthc-core-plugin-0.1
depends: $depends
CONF
"$GHC_PKG" --package-db "$out/package.conf.d" update --force "$out/thc-core-plugin.conf"
printf '%s\n' "Built THC Core plugin in $out"
