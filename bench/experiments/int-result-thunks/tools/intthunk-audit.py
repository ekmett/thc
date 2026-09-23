#!/usr/bin/env python3
"""Read-only audit of IntThunkExperiment logs; never launches Java or a build.

Usage: python3 work/intthunk-audit.py outputs/int-thunk-experiment/confirm [--json]
Each completed log and adjacent runner manifest are one JVM fork. Windows within
a fork are summarized, not treated as independent experimental replicates.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

MOD = 1 << 64
TRACE = re.compile(r"\[engine\].*(?:\bopt\b|deopt|invalidat|compil)", re.I)


def force_stride(case):
    return {"partial": 4, "partial-half": 2}.get(case, 1)


def summary(values):
    if not values:
        return None
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def checksum_audit(samples):
    """Check checksums independently of the source's expected() implementation.

    The first generation is not logged. Solve checksum congruences for that
    generation modulo 2**64, then require every consecutive sample to agree.
    This proves cross-window consistency, not the unlogged absolute start.
    """
    residue, modulus, previous_calls = 0, 1, 0
    for s in samples:
        n, case, calls = s["size"], s["case"], s["calls"]
        stride = force_stride(case)
        count = (n + stride - 1) // stride
        repeats = s["forcedReads"] // (n * calls) if case in {"reread", "published-read"} else 1
        if case == "never":
            a, b = n, 1
        elif case in {"shared", "cached"}:
            seed = (1 << 32) if case == "shared" else 11
            a, b = (seed * 3 + 17) * count, 0
        else:
            step = 3 * stride
            a = ((1 << 32) * 3 + 17) * count + step * count * (count - 1) // 2
            a *= repeats
            b = 0 if case in {"reread", "published-read"} else 65537 * 3 * count
        k = b * calls
        constant = a * calls + b * calls * (calls - 1) // 2 + k * previous_calls
        wanted = (s["checksum"] - constant) % MOD
        divisor = math.gcd(k, MOD)
        if wanted % divisor:
            return {"valid": False, "reason": f"sample {s['sample']} has no valid generation"}
        this_modulus = MOD // divisor
        this_residue = 0 if this_modulus == 1 else ((wanted // divisor) * pow(k // divisor, -1, this_modulus)) % this_modulus
        if (this_residue - residue) % min(modulus, this_modulus):
            return {"valid": False, "reason": f"sample {s['sample']} disagrees with prior generations"}
        if this_modulus > modulus:
            residue, modulus = this_residue, this_modulus
        previous_calls += calls
    return {"valid": True, "scope": "exact constant-input sum" if modulus == 1 else "consecutive-generation congruences; absolute warmup generation unlogged",
            "firstGenerationResidue": residue, "firstGenerationModulus": modulus}


def audit(path):
    # A manifest is written only after JVM termination. Check it before reading
    # the log so a live writer cannot turn a partial snapshot into a false fail.
    meta_path = path.with_suffix(".json")
    try:
        meta = json.loads(meta_path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {"log": str(path), "sampleCount": None, "issues": [],
                "status": "incomplete: runner manifest not yet complete"}
    raw = path.read_bytes()
    lines = raw.decode(errors="replace").splitlines()
    events = []
    for number, line in enumerate(lines, 1):
        if line.startswith("{"):
            try:
                obj = json.loads(line)
                if isinstance(obj, dict):
                    events.append((number, obj))
            except json.JSONDecodeError:
                pass
    samples_at = [(n, x) for n, x in events if x.get("event") == "sample"]
    samples = [x for _, x in samples_at]
    warmups = [(n, x) for n, x in events if x.get("event") == "warmup"]
    configuration = next((x for _, x in events if x.get("event") == "configuration"), {})
    result = {"log": str(path), "sampleCount": len(samples), "issues": []}
    if meta.get("exitCode") != 0:
        result["issues"].append(f"runner exitCode {meta.get('exitCode')}")
    if meta.get("logSha256") != hashlib.sha256(raw).hexdigest():
        result["issues"].append("log hash differs from runner manifest")
    command = meta.get("command", [])
    if "bench" not in command or configuration.get("action") != "bench":
        result["status"] = "not a timing log"
        return result
    args = command[command.index("bench") + 1:]
    mode, case, size, warmup_count, expected_samples, repeats = args[:6]
    size, expected_samples, repeats = int(size), int(expected_samples), int(repeats)
    window_ms = int(args[6]) if len(args) > 6 else 1000
    result.update(mode=mode, case=case, size=size, readRepeats=repeats,
                  requestedWindowMillis=window_ms, expectedSamples=expected_samples,
                  traceEnabled="-Dthc.traceCompilation=true" in command)
    if len(samples) != expected_samples:
        result["issues"].append("sample count differs from command")
    if len(warmups) != 1:
        result["issues"].append("missing or duplicate warmup completion event")
    if not result["traceEnabled"]:
        result["issues"].append("Truffle compilation tracing was not enabled")
    reads_only = case in {"reread", "published-read"}
    stride = force_stride(case)
    per_call_forced = 0 if case == "never" else ((size + stride - 1) // stride) * (repeats if reads_only else 1)
    per_call_ops = size if case == "never" else per_call_forced
    for index, s in enumerate(samples):
        checks = {
            "sample sequence": s.get("sample") == index,
            "mode/case/size": (s.get("mode"), s.get("case"), s.get("size")) == (mode, case, size),
            "positive calls and elapsed": s.get("calls", 0) > 0 and s.get("elapsedNs", 0) > 0,
            "compiled entries": s.get("compiledEntries") == s.get("calls") and s.get("guestLastTierInstalled") is True,
            "allocated carrier count": s.get("allocatedCells") == (0 if reads_only else size * s["calls"]),
            "forced reads": s.get("forcedReads") == per_call_forced * s["calls"],
            "operation denominator": s.get("operations") == per_call_ops * s["calls"],
            "allocated-byte counter": isinstance(s.get("allocatedBytes"), int) and s["allocatedBytes"] >= 0,
            "window minimum duration": s.get("elapsedNs", 0) >= window_ms * 1_000_000,
            "checksum field": isinstance(s.get("checksum"), int),
        }
        result["issues"].extend(f"sample {index}: {name}" for name, ok in checks.items() if not ok)
    if samples:
        result["checksumAudit"] = checksum_audit(samples)
        if not result["checksumAudit"]["valid"]:
            result["issues"].append("independent checksum audit failed")
        start = warmups[-1][0] if warmups else 0
        stop = samples_at[-1][0]
        measured_events = [{"line": n, "text": line} for n, line in enumerate(lines, 1) if start < n < stop and TRACE.search(line)]
        result["measuredCompilationEvents"] = measured_events
        if measured_events:
            result["issues"].append("Truffle compiler event between warmup completion and last sample")
        ns = [s["elapsedNs"] / s["operations"] for s in samples]
        byte_ops = [s["allocatedBytes"] / s["operations"] for s in samples]
        byte_carriers = [s["allocatedBytes"] / s["allocatedCells"] for s in samples if s["allocatedCells"]]
        result.update(nsPerOp=summary(ns), bytesPerOp=summary(byte_ops), bytesPerCarrier=summary(byte_carriers),
                      bytesPerCall=summary([s["allocatedBytes"] / s["calls"] for s in samples]),
                      firstToLastNsRatio=ns[-1] / ns[0], windowRangeNsRatio=max(ns) / min(ns),
                      warmup=warmups[-1][1] if warmups else None,
                      windows=[dict(s, nsPerOp=s["elapsedNs"] / s["operations"], bytesPerOp=s["allocatedBytes"] / s["operations"]) for s in samples])
    result["status"] = "valid" if not result["issues"] else "audit failed"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--json", action="store_true", help="emit full JSON rather than compact tables")
    args = parser.parse_args()
    logs = sorted(set(path for p in args.paths for path in (p.rglob("*.log") if p.is_dir() else [p])))
    records = [audit(p) for p in logs]
    grouped = {}
    for r in records:
        if r["status"] == "valid":
            key = (r["case"], r["mode"], r["size"], r["readRepeats"])
            grouped.setdefault(key, []).append(r)
    groups = []
    for (case, mode, size, repeats), rows in sorted(grouped.items()):
        group = dict(case=case, mode=mode, size=size, readRepeats=repeats, forks=len(rows),
                     forkMedianNsPerOp=summary([r["nsPerOp"]["median"] for r in rows]),
                     forkMedianBytesPerOp=summary([r["bytesPerOp"]["median"] for r in rows]))
        baseline = grouped.get((case, "ordinary", size, repeats))
        if baseline:
            group["nsRatioToOrdinary"] = group["forkMedianNsPerOp"]["median"] / statistics.median(r["nsPerOp"]["median"] for r in baseline)
        groups.append(group)
    if args.json:
        print(json.dumps({"logs": records, "groups": groups, "limits": [
            "Forks are independent; windows within a fork are not independent replicates.",
            "No thresholds or confidence claims are inferred from drift or range.",
            "Trace audit covers Truffle events in merged log order, not every host-JIT/GC/scheduling event.",
            "Allocation is invoking-thread bytes including driver bridges; reachable graph is separate."]}, indent=2))
    else:
        print("log\tstatus\tns/op median\tB/op median\tlast/first ns\twindow max/min")
        for r in records:
            values = [str(Path(r["log"]).name), r["status"]]
            if "nsPerOp" in r:
                values += [f'{r["nsPerOp"]["median"]:.6g}', f'{r["bytesPerOp"]["median"]:.6g}', f'{r["firstToLastNsRatio"]:.4f}', f'{r["windowRangeNsRatio"]:.4f}']
            print("\t".join(values))
            for issue in r["issues"]:
                print("  ISSUE: " + issue)
        print("\ncase\tmode\tsize\trepeats\tforks\tfork median ns/op\tfork min..max\tratio to ordinary")
        for g in groups:
            s = g["forkMedianNsPerOp"]
            print(f'{g["case"]}\t{g["mode"]}\t{g["size"]}\t{g["readRepeats"]}\t{g["forks"]}\t{s["median"]:.6g}\t{s["min"]:.6g}..{s["max"]:.6g}\t{g.get("nsRatioToOrdinary", float("nan")):.4f}')


if __name__ == "__main__":
    main()
