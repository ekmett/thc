#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
out="$root/build/compiler"
mkdir -p "$out"
case "$(uname -s)" in Darwin) suffix=dylib ;; *) suffix=so ;; esac
"$GHC" --make -O1 -dynamic -shared -fPIC -package ghc -package bytestring -package directory -package filepath -package containers \
  -this-unit-id thc-core-plugin-0.1 -hisuf dyn_hi -osuf dyn_o -icompiler -odir "$out" -hidir "$out" \
  compiler/THC/Plugin.hs -o "$out/libHSthc-core-plugin-0.1-ghc$version.$suffix"
if [ ! -d "$out/package.conf.d" ]; then
  "$GHC_PKG" init "$out/package.conf.d"
fi
depends=""
for pkg in ghc base bytestring directory filepath containers; do
  pkg_id=$("$GHC_PKG" field "$pkg" id --simple-output)
  depends="$depends $pkg_id"
done
pending_conf=$(mktemp "$out/.thc-core-plugin.conf.XXXXXX")
trap 'rm -f "$pending_conf"' EXIT HUP INT TERM
cat > "$pending_conf" <<CONF
name: thc-core-plugin
version: 0.1
id: thc-core-plugin-0.1
key: thc-core-plugin-0.1
exposed: True
exposed-modules: THC.Plugin
import-dirs: $out
library-dirs: $out
dynamic-library-dirs: $out
hs-libraries: HSthc-core-plugin-0.1
depends: $depends
CONF
# GHC still checks every plugin source above. Rewriting an identical package DB
# after each preparer can invalidate the next shared-library link check.
registered="$out/package.conf.d/thc-core-plugin-0.1.conf"
registered_copy="$out/thc-core-plugin.registered.conf"
package_cache="$out/package.conf.d/package.cache"
package_cache_copy="$out/thc-core-plugin.package.cache"
if ! cmp -s "$pending_conf" "$out/thc-core-plugin.conf" || \
   [ ! -s "$package_cache" ] || \
   ! cmp -s "$registered" "$registered_copy" || \
   ! cmp -s "$package_cache" "$package_cache_copy"; then
  mv "$pending_conf" "$out/thc-core-plugin.conf"
  "$GHC_PKG" --package-db "$out/package.conf.d" update --force "$out/thc-core-plugin.conf"
  cp "$registered" "$registered_copy"
  cp "$package_cache" "$package_cache_copy"
fi
printf '%s\n' "Built THC Core plugin in $out"
