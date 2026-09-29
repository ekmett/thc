#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Cbits target/manifest regression tests; no installed compiler is required."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import call, patch

spec = importlib.util.spec_from_file_location("build_cbits", Path(__file__).with_name("build-cbits.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class CompilerTargetTest(unittest.TestCase):
    def test_emitted_target_is_read_without_recompiling_or_relabeling(self):
        expected = "x86_64-pc-windows-msvc19.33.0"
        artifact = Path("pointer with spaces.bc")
        with patch.object(build.subprocess, "check_output", return_value=f'target triple = "{expected}"\n') as query:
            self.assertEqual(expected, build.bitcode_target("llvm-dis", artifact, expected))
        query.assert_called_once_with(["llvm-dis", str(artifact), "-o", "-"], text=True)

    def test_emitted_incompatible_or_missing_target_is_rejected(self):
        for ir in ('target triple = "x86_64-w64-windows-gnu"\n', ""):
            with self.subTest(ir=ir), patch.object(build.subprocess, "check_output", return_value=ir):
                with self.assertRaisesRegex(SystemExit, "Compiled bitcode target mismatch"):
                    build.bitcode_target("llvm-dis", Path("pointer.bc"), "x86_64-pc-windows-msvc19.33.0")

    def test_linux_vendor_is_explicitly_normalized_and_requeried(self):
        for vendor in ("pc", "unknown", "redhat"):
            for arch in ("x86_64", "AMD64"):
                with self.subTest(vendor=vendor, arch=arch):
                    default = f"x86_64-{vendor}-linux-gnu"
                    target = "x86_64-unknown-linux-gnu"
                    with patch.object(build.subprocess, "check_output", side_effect=[default + "\n", target + "\n"]) as query:
                        result = build.compiler_target("/clang with spaces", "Linux", arch)
                    command = ["/clang with spaces", "--target=" + target]
                    self.assertEqual((command, target, default), result)
                    self.assertEqual([call([command[0], "-dumpmachine"], text=True),
                                      call([*command, "-dumpmachine"], text=True)], query.call_args_list)

    def test_linux_arm_aliases_use_aarch64_without_changing_abi(self):
        for arch in ("arm64", "aarch64"):
            for compiler_arch in ("arm64", "aarch64"):
                with self.subTest(arch=arch, compiler_arch=compiler_arch):
                    default = f"{compiler_arch}-unknown-linux-gnu"
                    target = "aarch64-unknown-linux-gnu"
                    with patch.object(build.subprocess, "check_output", side_effect=[default, target]):
                        self.assertEqual((["clang", "--target=" + target], target, default),
                                         build.compiler_target("clang", "Linux", arch))

    def test_darwin_keeps_native_target_and_deployment_version(self):
        for arch, target in (("arm64", "arm64-apple-darwin25.0.0"),
                             ("aarch64", "arm64-apple-darwin25.0.0"),
                             ("x86_64", "x86_64-apple-darwin24.6.0")):
            with self.subTest(arch=arch):
                with patch.object(build.subprocess, "check_output", return_value=target) as query:
                    self.assertEqual((["clang"], target, target), build.compiler_target("clang", "Darwin", arch))
                query.assert_called_once_with(["clang", "-dumpmachine"], text=True)

    def test_wrong_host_architecture_os_and_malformed_triples_reject(self):
        cases = [("Linux", "x86_64", "aarch64-unknown-linux-gnu"),
                 ("Linux", "aarch64", "x86_64-pc-linux-gnu"),
                 ("Linux", "x86_64", "x86_64-apple-darwin25.0.0"),
                 ("Darwin", "arm64", "aarch64-unknown-linux-gnu"),
                 ("Windows", "AMD64", "x86_64-pc-windows-msvc"),
                 ("Linux", "ppc64le", "powerpc64le-unknown-linux-gnu"),
                 ("Linux", "x86_64", ""),
                 ("Linux", "x86_64", "x86_64"),
                 ("Linux", "x86_64", "x86_64-linuxvendor-freebsd-gnu")]
        for system, arch, target in cases:
            with self.subTest(system=system, arch=arch, target=target):
                with patch.object(build.subprocess, "check_output", return_value=target) as query:
                    with self.assertRaisesRegex(SystemExit, "supported host platform"):
                        build.compiler_target("clang", system, arch)
                self.assertEqual(1, query.call_count)

    def test_non_gnu_and_x32_linux_abis_are_not_silently_changed(self):
        for suffix in ("musl", "gnux32", "gnueabi", "gnu-extra", ""):
            with self.subTest(suffix=suffix):
                target = "x86_64-pc-linux" + ("-" + suffix if suffix else "")
                with patch.object(build.subprocess, "check_output", return_value=target) as query:
                    with self.assertRaisesRegex(SystemExit, "GNU LP64 ABI"):
                        build.compiler_target("clang", "Linux", "x86_64")
                self.assertEqual(1, query.call_count)

    def test_compiler_ignoring_requested_target_is_rejected(self):
        with patch.object(build.subprocess, "check_output", return_value="x86_64-pc-linux-gnu"):
            with self.assertRaisesRegex(SystemExit, "did not select the Sulong target"):
                build.compiler_target("clang", "Linux", "x86_64")

    def test_main_records_effective_target_command_and_source_hashes(self):
        with tempfile.TemporaryDirectory(prefix="thc-cbits-test-") as temporary:
            root = Path(temporary) / "repo with spaces"
            reference = root / "nih/pinned/ghc-9.14.1/libraries/ghc-internal"
            reference.mkdir(parents=True)
            for name in build.PINNED:
                path = reference / ("cbits" if name.endswith(".c") else "include") / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((build.ROOT / path.relative_to(root)).read_bytes())
            source = root / "src/main/c/md5-api.c"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"/* synthetic ABI wrapper */\n")
            for name in ("iconv-api.c", "gmp-api.c", "strerror-locale.c", "libdw-unavailable.c", "package-pointer-api.c",
                         "text-api.c", "wait-status-api.c", "bytestring-utf8-api.c"):
                (source.parent / name).write_bytes(b"/* synthetic ABI wrapper */\n")
            text_source = root / "nih/pinned/text-2.1.3"
            for name in build.TEXT_SHA256:
                path = root / "nih/pinned/openbsd-memchr-1.8.c" if name == "openbsd-memchr.c" else text_source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((build.ROOT / path.relative_to(root)).read_bytes())
            utf8 = root / "nih/pinned/bytestring-0.12.2.0/cbits/is-valid-utf8.c"
            utf8.parent.mkdir(parents=True)
            utf8.write_bytes((build.ROOT / utf8.relative_to(root)).read_bytes())
            libdw = root / "nih/pinned/ghc-9.14.1/rts"
            libdw.mkdir(parents=True)
            for name in build.LIBDW_SHA256 | build.RTS_FLOAT_SHA256:
                (libdw / name).write_bytes((build.ROOT / "nih/pinned/ghc-9.14.1/rts" / name).read_bytes())
            original = root / "nih/pinned/ghc-9.14.1/libraries/ghc-internal/cbits/strerror.c"
            original.parent.mkdir(parents=True, exist_ok=True)
            original.write_bytes((build.ROOT / "nih/pinned/ghc-9.14.1/libraries/ghc-internal/cbits/strerror.c").read_bytes())
            libdir = root / "ghc-lib"
            libdir.mkdir()
            (libdir / "HsFFI.h").write_bytes(b"/* synthetic GHC header */\n")
            (libdir / "HsBaseConfig.h").write_bytes(b"#define HAVE_STRERROR_R 1\n")
            (libdir / "HsUnix.h").write_bytes(b"/* synthetic Unix header */\n")
            (libdir / "HsUnixConfig.h").write_bytes(b"/* synthetic Unix config */\n")
            output = root / "generated"
            default, target = "x86_64-pc-linux-gnu", "x86_64-unknown-linux-gnu"

            def query(command, **kwargs):
                if "-S" in command and command[-3].endswith("bytestring-utf8.bc"):
                    return "define i32 @bytestring_is_valid_utf8(ptr %src, i64 %len) { ret i32 1 }\n"
                if "-S" in command:
                    self.assertEqual(["/clang", "--target=" + target, "-S", "-emit-llvm",
                                      str(output.resolve() / "thc/cbits/text.bc"), "-o", "-"], command)
                    return "define ptr @thc_text_memchr(ptr %src, i32 %byte, i64 %len) { ret ptr null }\n"
                replies = {("/clang", "-dumpmachine"): default,
                           ("/clang", "--target=" + target, "-dumpmachine"): target,
                           ("/clang", "--version"): "synthetic clang 20",
                           ("/ghc", "--numeric-version"): "9.14.1",
                           ("/ghc", "--print-libdir"): str(libdir)}
                return replies[tuple(command)] + "\n"

            def compile_bitcode(command, **kwargs):
                self.assertEqual("/clang", command[0])
                self.assertEqual(["--target=" + target], [arg for arg in command if arg.startswith("--target=")])
                self.assertEqual({"cwd": root, "check": True}, kwargs)
                Path(command[command.index("-o") + 1]).write_bytes(b"synthetic LLVM bitcode")

            with (patch.object(build, "ROOT", root),
                  patch.object(build.platform, "system", return_value="Linux"),
                  patch.object(build.platform, "machine", return_value="x86_64"),
                  patch.dict(build.os.environ, {"THC_CLANG": "clang", "GHC": "ghc"}),
                  patch.object(build.shutil, "which", side_effect=lambda name: "/" + name),
                  patch.object(build.subprocess, "check_output", side_effect=query),
                  patch.object(build.subprocess, "run", side_effect=compile_bitcode) as compile_call,
                  patch("sys.argv", ["build-cbits.py", "--output", str(output)]),
                  contextlib.redirect_stdout(io.StringIO())):
                build.main()
            manifest = json.loads((output / "thc/cbits/manifest.json").read_text())
            self.assertEqual(default, manifest["compilerDefaultTarget"])
            self.assertEqual(target, manifest["target"])
            self.assertEqual("Linux", manifest["system"])
            self.assertEqual("x86_64", manifest["architecture"])
            self.assertEqual(13, len(manifest["commands"]))
            self.assertEqual(29, len(manifest["sources"]))
            self.assertEqual(11, len(manifest["artifacts"]))
            float_command = next(c for c in manifest["commands"] if str(libdw / "StgPrimFloat.c") in c)
            self.assertIn("-shared", float_command)
            self.assertNotIn("-emit-llvm", float_command)
            utf8_command = next(c for c in manifest["commands"] if str(Path("src/main/c/bytestring-utf8-api.c")) in c)
            self.assertIn("-D__STDC_NO_ATOMICS__=1", utf8_command)
            self.assertEqual(build.BYTESTRING_UTF8_SHA256, hashlib.sha256(
                (output / "thc/cbits/bytestring-utf8-LICENSE").read_bytes()).hexdigest())
            for entry in manifest["sources"] + manifest["artifacts"]:
                self.assertEqual(hashlib.sha256(Path(entry["path"]).read_bytes()).hexdigest(), entry["sha256"])


if __name__ == "__main__":
    unittest.main()
