#!/usr/bin/env python3
"""The AVX2 execution gate rejects wider instructions and runtime helper edges."""
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from simd_native_isa import audit_avx2, check_disassembly, check_executable_sections

AVX2 = '  10: c5 fd fe c1 vpaddd %ymm1,%ymm0,%ymm0\n'
TEXT = ('  0 .text 00000010 0000000000000000 0000000000000000 00000040 2**4\n'
        '                  CONTENTS, ALLOC, LOAD, RELOC, READONLY, CODE\n')
DATA = ('  1 .rodata 00000010 0000000000000000 0000000000000000 00000050 2**4\n'
        '                  CONTENTS, ALLOC, LOAD, READONLY, DATA\n')


class NativeIsaTest(unittest.TestCase):
    def test_avx2_and_scalar_code(self):
        check_disassembly(AVX2, ' U stg_gc_noregs\n', 'test')
        check_disassembly('  10: 67 c5 fe 6f 00 vmovdqu (%eax),%ymm0\n', '', 'test')
        # 0x62 is safe inside an immediate/displacement, not as an EVEX prefix.
        check_disassembly('  10: 48 c7 c0 62 00 00 00 mov $0x62,%rax\n', '', 'test')

    def test_forbidden_registers_and_evex_encoding(self):
        for text in ('%zmm0', '%k7', '%xmm16', '%ymm31', '  10: 62 f1 7c 48 58 c0 vaddps'):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                check_disassembly(text, '', 'test')

    def test_evex_with_legacy_prefixes(self):
        # This exact address-size-prefixed instruction is decoded by GNU
        # objdump as vmovdqa32 (%eax),%ymm0; low YMM registers do not reveal EVEX.
        check = '  0: 67 62 f1 7d 28 6f 00 vmovdqa32 (%eax),%ymm0\n'
        with self.assertRaisesRegex(RuntimeError, 'AVX-512/EVEX'):
            check_disassembly(check, '', 'test')
        for prefix in ('64', '65 67', 'f0', 'f2', 'f3', '2e', '36', '3e', '26', '66', '48'):
            with self.subTest(prefix=prefix), self.assertRaisesRegex(RuntimeError, 'AVX-512/EVEX'):
                check_disassembly(f'  A: {prefix} 62 f1 7d 28 6f 00 instruction\n', '', 'test')

    def test_missing_disassembly_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'No instruction bytes'):
            check_disassembly('Disassembly of section .text:\n', '', 'test')

    def test_wider_rts_helpers(self):
        for name in ('stg_gc_v64', 'stg_zmm_save', 'stg_avx512_restore'):
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                check_disassembly(AVX2, ' U ' + name, 'test')

    def test_exact_nonempty_text_section(self):
        check_executable_sections(TEXT, 'test')
        check_executable_sections(TEXT + DATA, 'test')

    def test_other_or_missing_executable_sections_are_rejected(self):
        for table in ('', DATA, TEXT.replace('00000010', '00000000'), TEXT + TEXT,
                      TEXT.replace('.text', '.text.hot'), TEXT + TEXT.replace('.text', '.text.extra'),
                      'not a section header\n                  CONTENTS, CODE\n'):
            with self.subTest(table=table), self.assertRaises(RuntimeError):
                check_executable_sections(table, 'test')

    def test_audit_rejects_extra_code_before_disassembly_and_preserves_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in ('GeneratedSimdFamilies.o', 'Main.o'):
                (directory / name).touch()
            calls = []
            def run(argv, **kwargs):
                calls.append(list(map(str, argv)))
                if argv == ['lscpu']:
                    return SimpleNamespace(stdout='Flags: avx2 bmi1 bmi2 fma sse4_1 sse4_2\n')
                if argv[:2] == ['objdump', '-h']:
                    return SimpleNamespace(stdout=TEXT + TEXT.replace('.text', '.text.extra'))
                self.fail('Instruction audit must not begin after a section rejection')
            with patch('simd_native_isa.platform.machine', return_value='x86_64'), \
                    patch('simd_native_isa.platform.system', return_value='Linux'), \
                    self.assertRaises(RuntimeError):
                audit_avx2(directory, run, lambda path: dict(path=path.name))
            self.assertEqual(2, len(calls))
            self.assertEqual(TEXT + TEXT.replace('.text', '.text.extra'),
                             (directory / 'GeneratedSimdFamilies.sections').read_text())

    def test_successful_audit_records_sections_and_unwrapped_instruction_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in ('GeneratedSimdFamilies.o', 'Main.o'):
                (directory / name).touch()
            calls = []
            def run(argv, **kwargs):
                calls.append(list(map(str, argv)))
                if argv == ['lscpu']:
                    return SimpleNamespace(stdout='Flags: avx2 bmi1 bmi2 fma sse4_1 sse4_2\n')
                if argv[:2] == ['objdump', '-h']:
                    return SimpleNamespace(stdout=TEXT + DATA)
                if argv[:2] == ['objdump', '-D']:
                    self.assertIn('--insn-width=16', argv)
                    return SimpleNamespace(stdout=AVX2)
                if argv[:2] == ['nm', '-u']:
                    return SimpleNamespace(stdout=' U stg_gc_noregs\n')
                self.fail('Unexpected audit command: ' + str(argv))
            with patch('simd_native_isa.platform.machine', return_value='x86_64'), \
                    patch('simd_native_isa.platform.system', return_value='Linux'):
                report, artifacts = audit_avx2(directory, run, lambda path: dict(path=path.name))
            self.assertFalse(report['cpuHasAVX512'])
            self.assertEqual(7, len(calls))
            self.assertEqual(9, len(artifacts))
            for name in ('GeneratedSimdFamilies', 'Main'):
                self.assertIn(directory / (name + '.sections'), artifacts)
                self.assertEqual(TEXT + DATA, (directory / (name + '.sections')).read_text())


if __name__ == '__main__':
    unittest.main()
