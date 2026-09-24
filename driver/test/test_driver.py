#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Functional checks against real Cabal configuration and a native fixture build."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
DRIVER_ROOT = HERE.parent
FIXTURE = HERE / "fixtures" / "tiny"
REPO_ROOT = DRIVER_ROOT.parent
GHC_VERSION = "9.14.1"
CABAL_VERSION = "3.16.0.0"


class CommandLog:
    """Keep command output and exit/timeout details after scratch fixtures vanish."""

    def __init__(self, directory, environment):
        self.path = directory / "commands.jsonl"
        self.environment = environment

    def run(self, command, *, cwd, timeout=60):
        command = list(map(str, command))
        record = {"command": command, "cwd": str(cwd), "timeoutSeconds": timeout}
        try:
            result = subprocess.run(command, cwd=cwd, env=self.environment,
                                    text=True, capture_output=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as error:
            record["error"] = str(error)
            for name in ("stdout", "stderr"):
                output = getattr(error, name, None)
                record[name] = output.decode("utf-8", errors="replace") if isinstance(output, bytes) else output
            self.save(record)
            raise RuntimeError(f"{error}\nCommand evidence: {self.path}") from error
        record.update(returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        self.save(record)
        return result

    def save(self, record):
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    def checked(self, command, *, cwd, timeout=60):
        result = self.run(command, cwd=cwd, timeout=timeout)
        if result.returncode:
            raise RuntimeError(
                f"Command exited {result.returncode}: {result.args!r}\n"
                f"{result.stdout}{result.stderr}\nCommand evidence: {self.path}"
            )
        return result.stdout


class Tee:
    def __init__(self, *streams):
        self.streams = streams

    def write(self, text):
        for stream in self.streams:
            stream.write(text)
        return len(text)

    def flush(self):
        for stream in self.streams:
            stream.flush()


def resolve_tool(variable, default):
    requested = os.environ.get(variable, default)
    found = shutil.which(requested)
    if not found:
        raise RuntimeError(f"{variable} executable not found: {requested!r}; GHC {GHC_VERSION} is required")
    return str(Path(found).resolve())


def bootstrap_driver(directory, commands):
    """Use only the pinned installed toolchain; never acquire a nested CI lease."""
    ghc = resolve_tool("GHC", "ghc")
    suffix = ".exe" if os.name == "nt" else ""
    sibling_pkg = Path(ghc).parent / ("ghc-pkg" + suffix)
    ghc_pkg = resolve_tool("GHC_PKG", str(sibling_pkg) if sibling_pkg.is_file() else "ghc-pkg")
    version = commands.checked([ghc, "--numeric-version"], cwd=DRIVER_ROOT).strip()
    pkg_version = commands.checked([ghc_pkg, "--version"], cwd=DRIVER_ROOT).strip()
    if version != GHC_VERSION or pkg_version != f"GHC package manager version {GHC_VERSION}":
        raise RuntimeError(f"Expected GHC and ghc-pkg {GHC_VERSION}; found {version!r} / {pkg_version!r}")
    for package in ("Cabal", "Cabal-syntax"):
        version = commands.checked(
            [ghc_pkg, "--global", "--no-user-package-db", "field", package, "version", "--simple-output"],
            cwd=DRIVER_ROOT,
        ).split()
        if version != [CABAL_VERSION]:
            raise RuntimeError(f"Expected global {package}-{CABAL_VERSION}; found {version!r}")
    setup_directory = directory / "setup"
    setup_directory.mkdir()
    setup = setup_directory / ("setup" + suffix)
    commands.checked(
        [ghc, "--make", "-clear-package-db", "-global-package-db", "-package-env", "-",
         "-package", f"Cabal-{CABAL_VERSION}", "-package", f"Cabal-syntax-{CABAL_VERSION}",
         "-outputdir", setup_directory, "-o", setup, DRIVER_ROOT / "Setup.hs"],
        cwd=DRIVER_ROOT, timeout=180,
    )
    dist = directory / "dist"
    commands.checked(
        [setup, "configure", f"--builddir={dist}", f"--with-compiler={ghc}",
         f"--with-hc-pkg={ghc_pkg}", "--package-db=clear", "--package-db=global"],
        cwd=DRIVER_ROOT, timeout=180,
    )
    commands.checked([setup, "build", f"--builddir={dist}"], cwd=DRIVER_ROOT, timeout=180)
    driver = dist / "build" / "thc" / ("thc" + suffix)
    if not driver.is_file():
        raise RuntimeError(f"Cabal build did not produce {driver}")
    return driver, ghc, ghc_pkg, [str(setup)]


def source_hashes(directory):
    return {
        str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in directory.rglob("*") if p.is_file()
    }


class DriverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="thc-cabal-", dir=self.scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Both discovery and JSON serialization must preserve ordinary paths.
        self.package = self.root / 'tiny project "café"'
        shutil.copytree(FIXTURE, self.package)
        self.cabal_file = self.package / "tiny-fixture.cabal"
        self.dist = self.root / "configuration"

    def invoke(self, *args, ok=True):
        extra = self.compiler_options if args and args[0] in ("plan-package", "run") else []
        result = self.commands.run([self.driver, *args, *extra], cwd=self.root)
        if ok:
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        else:
            self.assertNotEqual(result.returncode, 0, result.stderr + result.stdout)
            self.assertEqual(result.stdout, "", "failure must not emit a success plan")
        return result

    def plan(self, *args, target=None):
        result = self.invoke(
            "plan-package", target or self.package,
            "--dist-dir", self.dist, *args,
        )
        return json.loads(result.stdout)

    def reject(self, *args, contains):
        result = self.invoke(*args, ok=False)
        self.assertIn(contains, result.stderr)

    def test_real_configuration_and_native_artifacts(self):
        before = source_hashes(self.package)
        plan = self.plan("--enable-tests", "--enable-benchmarks")
        self.assertEqual(plan["schema"], "thc.cabal-package-plan.v1")
        self.assertEqual(plan["stage"], "cabal-installed-package-configuration")
        self.assertEqual(plan["compiler"], "ghc-" + GHC_VERSION)
        self.assertEqual(plan["cabalVersion"], CABAL_VERSION)
        self.assertFalse(plan["solvedProjectPlan"])
        self.assertFalse(plan["artifactsBuilt"])
        self.assertEqual(plan["package"], "tiny-fixture-0.1.0.0")
        self.assertEqual(plan["packageRoot"], str(self.package.resolve()))
        self.assertEqual(plan["cabalFile"], str(self.cabal_file.resolve()))
        self.assertEqual(plan["packageDatabases"], ["global"])
        self.assertEqual(plan["flags"], {"loud": False, "unavailable": False})
        self.assertTrue(Path(plan["setupConfig"]).is_file())
        components = {c["name"]: c for c in plan["components"]}
        self.assertEqual(set(components), {
            "lib", "lib:words", "exe:hello", "test:greeting-test", "bench:greeting-bench",
        })
        lib = components["lib"]
        words = components["lib:words"]
        exe = components["exe:hello"]
        self.assertEqual(lib["exposedModules"], ["Greeting"])
        self.assertIn("quiet", lib["sourceDirectories"])
        self.assertNotIn("loud", lib["sourceDirectories"])
        platform_module = "Platform.Linux" if sys.platform.startswith("linux") else "Platform.Other"
        self.assertEqual(set(lib["otherModules"]), {"Message", platform_module})
        self.assertIn("-DRECENT_GHC", lib["cppOptions"])
        self.assertEqual(lib["internalDependencies"], [words["unitId"]])
        self.assertEqual(exe["internalDependencies"], [lib["unitId"]])
        self.assertEqual(exe["mainSource"], "Main.hs")
        # Ask GHC for the installed base unit independently of the driver.
        base_id = self.commands.checked(
            [self.ghc_pkg, "--global", "--no-user-package-db", "field", "base", "id", "--simple-output"],
            cwd=self.root,
        ).strip()
        self.assertIn(base_id, [d["unitId"] for d in exe["dependencies"]])
        self.assertEqual(len({c["unitId"] for c in components.values()}), 5)
        for component in components.values():
            self.assertFalse(Path(component["plannedArtifact"]).exists())
        # Cabal consumes the exact LocalBuildInfo persisted by plan-package.
        # This is a test oracle, not a THC build command implementation.
        build = self.commands.run(
            [*self.setup_command, "build", f"--builddir={self.dist}"],
            cwd=self.package, timeout=180,
        )
        self.assertEqual(build.returncode, 0, build.stderr + build.stdout)
        for component in components.values():
            self.assertTrue(Path(component["plannedArtifact"]).is_file(), component)
            self.assertTrue(Path(component["objectDirectory"]).is_dir(), component)
            self.assertTrue(Path(component["autogenDirectory"]).is_dir(), component)
            modules = component["exposedModules"] + component["otherModules"]
            if component["mainSource"]:
                modules.append("Main")
            for module in modules:
                object_path = Path(component["objectDirectory"]) / (module.replace(".", "/") + ".o")
                self.assertTrue(object_path.is_file(), object_path)
        self.assertEqual(self.commands.checked([exe["plannedArtifact"]], cwd=self.package), "hello Cabal\n")
        self.commands.checked([components["test:greeting-test"]["plannedArtifact"]], cwd=self.package)
        self.assertEqual(source_hashes(self.package), before)

    def test_default_components_and_explicit_flag(self):
        plan = self.plan("--flag", "loud")
        self.assertTrue(plan["flags"]["loud"])
        components = {c["name"]: c for c in plan["components"]}
        self.assertEqual(set(components), {"lib", "lib:words", "exe:hello"})
        self.assertIn("loud", components["lib"]["sourceDirectories"])
        self.assertNotIn("quiet", components["lib"]["sourceDirectories"])
        disabled = self.plan("--flag", "loud", "--flag=-loud")
        self.assertFalse(disabled["flags"]["loud"])

    def test_unknown_flag_and_missing_dependency(self):
        self.reject("plan-package", self.package, "--flag", "typo", contains="unknown package flag")
        self.reject("plan-package", self.package, "--dist-dir", self.dist,
                    "--flag", "unavailable", contains="thc-deliberately-unavailable")

    def test_project_boundary_and_explicit_package_selection(self):
        (self.root / "cabal.project").write_text('packages: "tiny project*"\n', encoding="utf-8")
        self.reject("plan-package", self.package, contains="cabal.project configuration is not supported")
        # File mode has explicitly documented package-only semantics.
        self.assertEqual(self.plan(target=self.cabal_file)["package"], "tiny-fixture-0.1.0.0")
        self.reject("plan-package", self.root / "cabal.project", contains="expected a package directory")

    def test_local_and_freeze_project_files(self):
        for name in ("cabal.project.local", "cabal.project.freeze"):
            with self.subTest(name=name):
                project_file = self.package / name
                project_file.write_text("constraints: base == 0\n", encoding="utf-8")
                self.reject("plan-package", self.package, contains="cabal.project configuration is not supported")
                project_file.unlink()

    def test_discovery_uses_real_files_and_canonical_project_root(self):
        (self.package / "not-a-file.cabal").mkdir()
        self.assertEqual(self.plan()["package"], "tiny-fixture-0.1.0.0")
        project_root = self.root / "project"
        project_root.mkdir()
        relocated = project_root / self.package.name
        shutil.move(self.package, relocated)
        (project_root / "cabal.project").write_text("packages: *\n", encoding="utf-8")
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        alias = elsewhere / "alias"
        alias.symlink_to(relocated, target_is_directory=True)
        self.reject("plan-package", alias, contains="cabal.project configuration is not supported")

    def test_ambiguous_missing_and_invalid_inputs(self):
        second = self.package / "second.cabal"
        shutil.copyfile(self.cabal_file, second)
        self.reject("plan-package", self.package, contains="multiple .cabal files")
        second.unlink()
        self.reject("plan-package", self.root, contains="no .cabal package")
        self.reject("plan-package", self.root / "missing.cabal", contains="does not exist")
        self.cabal_file.write_text("this is not a package description\n", encoding="utf-8")
        self.reject("plan-package", self.cabal_file, contains="tiny-fixture.cabal")

    def test_custom_setup_rejected_without_execution(self):
        text = self.cabal_file.read_text(encoding="utf-8")
        custom = text.replace("build-type: Simple", "build-type: Custom")
        custom += "\ncustom-setup\n  setup-depends: base, Cabal\n"
        self.cabal_file.write_text(custom, encoding="utf-8")
        (self.package / "Setup.hs").write_text('main = writeFile "executed" "bad"\n', encoding="utf-8")
        self.reject("plan-package", self.package, contains="only build-type: Simple")
        self.assertFalse((self.package / "executed").exists())

    def test_disabled_component_is_not_reported(self):
        text = self.cabal_file.read_text(encoding="utf-8")
        text = text.replace("executable hello\n  import: defaults", "executable hello\n  import: defaults\n  buildable: False")
        self.cabal_file.write_text(text, encoding="utf-8")
        self.assertEqual({c["name"] for c in self.plan()["components"]}, {"lib", "lib:words"})

    def test_unsupported_detailed_test(self):
        text = self.cabal_file.read_text(encoding="utf-8")
        text = text.replace("type: exitcode-stdio-1.0\n  hs-source-dirs: test\n  main-is: Main.hs",
                            "type: detailed-0.9\n  hs-source-dirs: test\n  test-module: Main")
        self.cabal_file.write_text(text, encoding="utf-8")
        self.reject("plan-package", self.package, "--dist-dir", self.dist,
                    "--enable-tests", contains="only exitcode-stdio-1.0 test suites")

    def test_relative_distribution_path(self):
        plan = json.loads(self.invoke("plan-package", self.package, "--dist-dir", "out").stdout)
        self.assertEqual(plan["setupConfig"], "out/setup-config")
        self.assertTrue((self.package / plan["setupConfig"]).exists())
        self.assertFalse((self.root / "out").exists())

    def test_cli_exposes_run_without_claiming_build_or_repl(self):
        self.assertIn("plan-package", self.invoke("--help").stdout)
        self.assertIn("run", self.invoke("--help").stdout)
        self.reject("run", contains="run requires --exe NAME")
        for args in (("build", "--dry-run"), ("repl",),
                     ("plan-package", "--unknown"), ("plan-package", "a", "b")):
            with self.subTest(args=args):
                self.reject(*args, contains="Usage:")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", type=Path, help="Prebuilt driver (requires --scratch); omit both to bootstrap")
    parser.add_argument("--scratch", type=Path, help="Fixture scratch directory (requires --driver)")
    args = parser.parse_args()
    if (args.driver is None) != (args.scratch is None):
        parser.error("--driver and --scratch must be supplied together, or both omitted")
    base = args.scratch.resolve() if args.scratch else REPO_ROOT / "build" / "driver-test-bootstrap"
    base.mkdir(parents=True, exist_ok=True)
    mode = "optimized" if sys.flags.optimize else "normal"
    directory = Path(tempfile.mkdtemp(prefix=mode + "-", dir=base))
    print(f"Cabal driver test evidence: {directory}", file=sys.stderr, flush=True)
    environment = os.environ.copy()
    environment.pop("GHC_PACKAGE_PATH", None)
    environment["GHC_ENVIRONMENT"] = "-"
    commands = CommandLog(directory, environment)
    DriverTest.commands = commands
    try:
        if args.driver:
            DriverTest.driver = args.driver.resolve()
            DriverTest.scratch = args.scratch.resolve()
            DriverTest.ghc_pkg = os.environ.get("GHC_PKG", "ghc-pkg")
            DriverTest.setup_command = [shutil.which("runghc") or "runghc", str(DRIVER_ROOT / "Setup.hs")]
            DriverTest.compiler_options = []
        else:
            DriverTest.driver, ghc, DriverTest.ghc_pkg, DriverTest.setup_command = bootstrap_driver(directory, commands)
            DriverTest.scratch = directory / "scratch"
            DriverTest.compiler_options = ["--with-ghc", ghc, "--with-ghc-pkg", DriverTest.ghc_pkg]
    except (OSError, RuntimeError) as error:
        message = f"Cabal driver bootstrap failed: {error}\nEvidence: {directory}\n"
        (directory / "failure.log").write_text(message, encoding="utf-8")
        print(message, file=sys.stderr)
        return 1
    DriverTest.scratch.mkdir(parents=True, exist_ok=True)
    with (directory / "tests.log").open("w", encoding="utf-8") as log:
        runner = unittest.TextTestRunner(stream=Tee(sys.stderr, log), verbosity=2)
        result = runner.run(unittest.defaultTestLoader.loadTestsFromTestCase(DriverTest))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
