"""Fail closed before running GHC's logical 512-bit fixtures on an AVX2 host."""
import platform
import re

# GHC requires this front-end feature for 512-bit PrimReps. LLVM legalizes the
# actual operations to Haswell; flags alone are NOT proof of the emitted ISA.
AVX2_OPTIONS = ['-fllvm', '-mavx512f', '-optlc=-mcpu=haswell',
                '-optlc=-mattr=+avx2,-avx512f,-avx512cd',
                '-keep-s-files', '-keep-llvm-files']
REQUIRED_FLAGS = {'avx2', 'bmi1', 'bmi2', 'fma', 'sse4_1', 'sse4_2'}
FORBIDDEN = re.compile(r'%zmm|%k[0-7]\b|%(?:xmm|ymm)(?:1[6-9]|2[0-9]|3[01])\b', re.I)
INSTRUCTION = re.compile(r'^\s*[0-9a-f]+:\s+((?:[0-9a-f]{2}(?:[ \t]+|$))+)', re.M | re.I)
# Include REX as a conservative rejection control even where a prefix sequence
# would be invalid. Segment/address-size prefixes can legally precede EVEX.
PREFIXES = {0xf0, 0xf2, 0xf3, 0x2e, 0x36, 0x3e, 0x26, 0x64, 0x65, 0x66, 0x67} | set(range(0x40, 0x50))
SECTION = re.compile(r'^\s*\d+\s+(\S+)\s+([0-9a-f]+)\s+[0-9a-f]+\s+[0-9a-f]+\s+[0-9a-f]+\s+2\*\*\d+\s*$', re.I)


def check_disassembly(disassembly, undefined, name):
    if FORBIDDEN.search(disassembly):
        raise RuntimeError('AVX-512/EVEX instruction or register in generated object: ' + name)
    if re.search(r'stg_\S*(?:v64|zmm|avx512)', undefined, re.I):
        raise RuntimeError('AVX-512 RTS helper referenced by generated object: ' + name)
    instructions = list(INSTRUCTION.finditer(disassembly))
    if not instructions:
        raise RuntimeError('No instruction bytes in generated object disassembly: ' + name)
    for instruction in instructions:
        octets = bytes.fromhex(instruction[1])
        position = 0
        while position < len(octets) and octets[position] in PREFIXES:
            position += 1
        if position < len(octets) and octets[position] == 0x62:
            raise RuntimeError('AVX-512/EVEX instruction or register in generated object: ' + name)


def check_executable_sections(table, name):
    """Accept exactly one nonempty .text CODE section in GNU objdump -h output."""
    executable = []
    previous = ''
    for line in table.splitlines():
        if 'CODE' in {flag.strip() for flag in line.split(',')}:
            section = SECTION.fullmatch(previous)
            if section is None:
                raise RuntimeError('Unrecognized executable section header in generated object: ' + name)
            executable.append((section[1], int(section[2], 16)))
        previous = line
    if len(executable) != 1 or executable[0][0] != '.text' or executable[0][1] == 0:
        raise RuntimeError('Expected only a nonempty .text executable section in generated object: '
                           + name + ': ' + str(executable))


def audit_avx2(directory, run, record):
    if platform.machine() != 'x86_64' or platform.system() != 'Linux':
        raise RuntimeError('--native-avx2 currently requires Linux x86_64 and GNU binutils')
    cpu = run(['lscpu'], text=True, capture_output=True).stdout
    flags_line = next((line for line in cpu.splitlines() if line.startswith('Flags:')), '')
    flags = set(flags_line.partition(':')[2].split())
    if not REQUIRED_FLAGS <= flags:
        raise RuntimeError('Host lacks required Haswell features: ' + str(sorted(REQUIRED_FLAGS - flags)))
    cpu_path = directory / 'cpu.txt'
    cpu_path.write_text(cpu)
    artifacts = [cpu_path]
    objects = sorted(directory.glob('*.o'))
    if {p.name for p in objects} != {'GeneratedSimdFamilies.o', 'Main.o'}:
        raise RuntimeError('Unexpected native SIMD object set: ' + str(objects))
    for obj in objects:
        sections = run(['objdump', '-h', obj], text=True, capture_output=True).stdout
        sections_path = obj.with_suffix('.sections')
        sections_path.write_text(sections)
        check_executable_sections(sections, obj.name)
        # -d alone hides instructions behind GHC info symbols. Force disassembly.
        # A full x86 instruction fits on one line, avoiding continuation bytes
        # being mistaken for the beginning of another instruction.
        assembly = run(['objdump', '-D', '--insn-width=16', '-j', '.text', obj], text=True, capture_output=True).stdout
        undefined = run(['nm', '-u', obj], text=True, capture_output=True).stdout
        dis_path = obj.with_suffix('.disassembly')
        refs_path = obj.with_suffix('.undefined')
        dis_path.write_text(assembly)
        refs_path.write_text(undefined)
        check_disassembly(assembly, undefined, obj.name)
        artifacts += [obj, sections_path, dis_path, refs_path]
    return dict(mode='logical SIMD legalized to AVX2; not AVX512 execution or performance',
                cpuHasAVX512=any(f.startswith('avx512') for f in flags),
                auditedObjects=[record(p) for p in objects]), artifacts
