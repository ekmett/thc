#!/usr/bin/env python3
"""Compare two immutable THC runtime distributions against one native Map oracle.

All nine timing processes run serially. Each engine occupies each run-order
position once. Timings include each engine's host-call boundary, exclude startup
and warmup, and describe this workload rather than general Haskell performance.
Host and power observations are diagnostic; power warnings do not reject timings.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import sys
import time

ENTRY = 'mapAggregate'
FORKS, SAMPLES, SAMPLE_SECONDS = 3, 5, 2
# The Polyglot entry itself compiles at 10,000 calls. Warm beyond it as well
# as the guest roots; a time-only warmup can leave it compiling in measurement.
JVM_WARM_SECONDS, NATIVE_WARM_SECONDS, MINIMUM_WARM_CALLS = 15, 1, 12000
ENGINES = ('baseline', 'candidate', 'native')
FIELDS = ['entry', 'sample', 'repetitions', 'inputBase', 'checksum', 'elapsedNs']
CRITICAL_BATTERY_PERCENT = 10  # Warning only; never changes timing validation.
EVENT = re.compile(r'\bopt\s+(?:done|start|fail|inval\w*|deopt|queued|unqueued)\b|\bdeopt(?:imization)?\b', re.I)


class InvalidRun(Exception):
    pass


def require(condition, message):
    if not condition:
        raise InvalidRun(message)


def signed64(value):
    return (value + (1 << 63)) % (1 << 64) - (1 << 63)


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def hashes(paths):
    return [{'path': str(p), 'sha256': sha256(p)} for p in sorted(paths)]


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def parse_power_status(raw):
    """Keep pmset's raw text authoritative; unknown formats remain unclassified."""
    status = {}
    source = re.search(r"Now drawing from '([^']+)'", raw)
    if source:
        status['source'] = source.group(1)
    battery = re.search(r'\b(\d{1,3})%;\s*([^;\n]+)', raw)
    if battery and 0 <= int(battery.group(1)) <= 100:
        status['batteryPercent'] = int(battery.group(1))
        status['batteryState'] = battery.group(2).strip()
    return status


def thermal_status():
    # Foundation supplies this on Apple Silicon where pmset -g therm does not.
    # Unknown/unsupported thermal states may report nominal; this is not a
    # thermometer or proof that frequency stayed constant during measurement.
    script = ('ObjC.import("Foundation"); JSON.stringify({'
              'thermalState: Number($.NSProcessInfo.processInfo.thermalState), '
              'lowPowerMode: Boolean($.NSProcessInfo.processInfo.isLowPowerModeEnabled)})')
    result = subprocess.run(['/usr/bin/osascript', '-l', 'JavaScript', '-e', script],
                            text=True, capture_output=True, timeout=3, check=False)
    if result.returncode:
        raise ValueError(result.stderr.strip() or 'Foundation power-state read failed')
    status = json.loads(result.stdout)
    state = status.get('thermalState')
    require(type(state) is int and state in range(4), 'Unknown Foundation thermal state')
    require(type(status.get('lowPowerMode')) is bool, 'Unknown Foundation low-power state')
    return {**status, 'thermalStateName': ('nominal', 'fair', 'serious', 'critical')[state]}


def power_status():
    status = {'recordedAtUtc': datetime.now(timezone.utc).isoformat()}
    try:
        result = subprocess.run(['/usr/bin/pmset', '-g', 'batt'], text=True,
                                capture_output=True, timeout=3, check=False)
        status.update(raw=result.stdout, stderr=result.stderr, returncode=result.returncode)
        if result.returncode == 0:
            status.update(parse_power_status(result.stdout))
        else:
            status['error'] = 'pmset failed; power state unavailable'
    except (OSError, subprocess.SubprocessError) as error:
        status['error'] = str(error)
    try:
        status.update(thermal_status())
    except (OSError, subprocess.SubprocessError, ValueError, InvalidRun) as error:
        status['thermalError'] = str(error)
    return status


