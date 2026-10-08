# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exercise the workflow's runner-routing and persistent-runner guards."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows/fast.yml"
REPO = "ekmett/thc"


def embedded_python(delimiter, workflow=WORKFLOW):
    text = workflow.read_text()
    match = re.search(r"python3 - <<'" + delimiter + r"'\n(.*?)\n          " + delimiter,
                      text, re.DOTALL)
    if match is None:
        raise AssertionError(f"Missing workflow guard: {delimiter}")
    return textwrap.dedent(match.group(1)) + "\n"


class FastWorkflowGuardsTest(unittest.TestCase):
    def test_qualification_is_one_manual_job_and_does_not_change_hourly_health(self):
        workflow = (WORKFLOW.parent / 'hourly-qualification.yml').read_text()
        self.assertIn('name: Hourly qualification\n', workflow)
        self.assertIn('  workflow_dispatch:\n', workflow)
        self.assertNotIn('  schedule:', workflow)
        self.assertNotIn('    uses: ./.github/workflows/', workflow)
        self.assertEqual(['bounded'], re.findall(r'^  ([\w-]+):\n    name:', workflow, re.M))
        self.assertIn('    timeout-minutes: 10\n', workflow)
        self.assertNotIn('    strategy:', workflow)
        self.assertIn("inputs.platform == 'macos-latest' && 'macos-latest' || 'ubuntu-latest'", workflow)
        self.assertIn('default: scalar-memory-utilities', workflow)
        self.assertIn('default: ubuntu-latest', workflow)
        self.assertIn('ref: ${{ github.sha }}', workflow)
        self.assertIn('test "$(git rev-parse HEAD)" = "$GITHUB_SHA"', workflow)
        self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"', workflow)
        self.assertLess(workflow.index('name: Validate bounded revision'), workflow.index('uses: ./.github/actions/setup'))
        self.assertIn("cache-cabal-project: 'true'", workflow)
        for command in ('start', 'compile-common', 'compile-test-support', 'group'):
            self.assertIn('fast_ci.py ' + command + ' ', workflow)
        self.assertNotIn('--matrix', workflow)
        run = workflow.split('name: Compile existing shared tools', 1)[1].split('\n      - ', 1)[0]
        self.assertNotIn('${{', run)
        self.assertIn('timeout-minutes: 7', run)
        script = textwrap.dedent(run.split('        run: |\n', 1)[1])
        for exact_class in ('', 'example.Test', 'example.Test; touch injected'):
            with self.subTest(exact_class=exact_class), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                launcher = root / 'python3'
                launcher.write_text('#!' + sys.executable + '\nimport json,sys\n'
                    'from pathlib import Path\n'
                    'with Path("argv.jsonl").open("a") as out: out.write(json.dumps(sys.argv[1:]) + "\\n")\n')
                launcher.chmod(0o755)
                result = subprocess.run(['bash', '-e', '-c', script], cwd=root,
                    env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                             CI_GROUP='selected', CI_EXACT_CLASS=exact_class), capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                commands = [json.loads(line) for line in (root / 'argv.jsonl').read_text().splitlines()]
                self.assertEqual(['start', 'compile-common', 'compile-test-support', 'group'],
                                 [command[1] for command in commands])
                self.assertEqual(['.github/scripts/fast_ci.py', 'group']
                    + (['--exact-class', exact_class] if exact_class else [])
                    + ['--group', 'selected', '--cadence', 'hourly', '--reuse-daemon',
                       '--report-dir', 'build/ci/group-results/bounded'], commands[-1])
                self.assertFalse((root / 'injected').exists())

        for name in ('Stop the qualification', 'Preserve bounded selection'):
            self.assertIn('if: always()', workflow.split('name: ' + name, 1)[1].split('\n      - ', 1)[0])
        for directory in ('setup-results', 'common-results', 'group-results'):
            self.assertIn('build/ci/' + directory + '/', workflow)
        health = (WORKFLOW.parent.parent / 'scripts/hourly_health.py').read_text()
        self.assertIn('item["path"] == ".github/workflows/hourly.yml"', health)
        self.assertNotIn('hourly-qualification.yml', health)

    def test_qualification_validates_exact_nonempty_group_on_selected_platform(self):
        import fast_select
        script = embedded_python('BOUNDED', WORKFLOW.parent / 'hourly-qualification.yml')
        for system, requested, group, exact_class, valid in (
                ('Linux', 'ubuntu-latest', 'selected', '', True),
                ('Darwin', 'macos-latest', 'selected', '', True),
                ('Linux', 'macos-latest', 'selected', '', False),
                ('Linux', 'self-hosted', 'selected', '', False),
                ('Linux', '', 'selected', '', False),
                ('Linux', 'ubuntu-latest', '', '', False),
                ('Linux', 'ubuntu-latest', 'empty', '', False),
                ('Linux', 'ubuntu-latest', 'nightly-only', '', False),
                ('Linux', 'ubuntu-latest', 'selected; touch injected', '', False),
                ('Linux', 'ubuntu-latest', 'selected', 'example.Test', True),
                ('Darwin', 'macos-latest', 'selected', 'example.Test', True),
                ('Linux', 'ubuntu-latest', 'selected', 'example.MissingTest', False)):
            with self.subTest(system=system, requested=requested, group=group, exact_class=exact_class), \
                 tempfile.TemporaryDirectory() as directory, \
                 patch('platform.system', return_value=system), \
                 patch.dict(os.environ, CI_PLATFORM=requested, CI_GROUP=group, CI_EXACT_CLASS=exact_class), \
                 patch.object(fast_select, 'groups', return_value={'selected': ['example.Test'], 'empty': []}) as groups, \
                 patch.object(fast_select, 'group_selection', return_value={'junit': {'classes': ['example.Test']}}) as select:
                if exact_class == 'example.MissingTest':
                    select.side_effect = fast_select.SelectionError('Class not admitted')
                previous = Path.cwd()
                try:
                    os.chdir(directory)
                    if valid:
                        exec(script, {})
                        groups.assert_called_once_with(Path.cwd(), system=system, cadence='hourly')
                        select.assert_called_once_with(Path.cwd(), group, cadence='hourly', exact_class=exact_class or None)
                        self.assertTrue(Path('build/ci/bounded-selection.json').is_file())
                    else:
                        with self.assertRaises(fast_select.SelectionError if exact_class else SystemExit):
                            exec(script, {})
                        if exact_class:
                            select.assert_called_once_with(Path.cwd(), group, cadence='hourly', exact_class=exact_class)
                        else:
                            select.assert_not_called()
                        self.assertFalse(Path('build').exists())
                    self.assertFalse(Path('injected').exists())
                finally:
                    os.chdir(previous)

    def test_persistent_index_refreshes_only_for_missing_or_changed_snapshot(self):
        script = embedded_python("HACKAGE")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / "packages" / "hackage.haskell.org"
            cache.mkdir(parents=True)
            (root / "cabal.project").write_text("index-state: 2026-10-02T22:18:55Z\n")
            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch("subprocess.check_output", return_value=str(cache.parent)), \
                     patch("subprocess.run") as update:
                    exec(script, {})
                    update.assert_called_once_with(["cabal", "update", "hackage.haskell.org,2026-10-02T22:18:55Z"], check=True)
                    (cache / "01-index.tar").write_bytes(b"index")
                    from datetime import datetime
                    epoch = int(datetime.fromisoformat("2026-10-02T22:18:55+00:00").timestamp())
                    for timestamp in ("2026-10-02T22:18:55Z", "@" + str(epoch)):
                        (cache / "01-index.timestamp").write_text(timestamp)
                        update.reset_mock()
                        exec(script, {})
                        update.assert_not_called()
                    for timestamp in ("HEAD", "2026-09-24T12:38:18Z"):
                        (cache / "01-index.timestamp").write_text(timestamp)
                        update.reset_mock()
                        exec(script, {})
                        update.assert_called_once()
                    (cache / "01-index.timestamp").write_text("2026-10-02T22:18:55Z")
                    (root / "cabal.project").write_text("index-state: 2026-10-03T00:00:00Z\n")
                    update.side_effect = subprocess.CalledProcessError(1, "cabal update")
                    with self.assertRaises(subprocess.CalledProcessError):
                        exec(script, {})
            finally:
                os.chdir(previous)

    def test_commit_plan_contains_no_scheduled_jobs_or_skipped_matrix(self):
        pending = ["build.yml"]
        visited = set()
        while pending:
            filename = pending.pop()
            if filename in visited:
                continue
            visited.add(filename)
            workflow = (WORKFLOW.parent / filename).read_text()
            self.assertNotRegex(workflow, r"(?m)^    if: inputs\.cadence", filename)
            self.assertNotIn("    strategy:", workflow, filename)
            pending.extend(re.findall(r"uses: \./\.github/workflows/([^\s]+)", workflow))
        self.assertNotIn("test-groups.yml", visited)
        self.assertNotIn("windows.yml", visited)

    def test_cadence_wrappers_select_one_immutable_revision_without_commit_batching(self):
        for filename, name, cadence, trigger in (
            ("build.yml", "Build", "commit", "  push:\n    branches: [main]"),
            ("hourly.yml", "Hourly", "hourly", "    - cron: '31 * * * *'"),
            ("intensive.yml", "Intensive", "nightly", "    - cron: '17 7 * * *'"),
        ):
            with self.subTest(cadence=cadence):
                workflow = (WORKFLOW.parent / filename).read_text()
                self.assertIn("name: " + name + "\n", workflow)
                self.assertIn(trigger, workflow)
                self.assertIn("uses: ./.github/workflows/checks.yml", workflow)
                self.assertIn("cadence: " + cadence, workflow)
                self.assertIn("expected_sha: ${{ inputs.expected_sha || github.sha }}", workflow)
                if cadence != "hourly":
                    self.assertNotIn("concurrency:", workflow)
                if cadence != "nightly":
                    self.assertNotIn("runs-on:", workflow)
        for filename in ("checks.yml", "test-common.yml", "test-groups.yml"):
            workflow = (WORKFLOW.parent / filename).read_text()
            self.assertIn("  workflow_call:", workflow)
            self.assertIn("      cadence:\n        type: string\n        required: true", workflow)
            self.assertEqual(workflow.count("uses: actions/checkout@"),
                             workflow.count("ref: ${{ github.sha }}"))
            if filename == "test-groups.yml":
                self.assertIn("expected_sha: ${{ inputs.expected_sha }}", workflow)
                self.assertIn("fast_ci.py restore-common", workflow)
            else:
                self.assertIn('test "$(git rev-parse HEAD)" = "$GITHUB_SHA"', workflow)
                self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"', workflow)
            self.assertNotIn("concurrency:", workflow)

    def test_cadence_validation_and_hourly_health_precede_all_builds(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        block = workflow.split("name: Validate cadence\n", 1)[1].split("\n      - ", 1)[0]
        script = textwrap.dedent(block.split("        run: |\n", 1)[1])
        for cadence in ("commit", "hourly", "nightly", "", "daily", "commit hourly"):
            with self.subTest(cadence=cadence):
                result = subprocess.run(["bash", "-e", "-c", script],
                                        env=dict(os.environ, CI_CADENCE=cadence), capture_output=True)
                self.assertEqual(cadence in ("commit", "hourly", "nightly"), result.returncode == 0)
        gate = workflow.split("name: Stop commit checks when Hourly is failing\n", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: inputs.cadence == 'commit'", gate)
        self.assertIn("GH_TOKEN: ${{ github.token }}", gate)
        self.assertIn("run: python3 .github/scripts/hourly_health.py", gate)
        self.assertIn("  actions: read", workflow)
        self.assertNotIn("continue-on-error", gate)
        entry = (WORKFLOW.parent / "build.yml").read_text()
        self.assertNotIn("needs: automation", entry)
        common = (WORKFLOW.parent / "test-common.yml").read_text()
        gate = common.split("name: Stop commit builds when Hourly is failing\n", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: inputs.cadence == 'commit'", gate)
        self.assertIn("run: python3 .github/scripts/hourly_health.py", gate)
        self.assertNotIn("continue-on-error", gate)
        self.assertLess(common.index("name: Stop commit builds when Hourly is failing"),
                        common.index("uses: ./.github/actions/setup"))
        for filename, jobs in (
            ("hourly.yml", ("jvm-linux", "jvm-macos", "windows")),
            ("intensive.yml", ("jvm-linux", "jvm-macos", "build")),
        ):
            entry = (WORKFLOW.parent / filename).read_text()
            for job in jobs:
                self.assertIn("  " + job + ":\n    needs: automation\n", entry)
        intensive = (WORKFLOW.parent / "intensive.yml").read_text()
        self.assertIn("    needs: build\n", intensive.split("  library:\n", 1)[1])

    def test_intensive_work_has_explicit_cadence_and_quarantined_work_is_absent(self):
        workflow = (WORKFLOW.parent / "intensive.yml").read_text()
        for name in (
                "Check Haskell calls into JavaScript",
                "Compare public packages with native GHC on both backends and handoff modes",
                "Check cold project dependency acquisition",
                "Prepare fresh library source and native oracles",
                "Package verified library inputs and installed runtime",
                "Share this platform's library inputs with this workflow attempt",
                "Prepare fresh Map source and native oracle",
                "Check diagnostic Map on both backends and handoff modes"):
            with self.subTest(name=name):
                self.assertIn("      - name: " + name + "\n", workflow)
                for filename in ("build.yml", "hourly.yml", "checks.yml", "test-common.yml", "test-groups.yml"):
                    self.assertNotIn("name: " + name, (WORKFLOW.parent / filename).read_text())
        for producer in ("aggregate-heap", "fourway-aggregate", "generic-sum-transport",
                         "narrow-integer-transport"):
            self.assertNotIn("--offline -- " + producer, workflow)
        self.assertNotIn("  foreign-exceptions:", workflow)
        self.assertNotIn("inputs.cadence", workflow)
        common = (WORKFLOW.parent / "test-common.yml").read_text()
        commands = [line.strip() for line in (workflow + common).splitlines() if "cabal test driver-tests" in line]
        self.assertEqual(2, len(commands))
        for option in ("--public-packages-only", "--acquire-project-only"):
            self.assertEqual(1, sum("--test-options=" + option in command for command in commands))
        self.assertIn("name: Build JVM distribution from source\n", workflow)
        grouped = (WORKFLOW.parent / "test-groups.yml").read_text()
        self.assertIn('fast_select.py --matrix --cadence "$CI_CADENCE"', common)
        self.assertIn('fast_ci.py group --group "$group" --cadence "$CI_CADENCE"', grouped)

    def test_commit_checks_reuse_common_compilation_and_scheduled_workers_stay_separate(self):
        workflow = (WORKFLOW.parent / "build.yml").read_text()
        self.assertEqual(2, workflow.count("uses: ./.github/workflows/test-common.yml"))
        grouped = (WORKFLOW.parent / "test-groups.yml").read_text()
        self.assertIn("uses: ./.github/workflows/test-common.yml", grouped)
        self.assertIn("matrix: ${{ fromJSON(needs.compile.outputs.matrix) }}", grouped)
        self.assertNotIn("    if: inputs.cadence", grouped)
        common = (WORKFLOW.parent / "test-common.yml").read_text()
        self.assertIn("value: ${{ jobs.compile.outputs.matrix }}", common)
        self.assertIn("matrix: ${{ steps.inventory.outputs.matrix }}", common)
        block = common.split("name: Build and check declared dependencies with Ninja\n", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: inputs.cadence == 'commit'", block)
        self.assertIn("fast_ci.py commit-checks", block)
        self.assertNotIn("cabal test ", common)
        self.assertNotIn("continue-on-error", common)
        self.assertIn("build/ci/check-results/", common)
        self.assertIn("build/ci/graph/.ninja_log", common)
        for name in ("Compile and package the application", "Compile shared test classes and fixture tools without running tests"):
            block = common.split("name: " + name + "\n", 1)[1].split("\n      - ", 1)[0]
            self.assertIn("if: inputs.cadence != 'commit'", block)

    def test_build_runs_driver_units_without_selecting_package_integration(self):
        graph = (WORKFLOW.parents[2] / "cmake/Checks.cmake").read_text()
        self.assertIn('if(suite STREQUAL "driver-tests")', graph)
        self.assertIn('set(options --unit-only)', graph)
        self.assertNotIn('--public-packages-only', graph)
        self.assertNotIn('cabal test', graph)

    def test_library_and_map_launchers_resolve_the_required_vector_module(self):
        workflow = (WORKFLOW.parent / "intensive.yml").read_text()
        for label, entry in (
            ("Run the complete strict and compiled library checks", "thc.LibraryCheck build/libraries/cases.json"),
            ("Check diagnostic Map on both backends and handoff modes", "thc.MapCheck build/map/modules.txt build/map/oracle.tsv"),
        ):
            with self.subTest(label=label):
                block = workflow.split("name: " + label, 1)[1].split("\n      - ", 1)[0]
                self.assertIn("--add-modules=jdk.incubator.vector", block)
                self.assertIn(entry, block)
                self.assertNotIn("continue-on-error", block)

    def test_full_build_reports_independent_groups_and_never_prepares_every_fixture(self):
        grouped = (WORKFLOW.parent / "test-groups.yml").read_text()
        for filename in ("hourly.yml", "intensive.yml"):
            workflow = (WORKFLOW.parent / filename).read_text()
            self.assertEqual(2, workflow.count("uses: ./.github/workflows/test-groups.yml"))
            self.assertNotIn("bin/try.sh --handoff-modes", workflow)
            self.assertNotIn("bin/prepare-tests.sh", workflow)
        self.assertIn("fail-fast: false", grouped)
        self.assertIn('fast_ci.py group --group "$group"', grouped)
        self.assertIn("fast_ci.py compile-common", (WORKFLOW.parent / "test-common.yml").read_text())
        self.assertIn("digest-mismatch: error", grouped)
        self.assertIn("uses: ./.github/actions/setup", grouped)
        self.assertNotIn("continue-on-error", grouped)
        self.assertNotIn("needs: build", grouped)

    def test_tool_and_index_caches_survive_project_changes(self):
        setup = (WORKFLOW.parents[1] / "actions/setup/action.yml").read_text()
        plan = setup.split('        THC_CACHE_LAYERS: |\n', 1)[1].split('      with:\n', 1)[0]
        # Replace expression booleans so the declared cache plan is valid JSON.
        plan = re.sub(r'("enabled": )\$\{\{.*?\}\}', r'\1true', plan)
        layers = json.loads(textwrap.dedent(plan))
        immutable = json.dumps({name: layers[name] for name in ('ghc', 'cabal', 'graalvm', 'llvm', 'index')})
        self.assertNotIn("hashFiles", immutable)
        self.assertNotIn("github.sha", immutable)
        self.assertIn("steps.identity.outputs.index", immutable)
        self.assertEqual("${{ steps.identity.outputs.jam-key }}", layers["graalvm"]["key"])
        self.assertEqual("${{ steps.identity.outputs.jam-root }}", layers["graalvm"]["path"])
        self.assertIn('THC_TOOLS="$HOME/.cache/thc-toolchains" python3 .github/scripts/fast_ci.py jam-identity', setup)
        self.assertIn("if: steps.setup.outputs.index-cache-hit != 'true'", setup)
        self.assertEqual(1, setup.count("run: cabal update"))
        self.assertIn('run: cabal update "hackage.haskell.org,$INDEX_STATE"', setup)
        self.assertIn("cabal-store-${{ runner.os }}-${{ runner.arch }}-ghc9.14.1-cabal3.16.0.0-", layers['store']['restore-keys'].splitlines())
        self.assertIn("gradle-${{ runner.os }}-${{ runner.arch }}-java25.3.4.1-", layers['gradle']['restore-keys'].splitlines())
        self.assertEqual(4, setup.count('lookup-only: true'))
        self.assertIn('process.env.THC_NODE = process.execPath', setup)
        self.assertIn("'_actions/actions/cache/v4'", setup)
        self.assertIn('uses: actions/cache@v4', setup)
        common = (WORKFLOW.parent / 'test-common.yml').read_text()
        self.assertIn("cache-cabal-project: 'true'", common)
        self.assertNotIn('uses: actions/cache@', common)
        for filename in ("test-common.yml", "test-groups.yml", "intensive.yml"):
            workflow = (WORKFLOW.parent / filename).read_text()
            self.assertIn("uses: ./.github/actions/setup", workflow)
            self.assertIn("submodules: false", workflow)
            self.assertNotIn("cabal-update: true", workflow)

    def test_scheduled_windows_checks_run_only_admitted_runtime_hourly(self):
        workflow = (WORKFLOW.parent / "hourly.yml").read_text()
        caller = workflow.split("  windows:\n", 1)[1]
        self.assertIn("needs: automation", caller)
        self.assertNotIn("    if:", caller)
        for filename in ("build.yml", "intensive.yml", "checks.yml"):
            self.assertNotIn("workflows/windows.yml", (WORKFLOW.parent / filename).read_text())
        self.assertIn("uses: ./.github/workflows/windows.yml", caller)
        self.assertIn("testGroup: Runtime", caller)
        windows = (WORKFLOW.parent / "windows.yml").read_text()
        self.assertIn("  workflow_call:", windows)
        self.assertIn("      testGroup:\n", windows)
        self.assertIn("        required: true", windows)
        self.assertIn("  workflow_dispatch:", windows)
        self.assertIn("options: [Runtime]", windows)
        self.assertIn("if ($env:THC_WINDOWS_TEST_GROUP -ne 'Runtime')", windows)
        self.assertNotIn("  push:", windows)
        self.assertNotIn("  pull_request:", windows)
        self.assertNotIn("pull_request_target:", windows)
        self.assertIn("ref: ${{ github.sha }}", windows)
        self.assertIn("runs-on: windows-2025", windows)
        self.assertIn("  contents: read", windows)
        self.assertIn("persist-credentials: false", windows)
        self.assertIn("timeout-minutes: 45", windows)
        self.assertIn("bin/windows.ps1 -Action Test -Jobs 4", windows)
        self.assertIn("Tee-Object build/windows-ci.log", windows)
        self.assertIn("if: always()", windows)
        self.assertIn("build/test-results/testDefault/", windows)
        self.assertIn("build/test-results/testDense/", windows)
        self.assertNotIn("continue-on-error:", windows)

    def test_windows_runtime_smoke_does_not_require_driver_provenance(self):
        project = WORKFLOW.parents[2]
        smoke = (project / "build.gradle").read_text().split('// Native Windows checkpoint:', 1)[1]
        self.assertNotIn('build/windows-driver/provenance.json', smoke)
        self.assertIn('excludeTestsMatching("thc.WindowsDistributionTest.$it")', smoke)
        script = (project / 'bin/windows.ps1').read_text()
        self.assertIn("[ValidateSet('All', 'Driver', 'Runtime')][string]$TestGroup = 'All'", script)
        self.assertIn("if ($testRuntime)", script)
        self.assertIn("foreach ($mode in @('testDefault', 'testDense'))", script)
        focused = script.split('$focusedTests = @()', 1)[1]
        runtime = focused.split('if ($testRuntime) {', 1)[1].split('\n    }', 1)[0]
        self.assertIn("Invoke-ThcTool $fixture @('bit')", runtime)
        self.assertIn("'thc.runtime.BitPrimopsTest'", runtime)
        codepages = (project / 't/haskell-fixtures/WindowsCodePageFixtures.hs').read_text()
        self.assertIn('lookupEnv "GHC_PKG"', codepages)

    def test_normal_jvm_jobs_acquire_and_verify_the_exact_jam_package(self):
        project = WORKFLOW.parents[2]
        for filename in ("fast.yml", "intensive.yml", "windows.yml"):
            text = (WORKFLOW.parent / filename).read_text()
            self.assertIn("uses: ./.github/actions/jam", text)
            self.assertNotIn("graalvm/setup-graalvm", text)
        common = (WORKFLOW.parent / "test-common.yml").read_text()
        self.assertNotIn("release['GRAALVM_VERSION']", common)
        action = (project / ".github/actions/jam/action.yml").read_text()
        self.assertIn('fast_ci.py verify-jam --report-dir "$THC_JAM_REPORT"', action)
        self.assertNotIn("restore-keys:", action)
        self.assertLess(action.index("fast_ci.py verify-jam"), action.index("actions/cache/save@"))

    def test_title_skip_preserves_normal_pr_gate(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("!contains(github.event.pull_request.title, '[ci skip]')", workflow)

    def test_main_fast_run_finishes_while_pr_updates_still_cancel(self):
        concurrency = WORKFLOW.read_text().split("\nconcurrency:\n", 1)[1].split("\njobs:", 1)[0]
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' || github.ref != 'refs/heads/main' }}", concurrency)
        # A merged PR's close event can use main's ref: it must still cancel only
        # its PR group, including when the event lacks a usable PR number.
        self.assertIn("group: fast-${{ github.event_name == 'pull_request' && format('pr-{0}', github.event.number || github.run_id) || format('ref-{0}', github.ref) }}", concurrency)
        # Keep GitHub's default single pending slot: newest pending replaces old.
        self.assertNotIn("queue:", concurrency)

    def check_case(self, kind, ref, event, trusted):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            event_path = directory / "event.json"
            output_path = directory / "outputs"
            event_path.write_text(json.dumps(event))
            env = dict(os.environ, GITHUB_EVENT_PATH=str(event_path),
                       GITHUB_OUTPUT=str(output_path), GITHUB_REPOSITORY=REPO,
                       GITHUB_EVENT_NAME=kind, GITHUB_REF=ref)
            route = subprocess.run([sys.executable, "-c", embedded_python("PY")],
                                   env=env, text=True, capture_output=True)
            self.assertEqual(route.returncode, 0, route.stderr)
            outputs = dict(line.split("=", 1) for line in output_path.read_text().splitlines())
            self.assertEqual(outputs["trusted"], str(trusted).lower())
            self.assertNotIn("runner", outputs)  # Checkout diff chooses this later.
            guard = subprocess.run([sys.executable, "-c", embedded_python("PYCODE")],
                                   env=env, text=True, capture_output=True)
            self.assertEqual(guard.returncode == 0, trusted, guard.stderr)

    def test_hosted_ci_only_still_runs_the_full_fast_contract(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("runner: ${{ steps.route.outputs.runner }}", workflow)
        self.assertIn("persistent: ${{ steps.route.outputs.persistent }}", workflow)
        self.assertIn("python3 .github/scripts/fast_runner.py", workflow)
        self.assertIn('if [ "$TRUSTED_EVENT" != true ]; then', workflow)
        self.assertEqual(workflow.count("if: needs.automation.outputs.persistent != 'true'"), 2)
        self.assertIn("Run fresh smoke plus affected tests", workflow)
        self.assertIn("needs: automation", workflow)

    def test_same_repository_pr_uses_persistent_runner(self):
        self.check_case("pull_request", "refs/pull/42/merge", {
            "repository": {"full_name": REPO},
            "pull_request": {
                "head": {"repo": {"full_name": REPO, "fork": False}},
                "base": {"repo": {"full_name": REPO}, "ref": "main"},
            }}, True)

    def test_fork_or_unknown_pr_stays_hosted(self):
        for head in ({"full_name": "someone/thc", "fork": True},
                     {"full_name": REPO}, None):
            with self.subTest(head=head):
                self.check_case("pull_request", "refs/pull/42/merge", {
                    "repository": {"full_name": REPO},
                    "pull_request": {
                        "head": {"repo": head},
                        "base": {"repo": {"full_name": REPO}, "ref": "main"},
                    }}, False)

    def test_main_push_and_feature_branch_dispatch_are_trusted(self):
        self.check_case("push", "refs/heads/main", {"repository": {"full_name": REPO}}, True)
        self.check_case("workflow_dispatch", "refs/heads/feature", {
            "repository": {"full_name": REPO}}, True)

    def test_foreign_or_unknown_push_stays_hosted(self):
        self.check_case("push", "refs/heads/main", {
            "repository": {"full_name": "someone/thc"}}, False)
        self.check_case("push", "refs/heads/feature", {
            "repository": {"full_name": REPO}}, False)
        self.check_case("repository_dispatch", "refs/heads/main", {
            "repository": {"full_name": REPO}}, False)


if __name__ == "__main__":
    unittest.main()
