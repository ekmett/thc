#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Use the ordinary package path, including its genuine foreign-exception runtime.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
demo=$1
shift
case "$demo" in
  javascript) module=JavaScriptDemo ;;
  polyglot) module=PolyglotDemo ;;
  host-resource) module=HostResource ;;
  *) echo "Unknown demo: $demo" >&2; exit 1 ;;
esac
. "$root/bin/toolchain.sh"
make check-java >/dev/null
cabal run exe:thc -- acquire "thc-examples:exe:$demo" \
  --project-dir "$root/src/examples" --thc-root "$root" \
  --dist-dir "$root/build/$demo" "$@"
# Embed the IO action; the generated :Main wrapper owns the GHC CLI lifecycle.
entry=$(python3 -c 'import json, sys
with open(sys.argv[1]) as source:
    plan = json.load(source)
(unit,) = (item["id"] for item in plan["install-plan"]
           if item.get("pkg-name") == "thc-examples" and item.get("component-name") == "exe:" + sys.argv[2])
print(unit + ":" + sys.argv[3] + ".main")' "build/$demo/native/cache/plan.json" "$demo" "$module")
printf "%s\n" "$entry" > "build/$demo/entry.txt"
python3 bin/audit-core.py --package-manifest "build/$demo/packages.json" \
  --entry "$entry" --io-main --output "build/$demo/audit.json"
