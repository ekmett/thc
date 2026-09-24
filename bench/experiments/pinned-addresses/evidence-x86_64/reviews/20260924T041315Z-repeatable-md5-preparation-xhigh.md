# Repeatable MD5 preparation — scoped fix and preservation proof

Worker scalar_bitcasts_x86_xhigh to root, eak-quartus, 2026-09-24 04:13:15 UTC. Task `01a0cdeb-4ff9-74e2-83d4-8745c8ff0c3a`.

Commit **`6a854cae2a8a667044bb9f0fce84f88f23e523c0`**, `/home/ekmett/ai/thc-pinned-address-storage-01a0cdeb`, branch `codex/pinned-address-storage`. Only `scripts/prepare-managed-md5.py` and new `scripts/test-prepare-managed-md5.py` changed. Final status clean. No runtime/native-source/JVM changes or root edits, no push.

Root reported the actual first full-prepare failure as `FileExistsError` at old prepare-managed-md5.py:119, exec76715 exit1, retained `/home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/20260924-000851-xfv_da56/output.log`; full JVM suite had not started. This fix does not erase or relabel that failed attempt.

## Safety and repeatability

Only the exact canonical `<checkout>/build/managed-md5-native` output is accepted. Broad, relative, custom and `..` spellings reject. Build/leaf symlinks are rejected before resolving the requested path; the source-derived checkout root alone is resolved. Existing entries must all be regular files with one of the exact19 tool-owned names; unknown files, symlinks including dangling links, nested directories and non-directory output/build objects reject before any movement.

New attempts receive an exact `attempt-owner.json` marker immediately after directory creation. This permits archiving complete or partial attempts. The marker's bytes must exactly match this tool's canonical schema/tool/directory record. For backward compatibility, an unmarked legacy success is admitted only with the complete original18 filenames, expected pinned reference/layout metadata, exact native executable command, and all17 artifact hashes matching that directory. Unmarked partial/empty or corrupted legacy attempts are ambiguous and intentionally reject; no destructive recovery is inferred.

Archival reserves a unique sibling container with `tempfile.mkdtemp` and renames the entire validated old output into its `managed-md5-native` child, then creates a fresh canonical output. No existing file/archive is overwritten or deleted. The caller retains its normal build lease. Historical manifests are preserved unchanged; their paths describe the original location, not a rewritten relocated provenance claim.

Native verification, inventory, blob and digest functions are AST-identical to859e974. Compilation/header/libdir/native layout and model logic remain unchanged. The only new native artifact is the ownership marker (19 files per successful new attempt versus18 legacy).

## Completed checks

- Ten pure Python safety tests: **PASS normal 0.006s; PASS -O 0.005s**, exit0, no skips. Covers fresh output, successful/partial archival, legacy ownership/hash verification, marked/unmarked empty partial, wrong/malformed marker, unknown file, nested directory, regular/dangling symlink, leaf file/symlink, symlinked build, and broad/custom targets.
- `git diff --check`: PASS. Four verification/inventory function AST comparisons to859e974: PASS.
- Two successive native preparations under separate common build leases: **both exit0**, each558 cases,2232 context/output rows,15 alias snapshots;540 digests matched independent hashlib. No JVM and no retry after failure.

Each native command, from the worker checkout after sourcing `/home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh`:

```sh
python3 /home/ekmett/.codex/worktrees/98d0/cult/tools/resource_run.py \
  --build-dir /home/ekmett/ai/thc-pinned-address-storage-01a0cdeb/build -- \
  python3 scripts/prepare-managed-md5.py \
  --reference-dir /home/ekmett/ai/thc-pinned-addresses-01a0cdeb/bench/experiments/pinned-addresses/reference
```

Resource logs:

- `/home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/20260924-001256-1y9mtjvg/output.log`
- `/home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/20260924-001257-tngnvy_l/output.log`

An independent wrapper hashed every file before each run and after relocation, compared the complete filename/hash maps, and rechecked both archives after the second run. Exact preservation:

| Attempt | Location under worker build/ | Preserved files | Provenance SHA-256 |
| --- | --- | ---: | --- |
| Original859e native capture | `managed-md5-native.previous-q5pyor0x/managed-md5-native` | 18 | `2fa8a5f2e26ac4a02302d6811149c3f68d2d66bfca09c74b393b924ef8879d7d` |
| First repeated preparation | `managed-md5-native.previous-fe4nm0wr/managed-md5-native` | 19 | `ac3843423659696c1d072ce4a89fdd97a5dc2dce24b2a86a35970b64cec63b77` |
| Second repeated preparation | `managed-md5-native` | 19 | `ac3843423659696c1d072ce4a89fdd97a5dc2dce24b2a86a35970b64cec63b77` |

All three native.stdout hashes are exactly `3737ca61db2da76cb4ce2718c6fe6ef317f71ec7c8de2f46f86ef04953b600ff`. The original18 files and first-repeat19 files are byte-for-byte unchanged, not regenerated substitutes.

Source SHA-256:

```
f5f6c2f38690e83689a59d7b96abbcd719eb15928bd0399b04961486f81a1c99  scripts/prepare-managed-md5.py
eed0e951ade1936063092a60ecf5f85678472aaaf0aca23c880c2e109ebb29a4  scripts/test-prepare-managed-md5.py
```

Next: root reviews/cherry-picks the two-file fix, commits its integrated source and resumes full preparation from the top, retaining the already observed first full-prepare failure separately. No runtime/guest correctness claim is added by this workflow fix.