def power_warnings(current, previous=None):
    warnings = []
    discharging = current.get('source') == 'Battery Power' or current.get('batteryState', '').lower() == 'discharging'
    if discharging and current.get('batteryPercent', 101) <= CRITICAL_BATTERY_PERCENT:
        warnings.append(f"Critical battery: {current['batteryPercent']}%; reported source={current.get('source', 'unknown')}, "
                        f"state={current.get('batteryState', 'unknown')}")
    if previous and previous.get('source') and current.get('source') and previous['source'] != current['source']:
        warnings.append(f"Power source changed: {previous['source']} -> {current['source']}")
    if current.get('error'):
        warnings.append(f"Power status unavailable: {current['error']}")
    if current.get('thermalState', -1) >= 2:
        warnings.append(f"Thermal state: {current.get('thermalStateName', current['thermalState'])}")
    if current.get('lowPowerMode') is True:
        warnings.append('macOS low-power mode is enabled')
    if current.get('thermalError'):
        warnings.append(f"Thermal status unavailable: {current['thermalError']}")
    return warnings


def read_windows(path, base, cycle_sum):
    rows = []
    with path.open(newline='') as stream:
        for values in csv.reader(stream, delimiter='\t'):
            require(len(values) == len(FIELDS), f'{path}: expected six TSV fields: {values!r}')
            row = dict(zip(FIELDS, values))
            require(row['entry'] == ENTRY, f'{path}: unexpected entry')
            for field in FIELDS[1:]:
                row[field] = int(row[field])
            require(row['sample'] == len(rows) + 1, f'{path}: missing/duplicate/out-of-order sample')
            require(row['inputBase'] == base, f'{path}: input base changed')
            count = row['repetitions']
            require(count > 0 and count % 256 == 0, f'{path}: incomplete 256-call batch')
            require(row['elapsedNs'] >= SAMPLE_SECONDS * 1_000_000_000, f'{path}: window shorter than two seconds')
            require(row['checksum'] == signed64(cycle_sum * (count // 16)), f'{path}: native 16-cycle checksum mismatch')
            rows.append(row)
    require(len(rows) == SAMPLES, f'{path}: expected {SAMPLES} windows, found {len(rows)}')
    return rows


def validate_jvm_log(path, cycle_sum, backend, source_notes=None,
                     minimum_warm_calls=MINIMUM_WARM_CALLS, warm_seconds=JVM_WARM_SECONDS):
    expected = ['PHASE WARM BEGIN', 'PHASE WARM END']
    expected += [f'PHASE MEASURE {i} {edge}' for i in range(1, SAMPLES + 1) for edge in ('BEGIN', 'END')]
    expected += ['PHASE VERIFY BEGIN', 'PHASE VERIFY END']
    phase_index, active = 0, None
    warm, diagnostics, measured_events, verify_events = None, None, [], []
    for line in path.read_text().splitlines():
        if line.startswith('PHASE '):
            require(phase_index < len(expected), f'{path}: extra phase marker: {line}')
            want = expected[phase_index]
            if want == 'PHASE WARM END':
                match = re.fullmatch(r'PHASE WARM END calls=(\d+) elapsedNs=(\d+) checksum=(-?\d+)', line)
                require(match is not None, f'{path}: malformed warmup summary')
                warm = dict(zip(('calls', 'elapsedNs', 'checksum'), map(int, match.groups())))
                require(warm['calls'] >= minimum_warm_calls and warm['calls'] % 256 == 0, f'{path}: incomplete warmup')
                require(warm['elapsedNs'] >= warm_seconds * 1_000_000_000, f'{path}: warmup shorter than {warm_seconds} seconds')
                require(warm['checksum'] == signed64(cycle_sum * (warm['calls'] // 16)), f'{path}: warmup checksum mismatch')
            elif want == 'PHASE VERIFY END':
                require(line == 'PHASE VERIFY END guestLastTierInstalled=true', f'{path}: last-tier verification failed')
            else:
                require(line == want, f'{path}: expected {want!r}, found {line!r}')
            active = ('measure' if 'MEASURE' in line else 'verify' if 'VERIFY' in line else 'warm') if line.endswith('BEGIN') else None
            phase_index += 1
        elif line.startswith('diagnostics='):
            require(diagnostics is None, f'{path}: duplicate diagnostics')
            diagnostics = json.loads(line.removeprefix('diagnostics='))
        elif EVENT.search(line):
            if active == 'measure':
                measured_events.append(line)
            elif active == 'verify':
                verify_events.append(line)
    require(phase_index == len(expected), f'{path}: incomplete phase sequence')
    require(diagnostics is not None, f'{path}: missing diagnostics')
    require(diagnostics.get('instrumented') is False, f'{path}: timing instrumentation was enabled')
    require(diagnostics.get('backend', 'ast') == backend, f'{path}: wrong Core backend')
    require(diagnostics.get('unsupportedPolicy') == 'diagnostic-traps', f'{path}: incorrect unsupported policy')
    require(diagnostics.get('unsupportedTraps') == 0, f'{path}: unsupported path entered')
    if source_notes is not None:
        enabled = source_notes == 'on'
        require(diagnostics.get('sourceNotesEnabled') is enabled, f'{path}: wrong source-note mode')
        spans = diagnostics.get('sourceSpanCount')
        roots = diagnostics.get('sourceRootCount')
        require(type(spans) is int and type(roots) is int, f'{path}: missing source attachment counts')
        if enabled:
            require(spans > 0 and roots > 0, f'{path}: source notes requested but no tree locations attached')
        else:
            require(spans == 0 and roots == 0, f'{path}: source locations attached in disabled mode')
    require(not measured_events, f'{path}: compilation/deoptimization during measurement: {measured_events}')
    require(not verify_events, f'{path}: final verification compiled or deoptimized code: {verify_events}')
    return {'lastTierVerified': True, 'warmup': warm, 'diagnostics': diagnostics,
            'measuredCompilationEvents': measured_events, 'finalVerificationEvents': verify_events}


def engine_summary(rows):
    per_fork = []
    all_windows = []
    for fork in range(1, FORKS + 1):
        windows = [r['elapsedNs'] / r['repetitions'] for r in rows if r['fork'] == fork]
        require(len(windows) == SAMPLES, f'Missing windows in fork {fork}')
        all_windows.extend(windows)
        per_fork.append({'fork': fork, 'medianNsPerCall': statistics.median(windows),
                         'windowNsPerCall': windows, 'lastVsFirstPercent': (windows[-1] / windows[0] - 1) * 100})
    return {'medianNsPerCall': statistics.median(f['medianNsPerCall'] for f in per_fork),
            'forks': per_fork, 'minWindowNsPerCall': min(all_windows), 'maxWindowNsPerCall': max(all_windows)}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('baseline_libdir', type=Path, help='immutable baseline installDist lib directory')
    parser.add_argument('candidate_libdir', type=Path, help='immutable candidate installDist lib directory')
    parser.add_argument('native_binary', type=Path, help='immutable native oracle with scalar and --bench-steady modes')
    parser.add_argument('modules_manifest', type=Path, help='one Core JSON path per line; relative paths resolve beside this manifest')
    parser.add_argument('outdir', type=Path, help='new or empty output directory; existing results are never overwritten')
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'), help='GraalVM JDK directory (default: JAVA_HOME)')
    parser.add_argument('--baseline-commit', required=True, help='baseline source revision label; runtime JAR hashes are authoritative')
    parser.add_argument('--baseline-modules-manifest', type=Path,
                        help='optional separate frozen baseline Core manifest, for controlled metadata ablations')
    parser.add_argument('--candidate-source-dir', type=Path, default=Path(__file__).resolve().parents[1] / 'src/main',
                        help='source tree to hash with the candidate (default: this checkout src/main)')
    parser.add_argument('--input-base', type=int, default=10000, help='first of sixteen varying inputs (default: 10000)')
    parser.add_argument('--baseline-backend', choices=('ast', 'bytecode'), default='ast')
    parser.add_argument('--candidate-backend', choices=('ast', 'bytecode'), default='ast')
    parser.add_argument('--baseline-source-notes', choices=('on', 'off'), help='explicit source attachment mode; also validates attached spans/roots')
    parser.add_argument('--candidate-source-notes', choices=('on', 'off'), help='explicit source attachment mode; also validates attached spans/roots')
    parser.add_argument('--baseline-jvm-option', action='append', default=[], help='repeatable JVM option recorded in the baseline command')
    parser.add_argument('--candidate-jvm-option', action='append', default=[], help='repeatable JVM option recorded in the candidate command')
    parser.add_argument('--jvm-warm-seconds', type=float, default=JVM_WARM_SECONDS,
                        help='minimum JVM warmup duration, at least 15 seconds (default: 15)')
    parser.add_argument('--minimum-warm-calls', type=int, default=MINIMUM_WARM_CALLS,
                        help='minimum JVM warmup calls, at least 12000 (default: 12000)')
    parser.add_argument('--native-warm-seconds', type=float, default=NATIVE_WARM_SECONDS,
                        help='native warmup duration, at least one second (default: 1)')
    parser.add_argument('--process-timeout', type=int, default=300, help='maximum seconds for each timing process (default: 300)')
    args = parser.parse_args()
    require(args.java_home is not None, 'Set JAVA_HOME or pass --java-home')
    require(-(1 << 63) <= args.input_base <= (1 << 63) - 16, 'Input cycle must fit signed 64-bit integers')
    require(args.process_timeout > 0, 'Process timeout must be positive')
    require(math.isfinite(args.jvm_warm_seconds) and args.jvm_warm_seconds >= JVM_WARM_SECONDS, 'JVM warmup must be at least 15 seconds')
    require(args.minimum_warm_calls >= MINIMUM_WARM_CALLS, 'JVM warmup must include at least 12000 calls')
    require(math.isfinite(args.native_warm_seconds) and args.native_warm_seconds >= NATIVE_WARM_SECONDS, 'Native warmup must be at least one second')
    java_home = Path(args.java_home).resolve(strict=True)
    java = java_home / 'bin/java'
    native = args.native_binary.resolve(strict=True)
    require(os.access(java, os.X_OK) and os.access(native, os.X_OK), 'Java and native oracle must be executable')
    release_path = java_home / 'release'
    release = dict(line.split('=', 1) for line in release_path.read_text().splitlines() if '=' in line)
    require(release.get('GRAALVM_VERSION', '').strip('"') == '25.3.4.1', 'Expected GraalVM 25.3.4.1')
    require(release.get('JAVA_VERSION', '').strip('"').split('.')[0] == '25', 'Expected JDK 25')
    libraries = {}
    for engine in ENGINES[:2]:
        directory = getattr(args, f'{engine}_libdir').resolve(strict=True)
        libraries[engine] = sorted(directory.glob('*.jar'))
        require(bool(libraries[engine]), f'No runtime JARs in {directory}')
    manifest = args.modules_manifest.resolve(strict=True)
    manifests = {'baseline': (args.baseline_modules_manifest or manifest).resolve(strict=True), 'candidate': manifest}
    modules_by_engine = {}
    for engine, engine_manifest in manifests.items():
        modules = [(engine_manifest.parent / line.strip()).resolve(strict=True)
                   for line in engine_manifest.read_text().splitlines() if line.strip()]
        require(bool(modules) and len(modules) == len(set(modules)), 'Module manifest must be nonempty with unique paths')
        require(all(',' not in str(p) for p in modules), 'Probe module paths cannot contain commas')
        modules_by_engine[engine] = modules
    source_dir = args.candidate_source_dir.resolve(strict=True)
    sources = sorted(p for p in source_dir.rglob('*') if p.is_file())
    require(bool(sources), 'Candidate source directory is empty')
    out = args.outdir.resolve()
    require(not out.exists() or not any(out.iterdir()), 'Output directory must be new or empty')
    out.mkdir(parents=True, exist_ok=True)
    provenance = {'runtimeJars': {engine: hashes(paths) for engine, paths in libraries.items()},
                  'nativeBinary': hashes([native]), 'moduleManifest': hashes(set(manifests.values())),
                  'modules': hashes(set(p for paths in modules_by_engine.values() for p in paths)),
                  'candidateSources': hashes(sources), 'java': hashes([java, release_path]), 'harness': hashes([Path(__file__).resolve()])}
    commands = []
    for fork in range(1, FORKS + 1):
        order = ENGINES[fork - 1:] + ENGINES[:fork - 1]
        for position, engine in enumerate(order, 1):
            if engine == 'native':
                command = [str(native), '--bench-steady', ENTRY, str(args.native_warm_seconds), str(SAMPLE_SECONDS), str(SAMPLES), str(args.input_base)]
            else:
                command = [str(java), '--enable-native-access=ALL-UNNAMED', '-Xss2m', '-Dthc.traceCompilation=true',
                           '-Dthc.diagnosticUnsupported=true', f'-Dthc.minimumWarmCalls={args.minimum_warm_calls}',
                           f'-Dthc.backend={getattr(args, engine + "_backend")}',
                           '-cp', os.pathsep.join(map(str, libraries[engine])), 'thc.ProbeKt', ','.join(map(str, modules_by_engine[engine])),
                           ENTRY, '--steady', str(args.jvm_warm_seconds), str(SAMPLE_SECONDS), str(SAMPLES), str(args.input_base)]
            if engine != 'native':
                command[1:1] = getattr(args, engine + '_jvm_option')
                notes = getattr(args, engine + '_source_notes')
                if notes is not None:
                    command.insert(1, '-Dthc.sourceNotesEnabled=' + ('true' if notes == 'on' else 'false'))
            commands.append({'fork': fork, 'position': position, 'engine': engine, 'argv': command})
    host = {'system': platform.system(), 'platform': platform.platform(), 'machine': platform.machine()}
    config = {'schema': 1, 'recordedAtUtc': datetime.now(timezone.utc).isoformat(), 'entry': ENTRY,
              'baselineCommit': args.baseline_commit, 'backends': {e: getattr(args, e + '_backend') for e in ENGINES[:2]}, 'inputBase': args.input_base, 'forks': FORKS, 'samples': SAMPLES,
              'sampleSeconds': SAMPLE_SECONDS, 'jvmWarmSeconds': args.jvm_warm_seconds, 'nativeWarmSeconds': args.native_warm_seconds,
              'minimumJvmWarmCalls': args.minimum_warm_calls, 'diagnosticUnsupported': True, 'instrumented': False,
              'moduleManifests': {engine: str(path) for engine, path in manifests.items()},
              'sourceNotesModes': {e: getattr(args, e + '_source_notes') for e in ENGINES[:2]},
              'protocol': 'serialized rotated engine order; median of per-fork window medians; signed64 checksums',
              'candidateSourceNote': 'Recorded source hashes identify the supplied tree; the caller must build these runtime JARs from that tree.',
              'host': host, 'commands': commands, 'provenance': provenance}
    write_json(out / 'run-config.json', config)
    power = {'supported': host['system'] == 'Darwin', 'criticalBatteryPercent': CRITICAL_BATTERY_PERCENT,
             'samples': [], 'warnings': []}

    def record_power(spec, phase):
        if not power['supported']:
            return
        sample = {key: spec[key] for key in ('engine', 'fork', 'position')}
        sample.update(phase=phase, **power_status())
        previous = next((sample for sample in reversed(power['samples']) if sample.get('source')), None)
        for message in power_warnings(sample, previous):
            warning = {'engine': spec['engine'], 'fork': spec['fork'], 'phase': phase,
                       'recordedAtUtc': sample['recordedAtUtc'], 'message': message}
            power['warnings'].append(warning)
            print(f"WARNING: {spec['engine']}-{spec['fork']} {phase}: {message}; timings retained", file=sys.stderr, flush=True)
        power['samples'].append(sample)
        write_json(out / 'power-status.json', power)

    write_json(out / 'power-status.json', power)
    rows, process_checks = [], []
    try:
        # Untimed scalar oracle queries establish a reference independently of
        # timing iteration counts and permit exact modular-overflow validation.
        oracle_rows, values = [], []
        with (out / 'oracle.log').open('w') as errors:
            for offset in range(16):
                result = subprocess.run([str(native), ENTRY, str(args.input_base + offset)], text=True,
                                        stdout=subprocess.PIPE, stderr=errors, check=True, timeout=args.process_timeout)
                fields = result.stdout.strip().split('\t')
                require(len(fields) == 3 and fields[:2] == [ENTRY, str(args.input_base + offset)], 'Malformed native scalar oracle output')
                value = int(fields[2])
                require(-(1 << 63) <= value < (1 << 63), 'Native scalar result outside signed64')
                values.append(value)
                oracle_rows.append(result.stdout.strip())
        (out / 'oracle.tsv').write_text('\n'.join(oracle_rows) + '\n')
        cycle_sum = sum(values)
        config['oracle'] = {'values': values, 'cycleChecksumSigned64': signed64(cycle_sum), 'rows': hashes([out / 'oracle.tsv'])}
        write_json(out / 'run-config.json', config)
        with (out / 'timings.tsv').open('w', newline='') as aggregate:
            writer = csv.DictWriter(aggregate, fieldnames=['engine', 'fork'] + FIELDS, delimiter='\t')
            writer.writeheader()
            aggregate.flush()
            for spec in commands:
                engine, fork = spec['engine'], spec['fork']
                name = f'{engine}-{fork}'
                raw, log = out / f'{name}.tsv', out / f'{name}.log'
                print(f'Fork {fork}/{FORKS}, position {spec["position"]}/3: {engine} starting', flush=True)
                record_power(spec, 'before')
                start = time.monotonic()
                try:
                    with raw.open('w') as output, log.open('w') as errors:
                        subprocess.run(spec['argv'], stdout=output, stderr=errors, check=True, timeout=args.process_timeout)
                finally:
                    process_seconds = time.monotonic() - start
                    record_power(spec, 'after')
                windows = read_windows(raw, args.input_base, cycle_sum)
                check = validate_jvm_log(log, cycle_sum, getattr(args, engine + '_backend'), getattr(args, engine + '_source_notes'),
                                         args.minimum_warm_calls, args.jvm_warm_seconds) if engine != 'native' else {'nativeProcessSucceeded': True}
                process_checks.append({'engine': engine, 'fork': fork, **check})
                for window in windows:
                    row = {'engine': engine, 'fork': fork, **window}
                    writer.writerow(row)
                    rows.append(row)
                aggregate.flush()
                median = statistics.median(r['elapsedNs'] / r['repetitions'] for r in windows)
                print(f'Fork {fork}/{FORKS}: {engine} validated; {median:,.1f} ns/call; process {process_seconds:.1f}s', flush=True)
        for records in [*provenance['runtimeJars'].values(), provenance['nativeBinary'], provenance['moduleManifest'],
                        provenance['modules'], provenance['candidateSources'], provenance['java'], provenance['harness']]:
            for record in records:
                require(sha256(Path(record['path'])) == record['sha256'], f'Input changed during comparison: {record["path"]}')
        require(sorted(p for p in source_dir.rglob('*') if p.is_file()) == sources, 'Candidate source inventory changed during comparison')
        engines = {engine: engine_summary([r for r in rows if r['engine'] == engine]) for engine in ENGINES}
        summary = {'entry': ENTRY, 'powerWarnings': power['warnings'], 'windowCount': len(rows), 'cycleChecksumSigned64': signed64(cycle_sum), 'engines': engines,
                   'ratios': {'candidate/baseline': engines['candidate']['medianNsPerCall'] / engines['baseline']['medianNsPerCall'],
                              'candidate/native': engines['candidate']['medianNsPerCall'] / engines['native']['medianNsPerCall']},
                   'limits': 'Exploratory workload comparison. No measured Truffle events does not establish absence of host JIT/GC or external machine contention.'}
        write_json(out / 'summary.json', summary)
        write_json(out / 'validation.json', {'passed': True, 'validatedWindows': len(rows), 'inputsUnchanged': True,
                                            'minimumWindowNs': min(r['elapsedNs'] for r in rows), 'processes': process_checks})
        print(f'VALIDATED: {len(rows)} windows; candidate/baseline={summary["ratios"]["candidate/baseline"]:.3f}; '
              f'candidate/native={summary["ratios"]["candidate/native"]:.3f}; results: {out}', flush=True)
    except Exception as error:
        write_json(out / 'validation.json', {'passed': False, 'error': str(error), 'validatedWindows': len(rows), 'processes': process_checks})
        raise


if __name__ == '__main__':
    try:
        main()
    except (InvalidRun, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'Comparison failed: {error}', file=sys.stderr)
        sys.exit(1)
