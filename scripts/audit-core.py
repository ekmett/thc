#!/usr/bin/env python3
"""Audit the syntactically reachable exported Core, without evaluating it.

Every alternative and local RHS of each reachable global is checked. Lexical
scope is exact: recursive RHSs see their group; nonrecursive RHSs do not; case
binders scope over alternatives only. An accepted report is a static capability
check, not a proof that arbitrary inputs terminate or avoid a Haskell error.
"""
import argparse
from collections import deque
import json
from pathlib import Path
import sys


class Audit:
    def __init__(self, modules, capabilities):
        self.cap = capabilities
        self.bindings = {}
        self.constructors = {}
        self.sources = {}
        self.issues = []
        self.edges = []
        self.missing = {}
        self.primitives = {}
        self.literals = {}
        self.used_constructors = {}
        self.reachable = []
        self.chains = {}
        self.queue = deque()
        for source, module in modules:
            if module.get('schema') != 1 or module.get('ghc') != '9.14.1':
                self.issue('module-format', None, source, 'Requires schema 1 / GHC 9.14.1')
            for binding in module.get('bindings', []):
                key = binding.get('id')
                if not isinstance(key, str):
                    self.issue('binding-id', None, source, 'Binding lacks a string id')
                    continue
                if key in self.bindings:
                    self.issue('duplicate-binding', key, source, 'Also supplied by ' + self.sources[key])
                else:
                    self.bindings[key] = binding
                    self.sources[key] = source
            for constructor in module.get('constructors', []):
                key = constructor.get('id')
                if not isinstance(key, str):
                    self.issue('constructor-id', None, source, 'Constructor lacks a string id')
                elif key in self.constructors and self.constructors[key] != constructor:
                    self.issue('inconsistent-constructor', None, source, key)
                else:
                    self.constructors[key] = constructor

    def issue(self, code, owner, path, detail):
        self.issues.append(dict(code=code, owner=owner, path=path, detail=detail))

    def location(self, owner, path):
        return dict(owner=owner, path=path)

    def representation(self, rep, owner, path):
        if not isinstance(rep, dict):
            self.issue('representation-proof', owner, path, 'Expected representation record')
            return
        # A logical empty/singleton tuple is not a scalar or a state token.
        # Unknown metadata without this GHC-derived evidence remains valid.
        if 'aggregate' in rep:
            aggregate = rep['aggregate']
            if aggregate in ('unboxed-tuple', 'unboxed-sum'):
                self.issue('aggregate-representation', owner, path, aggregate)
            else:
                self.issue('representation-proof', owner, path, 'Invalid aggregate kind')
        kind, registers, evaluated = rep.get('kind'), rep.get('primReps'), rep.get('evaluated')
        kinds = {'long', 'address', 'void', 'data', 'closure', 'object', 'unknown'}
        if kind not in kinds or type(evaluated) is not bool or (registers is not None and
                (not isinstance(registers, list) or any(not isinstance(r, str) for r in registers))):
            self.issue('representation-proof', owner, path, 'Invalid kind, register list, or WHNF evidence')
            return
        longs = {'IntRep', 'Int8Rep', 'Int16Rep', 'Int32Rep', 'Int64Rep',
                 'WordRep', 'Word8Rep', 'Word16Rep', 'Word32Rep', 'Word64Rep'}
        valid = (kind == 'unknown' or
                 kind == 'long' and isinstance(registers, list) and len(registers) == 1 and registers[0] in longs or
                 kind == 'address' and registers == ['AddrRep'] or
                 kind == 'void' and registers == [] or
                 kind in {'data', 'closure', 'object'} and isinstance(registers, list) and len(registers) == 1
                 and registers[0] in {'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)', 'BoxedRep Nothing'})
        if not valid:
            self.issue('representation-proof', owner, path, f'{kind}: inconsistent primitive registers {registers!r}')

    def binding_metadata(self, binding, owner, path):
        if 'rep' in binding:
            self.representation(binding['rep'], owner, path + '/rep')
        if 'joinValueArity' in binding:
            arity = binding['joinValueArity']
            expr = binding.get('expr')
            available = len(expr[1]) if isinstance(expr, list) and expr and expr[0] == 'lam' else 0
            info = binding.get('info')
            raw = info.get('joinArity') if isinstance(info, dict) else None
            if type(arity) is not int or arity < 0 or arity > available or type(raw) is not int or arity > raw:
                self.issue('join-metadata', owner, path, 'Join prefix disagrees with erased lambdas/raw join arity')
            self.representation(binding.get('joinResultRep'), owner, path + '/joinResultRep')

    def expression_metadata(self, expr, owner, path):
        index = {'var': 2, 'lit': 3, 'app': 6, 'lam': 3, 'let': 4,
                 'case': 4, 'con': 3, 'prim': 2, 'void': 1}.get(expr[0])
        if index is None or len(expr) <= index:
            return
        metadata = expr[index]
        if not isinstance(metadata, dict):
            self.issue('expression-metadata', owner, path, 'Expected metadata record')
            return
        self.representation(metadata.get('rep'), owner, path + '/rep')
        if expr[0] == 'lam':
            self.representation(metadata.get('resultRep'), owner, path + '/resultRep')
        if expr[0] == 'case':
            binder = metadata.get('binder')
            if not isinstance(binder, dict) or binder.get('id') != expr[2]:
                self.issue('case-binder-metadata', owner, path, 'Case binder metadata must match its positional id')
            else:
                self.binder_ids([binder], owner, path + '/binder')
                proof = binder.get('rep')
                if not isinstance(proof, dict) or proof.get('evaluated') is not True:
                    self.issue('case-binder-metadata', owner, path, 'Case binder must be in WHNF')

    def binder_ids(self, binders, owner, path):
        if not isinstance(binders, list):
            self.issue('binder-list', owner, path, 'Expected binder records')
            return set()
        ids = set()
        for i, binder in enumerate(binders):
            if not isinstance(binder, dict) or not isinstance(binder.get('id'), str):
                self.issue('binder-id', owner, f'{path}/{i}', 'Expected a string binder id')
                continue
            if binder['id'] in ids:
                self.issue('duplicate-local-binder', owner, f'{path}/{i}', binder['id'])
            ids.add(binder['id'])
            self.binding_metadata(binder, owner, f'{path}/{i}')
            if type(binder.get('lifted')) is not bool:
                self.issue('unknown-binder-levity', owner, f'{path}/{i}', binder['id'])
        return ids

    def reference(self, key, owner, path):
        location = self.location(owner, path)
        self.edges.append(dict(caller=owner, dependency=key, path=path))
        if key in self.bindings:
            if key not in self.chains:
                self.chains[key] = self.chains[owner] + [key]
                self.queue.append(key)
        elif key not in self.cap.get('externalBindings', []):
            self.missing.setdefault(key, []).append(location)

    def literal(self, kind, value, owner, path):
        item = self.literals.setdefault(kind, dict(kind=kind, examples=[], uses=[]))
        if value not in item['examples'] and len(item['examples']) < 8:
            item['examples'].append(value)
        item['uses'].append(self.location(owner, path))
        if kind not in self.cap['literalKinds']:
            self.issue('unsupported-literal', owner, path, kind)
            return
        if kind == 'string-bytes':
            if not isinstance(value, str) or len(value) % 2 or any(c not in '0123456789abcdefABCDEF' for c in value):
                self.issue('invalid-literal-value', owner, path, 'string-bytes must contain pairs of hexadecimal digits')
        if kind in self.cap.get('integerLiteralRanges', {}):
            lo, hi = self.cap['integerLiteralRanges'][kind]
            try:
                number = int(value)
                if str(number) != value or not lo <= number <= hi:
                    raise ValueError()
            except (TypeError, ValueError):
                self.issue('invalid-literal-value', owner, path, f'{kind}: {value!r}')

    def constructor(self, key, owner, path, constructing, arity):
        use = dict(self.location(owner, path), operation='construct' if constructing else 'match', arity=arity)
        self.used_constructors.setdefault(key, []).append(use)
        info = self.constructors.get(key)
        if info is None:
            self.issue('missing-constructor', owner, path, key)
            return
        expected = info.get('arity')
        if arity != expected:
            self.issue('constructor-arity', owner, path, f'{key}: got {arity}, expected {expected}')
        if info.get('kind', 'boxed') not in self.cap['constructorKinds']:
            self.issue('constructor-kind', owner, path, f'{key}: {info.get("kind")}')
        reps = info.get('fieldReps')
        if not isinstance(reps, list) or len(reps) != expected:
            self.issue('constructor-representations', owner, path, f'{key}: missing/misaligned fieldReps')
        else:
            for index, registers in enumerate(reps):
                if not isinstance(registers, list) or len(registers) > 1:
                    self.issue('constructor-field-representation', owner, path, f'{key}[{index}]: {registers!r}')
                elif registers and registers[0] not in self.cap['fieldRepresentations']:
                    self.issue('constructor-field-representation', owner, path, f'{key}[{index}]: {registers[0]}')
        # Matching only needs a layout; strictness is checked when constructing.
        if constructing:
            strict, lifted = info.get('strictFields'), info.get('fieldLifted')
            if not isinstance(strict, list) or not isinstance(lifted, list) or len(strict) != expected or len(lifted) != expected:
                self.issue('constructor-strictness', owner, path, f'{key}: missing/misaligned strictness and levity')
            else:
                for index, (is_strict, is_lifted) in enumerate(zip(strict, lifted)):
                    if type(is_strict) is not bool or (is_strict and type(is_lifted) is not bool):
                        self.issue('constructor-strictness', owner, path, f'{key}[{index}]: unknown strictness/levity')
                    elif is_strict and is_lifted and not self.cap['strictLiftedFields']:
                        self.issue('strict-lifted-field', owner, path, f'{key}[{index}]')

    def walk(self, expr, bound, owner, path, primitive_arity=None):
        if not isinstance(expr, list) or not expr or not isinstance(expr[0], str):
            self.issue('malformed-expression', owner, path, repr(expr)[:160])
            return
        tag = expr[0]
        try:
            self.expression_metadata(expr, owner, path)
            if tag == 'var':
                if not isinstance(expr[1], str):
                    raise ValueError('Variable id must be a string')
                if expr[1] not in bound:
                    self.reference(expr[1], owner, path)
            elif tag == 'lit':
                self.literal(expr[1], expr[2], owner, path)
            elif tag == 'void':
                pass
            elif tag == 'lam':
                ids = self.binder_ids(expr[1], owner, path + '/binders')
                self.walk(expr[2], bound | ids, owner, path + '/body')
            elif tag == 'app':
                arguments = expr[2]
                flags = expr[3] if len(expr) > 3 else None
                if not isinstance(arguments, list):
                    raise ValueError('Application arguments must be an array')
                if not isinstance(flags, list) or len(flags) != len(arguments) or any(type(f) is not bool for f in flags):
                    self.issue('application-levity', owner, path, 'Missing/invalid argument representation flags')
                self.walk(expr[1], bound, owner, path + '/function', len(arguments))
                for index, argument in enumerate(arguments):
                    self.walk(argument, bound, owner, f'{path}/arguments/{index}')
            elif tag == 'let':
                recursive, group = expr[1], expr[2]
                if type(recursive) is not bool:
                    raise ValueError('Let recursive flag must be boolean')
                ids = self.binder_ids(group, owner, path + '/bindings')
                for index, binding in enumerate(group):
                    if not isinstance(binding, dict):
                        continue
                    if recursive and binding.get('lifted') is False:
                        self.issue('recursive-unlifted', owner, f'{path}/bindings/{index}', binding.get('id'))
                    self.walk(binding.get('expr'), bound | ids if recursive else bound,
                              owner, f'{path}/bindings/{index}/rhs')
                self.walk(expr[3], bound | ids, owner, path + '/body')
            elif tag == 'case':
                self.walk(expr[1], bound, owner, path + '/scrutinee')
                if not isinstance(expr[2], str):
                    raise ValueError('Case binder must be a string')
                for index, alternative in enumerate(expr[3]):
                    altpath = f'{path}/alternatives/{index}'
                    kind, value, ids, rhs = alternative[:4]
                    if len(alternative) > 4:
                        metadata = alternative[4]
                        records = metadata.get('binders') if isinstance(metadata, dict) else None
                        self.binder_ids(records, owner, altpath + '/binders')
                        if not isinstance(records, list) or [b.get('id') for b in records if isinstance(b, dict)] != ids:
                            self.issue('alternative-binder-metadata', owner, altpath, 'Pattern metadata must preserve binder order')
                    if not isinstance(ids, list) or any(not isinstance(i, str) for i in ids):
                        raise ValueError('Alternative binders must be strings')
                    if kind == 'data':
                        self.constructor(value, owner, altpath, False, len(ids))
                    elif kind == 'lit':
                        self.literal(value[0], value[1], owner, altpath + '/literal')
                    elif kind != 'default':
                        self.issue('alternative-kind', owner, altpath, kind)
                    self.walk(rhs, bound | {expr[2]} | set(ids), owner, altpath + '/body')
            elif tag == 'con':
                self.constructor(expr[1], owner, path, True, expr[2])
            elif tag == 'prim':
                name = expr[1]
                self.primitives.setdefault(name, []).append(dict(self.location(owner, path), arity=primitive_arity))
                expected = self.cap['primitives'].get(name)
                if expected is None:
                    self.issue('unsupported-primitive', owner, path, name)
                elif primitive_arity != expected:
                    self.issue('primitive-arity', owner, path, f'{name}: got {primitive_arity}, expected {expected}')
            else:
                self.issue('unsupported-node', owner, path, tag)
        except (IndexError, KeyError, TypeError, ValueError) as error:
            self.issue('malformed-expression', owner, path, str(error))

    def run(self, entries):
        roots = []
        for entry in entries:
            candidates = [entry] if entry in self.bindings else [k for k, b in self.bindings.items() if b.get('name') == entry]
            if len(candidates) != 1:
                self.issue('entry-resolution', None, entry, dict(candidates=candidates))
            else:
                key = candidates[0]
                roots.append(key)
                if key not in self.chains:
                    self.chains[key] = [key]
                    self.queue.append(key)
        while self.queue:
            key = self.queue.popleft()
            self.reachable.append(key)
            binding = self.bindings[key]
            self.binding_metadata(binding, key, '/binding')
            if type(binding.get('lifted')) is not bool:
                self.issue('unknown-binder-levity', key, '/binding', key)
            self.walk(binding.get('expr'), set(), key, '/expr')
        for issue in self.issues:
            if issue['owner'] in self.chains:
                issue['reachableVia'] = self.chains[issue['owner']]
        missing = [dict(id=key, reachableVia=self.chains[uses[0]['owner']] + [key], references=uses)
                   for key, uses in sorted(self.missing.items())]
        return dict(schema=1, audit='syntactic-reachable-core', roots=roots,
                    capabilityProfile=self.cap.get('name'), accepted=not self.issues and not missing,
                    summary=dict(suppliedBindings=len(self.bindings), reachableBindings=len(self.reachable),
                                 missingGlobals=len(missing), issues=len(self.issues)),
                    reachableBindings=[dict(id=k, source=self.sources[k], reachableVia=self.chains[k]) for k in self.reachable],
                    dependencies=self.edges, missingGlobals=missing,
                    runtimeExternals=[dict(id=key, uses=[edge for edge in self.edges if edge['dependency'] == key])
                                      for key in sorted((set(self.cap.get('externalBindings', [])) - self.bindings.keys()) & {edge['dependency'] for edge in self.edges})],
                    primitives=[dict(name=k, expectedArity=self.cap['primitives'].get(k), uses=v) for k, v in sorted(self.primitives.items())],
                    constructors=[dict(id=k, metadata=self.constructors.get(k), uses=v) for k, v in sorted(self.used_constructors.items())],
                    literals=[v for _, v in sorted(self.literals.items())], issues=self.issues,
                    limits=['All syntactically reachable branches and local RHSs are audited, including lazy error paths.',
                            'Acceptance checks the declared capability profile, not termination, branch feasibility, or runtime correctness.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('modules', nargs='*', help='Exported JSON modules, or directories containing exported *.json modules')
    parser.add_argument('--module-list', action='append', type=Path, default=[], help='Read an exact newline-delimited module manifest; relative paths are relative to the manifest')
    parser.add_argument('--entry', action='append', required=True, help='Exact global id or unambiguous occurrence name; repeatable')
    parser.add_argument('--capabilities', type=Path, default=Path(__file__).with_name('core-capabilities.json'))
    parser.add_argument('--output', type=Path, help='Write full JSON report here (otherwise stdout)')
    args = parser.parse_args()
    files = []
    for supplied in args.modules:
        path = Path(supplied)
        files.extend(sorted(path.glob('*.json')) if path.is_dir() else [path])
    try:
        for manifest in args.module_list:
            for line in manifest.read_text().splitlines():
                if line.strip():
                    path = Path(line.strip())
                    files.append(path if path.is_absolute() else manifest.parent / path)
        if not files:
            parser.error('Supply modules or --module-list')
        modules = [(str(path), json.loads(path.read_text())) for path in dict.fromkeys(files)]
        report = Audit(modules, json.loads(args.capabilities.read_text())).run(args.entry)
    except (OSError, ValueError, TypeError) as error:
        parser.error(str(error))
    text = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    else:
        print(text, end='')
    print(json.dumps(dict(accepted=report['accepted'], **report['summary'])), file=sys.stderr)
    return 0 if report['accepted'] else 1


if __name__ == '__main__':
    sys.exit(main())
