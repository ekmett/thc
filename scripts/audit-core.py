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

from core_vectors import OPERATIONS as VECTOR_OPERATIONS, is_vector, proof_error as vector_proof_error, signature_matches as vector_signature_matches


# The identical checked-in resource is packaged in the JVM runtime jar.
SCALAR_SIGNATURES = json.loads((Path(__file__).resolve().parent.parent /
    'src/main/resources/thc/scalar-primop-signatures.json').read_text())['primitives']


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
        # Aggregate capability is driven by exact logical metadata, never width.
        if 'aggregate' in rep:
            aggregate = rep['aggregate']
            if aggregate not in ('unboxed-tuple', 'unboxed-sum'):
                self.issue('representation-proof', owner, path, 'Invalid aggregate kind')
            elif aggregate not in self.cap.get('aggregateResults', []):
                self.issue('aggregate-representation', owner, path, aggregate)
            elif not isinstance(rep.get('components'), list):
                self.issue('aggregate-representation', owner, path, aggregate + ': missing exact components')
            else:
                physical = []
                for index, component in enumerate(rep['components']):
                    self.representation(component, owner, f'{path}/components/{index}')
                    registers = component.get('primReps') if isinstance(component, dict) else None
                    if not isinstance(registers, list):
                        self.issue('aggregate-representation', owner, path, aggregate + ': unresolved component')
                    else:
                        physical.extend(registers)
                        if 'aggregate' not in component and (component.get('kind') == 'unknown' or
                                any(r not in self.cap['fieldRepresentations'] for r in registers)):
                            self.issue('aggregate-representation', owner, path, aggregate + ': unsupported component')
                if rep.get('kind') != 'unknown' or rep.get('primReps') != physical:
                    self.issue('representation-proof', owner, path, 'Tuple components disagree with physical representations')
        kind, registers, evaluated = rep.get('kind'), rep.get('primReps'), rep.get('evaluated')
        kinds = {'long', 'float', 'double', 'address', 'void', 'data', 'closure', 'object', 'unknown', 'vector'}
        if kind not in kinds or type(evaluated) is not bool or (registers is not None and
                (not isinstance(registers, list) or any(not isinstance(r, str) for r in registers))):
            self.issue('representation-proof', owner, path, 'Invalid kind, register list, or WHNF evidence')
            return
        longs = {'IntRep', 'Int8Rep', 'Int16Rep', 'Int32Rep', 'Int64Rep',
                 'WordRep', 'Word8Rep', 'Word16Rep', 'Word32Rep', 'Word64Rep'}
        vector_error = vector_proof_error(rep)
        if vector_error:
            self.issue('vector-representation', owner, path, vector_error)
        valid = (kind in ('unknown', 'vector') or
                 kind == 'float' and registers == ['FloatRep'] or
                 kind == 'double' and registers == ['DoubleRep'] or
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
            if is_vector(binding.get('joinResultRep')):
                self.issue('vector-boundary', owner, path, 'vector join result')
            if (self.is_tuple(binding.get('joinResultRep')) and
                    'unboxed-tuple' not in self.cap.get('aggregateJoinResults', [])):
                self.issue('aggregate-boundary', owner, path, 'unboxed-tuple join result')
            body = expr[2] if available and arity == available else expr
            self.compare_shapes(binding.get('joinResultRep'), self.expression_rep(body), owner, path + '/joinResultRep')

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

    @staticmethod
    def is_tuple(rep):
        return isinstance(rep, dict) and rep.get('aggregate') == 'unboxed-tuple'

    @classmethod
    def is_empty_tuple(cls, rep):
        return (cls.is_tuple(rep) and rep.get('kind') == 'unknown' and
                rep.get('components') == [] and rep.get('primReps') == [])

    def supported_empty_input(self, rep):
        return self.is_empty_tuple(rep) and 'empty-unboxed-tuple' in self.cap.get('aggregateInputs', [])

    @classmethod
    def shape(cls, rep):
        """Logical tuple boundaries are significant even at zero/one register.

        Boxed leaf kinds and WHNF evidence may refine independently; primitive
        representation names (including boxed liftedness) must still agree.
        """
        if not isinstance(rep, dict):
            return None
        if cls.is_tuple(rep):
            components = rep.get('components')
            if not isinstance(components, list):
                return None
            children = tuple(cls.shape(component) for component in components)
            return None if None in children else ('tuple', children)
        registers = rep.get('primReps')
        return ('scalar', tuple(registers)) if isinstance(registers, list) else None

    def compare_shapes(self, expected, actual, owner, path, component=False):
        # Exact scalar register names constrain the same lexical value too.
        # Missing/unknown legacy proofs and boxed kind refinements remain compatible.
        kinds = [rep.get('kind', 'unknown') if isinstance(rep, dict) else 'unknown' for rep in (expected, actual)]
        if any(kind in ('float', 'double') for kind in kinds) and 'unknown' not in kinds and kinds[0] != kinds[1]:
            self.issue('scalar-representation', owner, path, 'Conflicting floating scalar carrier kinds')
        if all(isinstance(rep, dict) and rep.get('kind') == 'long' and
               isinstance(rep.get('primReps'), list) and len(rep['primReps']) == 1 for rep in (expected, actual)):
            if expected['primReps'] != actual['primReps']:
                self.issue('scalar-representation', owner, path, 'Conflicting exact scalar primitive representations')
        if all(isinstance(rep, dict) and rep.get('kind') in ('object', 'data', 'closure') and
               rep.get('primReps') in (['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])
               for rep in (expected, actual)) and expected['primReps'] != actual['primReps']:
            self.issue('scalar-representation', owner, path, 'Conflicting exact boxed levities')
        if not component and not (self.is_tuple(expected) or self.is_tuple(actual) or is_vector(expected) or is_vector(actual)):
            return
        left, right = self.shape(expected), self.shape(actual)
        if left is None or right is None or left != right:
            self.issue('aggregate-shape', owner, path, 'Conflicting or missing logical tuple representation proofs')

    @staticmethod
    def binder_scope(records):
        scope = {}
        for record in records:
            if isinstance(record, dict) and isinstance(record.get('id'), str):
                proof = record.get('rep')
                if 'joinValueArity' in record:
                    proof = dict(proof or {}, _join_result=record.get('joinResultRep'), _join_arity=record['joinValueArity'])
                scope[record['id']] = proof
        return scope

    def function_scope(self, records, bound, recursive):
        local = bound | self.binder_scope(records)
        # Resolve definition-site signatures, not aliases in the eventual
        # caller's scope (where an unrelated binder may shadow the same id).
        for _ in range(max(1, len(records))):
            changed = False
            for record in records:
                if not isinstance(record, dict) or not isinstance(record.get('id'), str):
                    continue
                formals = self.call_formals(record.get('expr'), local if recursive else bound)
                if formals is not None:
                    key = record['id']
                    proof = local.get(key)
                    if not isinstance(proof, dict) or proof.get('_call_formals') != formals:
                        local[key] = dict(proof or {}, _call_formals=formals)
                        changed = True
            if not changed:
                break
        return local

    def call_formals(self, expression, bound, seen=frozenset()):
        """Known lambda/PAP prefixes only; dynamic targets validate their layout at dispatch."""
        if not isinstance(expression, list) or not expression:
            return None
        if expression[0] == 'lam':
            if (len(expression) < 2 or not isinstance(expression[1], list) or
                    any(not isinstance(binder, dict) for binder in expression[1])):
                return None
            return [binder.get('rep') for binder in expression[1]]
        if expression[0] == 'var':
            key = expression[1]
            if key in seen:
                return None
            if key in bound:
                stored = bound[key]
                return stored.get('_call_formals') if isinstance(stored, dict) else None
            definition = self.bindings.get(key, {}).get('expr')
            return self.call_formals(definition, {}, seen | {key})
        if expression[0] == 'app':
            formals = self.call_formals(expression[1], bound, seen)
            count = len(expression[2])
            if formals is not None and count < len(formals):
                return formals[count:]
        return None

    @staticmethod
    def literal_rep(expr):
        # Signed/unsigned 16- and 32-bit literals retain exact narrow identity.
        # Other legacy literal forms keep their historical carrier-only proof.
        if not isinstance(expr, list) or not expr:
            return None
        if expr[0] == 'void':
            return dict(kind='void', evaluated=True)
        if expr[0] == 'lit' and len(expr) >= 3:
            narrow = {'int16': 'Int16Rep', 'word16': 'Word16Rep', 'int32': 'Int32Rep', 'word32': 'Word32Rep'}
            if expr[1] in narrow:
                return dict(kind='long', primReps=[narrow[expr[1]]], evaluated=True)
            kind = {'float': 'float', 'double': 'double', 'string-bytes': 'address',
                    **dict.fromkeys(('int', 'word', 'char', 'int8', 'int16', 'int32', 'int64',
                                     'word8', 'word16', 'word32', 'word64'), 'long')}.get(expr[1])
            return dict(kind=kind, evaluated=True) if kind else None
        return None

    @classmethod
    def expression_rep(cls, expr):
        if not isinstance(expr, list) or not expr:
            return None
        index = {'var': 2, 'lit': 3, 'app': 6, 'lam': 3, 'let': 4, 'case': 4, 'con': 3, 'prim': 2, 'void': 1}.get(expr[0])
        proof = expr[index].get('rep') if index is not None and len(expr) > index and isinstance(expr[index], dict) else None
        intrinsic = cls.literal_rep(expr)
        # Noinline/unary-class erasure can clear the certificate, but literal
        # syntax still constrains primitive operands and lexical comparisons.
        # expression_metadata independently rejects malformed raw records.
        if (intrinsic is not None and intrinsic.get('primReps') is not None and
                isinstance(proof, dict) and proof.get('kind') == 'unknown' and proof.get('primReps') is None and
                not cls.is_tuple(proof) and not is_vector(proof)):
            return intrinsic
        return proof if proof is not None else intrinsic

    def scalar_primitive(self, name, arguments, result, bound, owner, path):
        signature = SCALAR_SIGNATURES.get(name)
        if signature is None:
            return

        def check(expected, proof, position):
            if not isinstance(proof, dict) or proof.get('primReps') is None:
                return
            if proof.get('kind') == 'unknown' and not self.is_tuple(proof) and not is_vector(proof):
                return
            if self.is_tuple(proof) or is_vector(proof) or proof.get('primReps') != [expected]:
                self.issue('primitive-representation', owner, path + '/' + position,
                           f'{name}: expected {expected}, found {proof.get("primReps")}')

        for index, (argument, expected) in enumerate(zip(arguments, signature['arguments'])):
            proof = self.expression_rep(argument)
            check(expected, proof, f'arguments/{index}')
            # The runtime lowers the lexical value before checking an operand.
            # An absent/unknown occurrence must not hide a contradictory binder.
            if argument[0] == 'var':
                stored = bound.get(argument[1]) if argument[1] in bound else self.bindings.get(argument[1], {}).get('rep')
                check(expected, stored, f'arguments/{index}/binder')
        check(signature['result'], result, 'rep')

    def free_variables(self, expr):
        if not isinstance(expr, list) or not expr:
            return set()
        if expr[0] == 'var':
            return {expr[1]}
        if expr[0] == 'lam':
            return self.free_variables(expr[2]) - {b['id'] for b in expr[1]}
        if expr[0] == 'app':
            return self.free_variables(expr[1]) | set().union(*(self.free_variables(a) for a in expr[2]))
        if expr[0] == 'let':
            ids = {b['id'] for b in expr[2]}
            rhs = set().union(*(self.free_variables(b['expr']) for b in expr[2]))
            return (rhs - ids if expr[1] else rhs) | (self.free_variables(expr[3]) - ids)
        if expr[0] == 'case':
            return self.free_variables(expr[1]) | set().union(*(self.free_variables(a[3]) - set(a[2]) - {expr[2]} for a in expr[3]))
        return set()

    def constructor(self, key, owner, path, constructing, arity, tuple_rep=None):
        use = dict(self.location(owner, path), operation='construct' if constructing else 'match', arity=arity)
        self.used_constructors.setdefault(key, []).append(use)
        info = self.constructors.get(key)
        if info is None:
            self.issue('missing-constructor', owner, path, key)
            return
        expected = info.get('arity')
        if arity != expected:
            self.issue('constructor-arity', owner, path, f'{key}: got {arity}, expected {expected}')
        if info.get('kind') == 'unboxed-tuple' and self.is_tuple(tuple_rep) and 'unboxed-tuple' in self.cap.get('aggregateResults', []):
            components = tuple_rep.get('components')
            if not isinstance(components, list) or len(components) != expected:
                self.issue('aggregate-representation', owner, path, 'unboxed-tuple: constructor components mismatch')
            # Global tuple workers are representation-polymorphic. The instantiated
            # application/case proof supplies fields; generic worker nulls do not.
            return
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

    def walk(self, expr, bound, owner, path, primitive_arity=None, tuple_result=None, join_prefix=0):
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
                    stored = self.bindings.get(expr[1], {}).get('rep')
                    if self.is_tuple(stored) or self.is_tuple(self.expression_rep(expr)):
                        self.compare_shapes(stored, self.expression_rep(expr), owner, path + '/rep')
                else:
                    proof = bound[expr[1]]
                    if isinstance(proof, dict) and proof.get('_join_arity') == 0 and primitive_arity is None:
                        proof = proof['_join_result']
                    self.compare_shapes(proof, self.expression_rep(expr), owner, path + '/rep')
            elif tag == 'lit':
                self.literal(expr[1], expr[2], owner, path)
                self.compare_shapes(self.expression_rep(expr), self.literal_rep(expr), owner, path + '/rep')
                if expr[1] in ('int16', 'word16', 'int32', 'word32'):
                    proof, intrinsic = self.expression_rep(expr), self.literal_rep(expr)
                    if (not isinstance(proof, dict) or proof.get('kind') != 'long' or
                            proof.get('primReps') != intrinsic['primReps'] or self.is_tuple(proof) or is_vector(proof)):
                        self.issue('scalar-representation', owner, path + '/rep', 'Narrow literal requires exact signed/unsigned identity')
            elif tag == 'void':
                self.compare_shapes(self.expression_rep(expr), self.literal_rep(expr), owner, path + '/rep')
            elif tag == 'lam':
                ids = self.binder_ids(expr[1], owner, path + '/binders')
                for index, binder in enumerate(expr[1]):
                    if is_vector(binder.get('rep')):
                        self.issue('vector-boundary', owner, path, 'vector formal argument')
                    if self.is_tuple(binder.get('rep')) and (index < join_prefix or
                            not self.supported_empty_input(binder.get('rep'))):
                        self.issue('aggregate-boundary', owner, path, 'unboxed-tuple formal argument')
                    if self.is_empty_tuple(binder.get('rep')) and binder.get('lifted') is not False:
                        self.issue('application-levity', owner, path, 'Empty tuple formal must be unlifted')
                captured = {key for key in (self.free_variables(expr[2]) - ids) & bound.keys()
                            if self.is_tuple(bound[key])}
                vector_captures = {key for key in (self.free_variables(expr[2]) - ids) & bound.keys() if is_vector(bound[key])}
                if vector_captures:
                    self.issue('vector-boundary', owner, path, 'vector capture')
                if captured:
                    self.issue('aggregate-boundary', owner, path, 'unboxed-tuple capture')
                metadata = expr[3] if len(expr) > 3 and isinstance(expr[3], dict) else {}
                if is_vector(metadata.get('resultRep')) or is_vector(self.expression_rep(expr[2])):
                    self.issue('vector-boundary', owner, path, 'vector function result')
                self.compare_shapes(metadata.get('resultRep'), self.expression_rep(expr[2]), owner, path + '/resultRep')
                self.walk(expr[2], bound | self.binder_scope(expr[1]), owner, path + '/body')
            elif tag == 'app':
                arguments = expr[2]
                flags = expr[3] if len(expr) > 3 else None
                if not isinstance(arguments, list):
                    raise ValueError('Application arguments must be an array')
                if not isinstance(flags, list) or len(flags) != len(arguments) or any(type(f) is not bool for f in flags):
                    self.issue('application-levity', owner, path, 'Missing/invalid argument representation flags')
                function = expr[1]
                tuple_constructor = function[0] == 'con' and self.constructors.get(function[1], {}).get('kind') == 'unboxed-tuple'
                proof = self.expression_rep(expr)
                if function[0] == 'prim':
                    self.scalar_primitive(function[1], arguments, proof, bound, owner, path)
                tuple_primitive = self.cap.get('tuplePrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if tuple_primitive is not None:
                    expected_args = [('scalar', (rep,)) for rep in tuple_primitive['arguments']]
                    expected_result = ('tuple', tuple(('scalar', (rep,)) for rep in tuple_primitive['result']))
                    argument_reps = [self.expression_rep(argument) for argument in arguments]
                    actual_args = [self.shape(rep) for rep in argument_reps]
                    if (actual_args != expected_args or flags != [False] * len(expected_args) or
                            any(not isinstance(rep, dict) or rep.get('kind') != 'long' or 'aggregate' in rep for rep in argument_reps)):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact scalar arguments required')
                    if self.shape(proof) != expected_result:
                        self.issue('primitive-representation', owner, path, function[1] + ': exact logical tuple result required')
                array = self.cap.get('managedArrayPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if array is not None:
                    def array_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'int':
                            return kind == 'long' and reps == ['IntRep']
                        if role == 'array':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        return kind in ('object', 'data', 'closure') and reps == ['BoxedRep (Just Lifted)']
                    expected = array['arguments']
                    if (len(arguments) != len(expected) or flags != [r == 'element' for r in expected] or
                            any(not array_role(self.expression_rep(a), r) for a, r in zip(arguments, expected))):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact Array arguments required')
                    result = array['result']
                    if isinstance(result, list):
                        fields = proof.get('components') if isinstance(proof, dict) else None
                        valid = (self.is_tuple(proof) and proof.get('kind') == 'unknown' and
                                 isinstance(fields, list) and len(fields) == len(result) and
                                 all(array_role(rep, role) for rep, role in zip(fields, result)) and
                                 proof.get('primReps') == [r for field in fields for r in field['primReps']])
                    else:
                        valid = array_role(proof, result)
                    if not valid:
                        self.issue('primitive-representation', owner, path, function[1] + ': exact Array result required')
                bytearray_primitive = self.cap.get('managedByteArrayPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if bytearray_primitive is not None:
                    def exact(actual, expected):
                        return (isinstance(actual, dict) and actual.get('kind') == expected['kind'] and
                                self.shape(actual) == self.shape(expected) and
                                (not self.is_tuple(expected) or all(exact(a, e) for a, e in
                                    zip(actual.get('components', []), expected['components']))))
                    expected = bytearray_primitive['arguments']
                    if (len(arguments) != len(expected) or flags != [False] * len(expected) or
                            any(not exact(self.expression_rep(a), e) for a, e in zip(arguments, expected))):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact ByteArray arguments required')
                    if not exact(proof, bytearray_primitive['result']):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact ByteArray result required')
                mutvar = self.cap.get('managedMutVarPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if mutvar is not None:
                    def role_matches(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'mutvar':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        return kind in ('object', 'data', 'closure') and reps in (
                            ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])
                    actual = [self.expression_rep(argument) for argument in arguments]
                    expected = mutvar['arguments']
                    expected_flags = [isinstance(rep, dict) and rep.get('primReps') == ['BoxedRep (Just Lifted)'] for rep in actual]
                    if len(actual) != len(expected) or flags != expected_flags or any(
                            not role_matches(rep, role) for rep, role in zip(actual, expected)):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact MutVar arguments required')
                    result = mutvar['result']
                    if isinstance(result, list):
                        fields = proof.get('components') if isinstance(proof, dict) else None
                        valid = self.is_tuple(proof) and isinstance(fields, list) and len(fields) == len(result) and all(
                            role_matches(rep, role) for rep, role in zip(fields, result)) and proof.get('primReps') == fields[1]['primReps']
                    else:
                        valid = role_matches(proof, result)
                    if not valid:
                        self.issue('primitive-representation', owner, path, function[1] + ': exact MutVar result required')
                target = bound.get(function[1]) if function[0] == 'var' else None
                if isinstance(target, dict) and '_join_result' in target:
                    self.compare_shapes(target['_join_result'], proof, owner, path + '/rep')
                formals = self.call_formals(function, bound)
                if formals is not None:
                    for index, (formal, actual) in enumerate(zip(formals, arguments)):
                        if self.is_tuple(formal) or self.is_tuple(self.expression_rep(actual)):
                            self.compare_shapes(formal, self.expression_rep(actual), owner,
                                                f'{path}/arguments/{index}/formal')
                vector_operation = function[1] if function[0] == 'prim' and function[1] in VECTOR_OPERATIONS else None
                if vector_operation:
                    expected, result = VECTOR_OPERATIONS[vector_operation]
                    if len(arguments) != len(expected):
                        self.issue('vector-shape', owner, path, 'Vector primitive arity mismatch')
                    for index, (wanted, actual) in enumerate(zip(expected, arguments)):
                        if not vector_signature_matches(wanted, self.expression_rep(actual)):
                            self.issue('vector-shape', owner, f'{path}/arguments/{index}', 'Exact vector primitive argument representation required')
                        self.compare_shapes(wanted, self.expression_rep(actual), owner, f'{path}/arguments/{index}', component=True)
                    if not vector_signature_matches(result, proof):
                        self.issue('vector-shape', owner, path + '/rep', 'Exact vector primitive result representation required')
                    self.compare_shapes(result, proof, owner, path + '/rep', component=True)
                elif is_vector(proof):
                    self.issue('vector-boundary', owner, path, 'vector call result')
                self.walk(function, bound, owner, path + '/function', len(arguments), proof if tuple_constructor else None)
                for index, argument in enumerate(arguments):
                    if tuple_constructor and self.is_tuple(proof) and isinstance(proof.get('components'), list):
                        components = proof['components']
                        if index < len(components):
                            self.compare_shapes(components[index], self.expression_rep(argument), owner,
                                                f'{path}/arguments/{index}/rep', component=True)
                    if not vector_operation and (is_vector(self.expression_rep(argument)) or argument[0] == 'var' and is_vector(bound.get(argument[1]))):
                        self.issue('vector-boundary', owner, f'{path}/arguments/{index}', 'vector argument')
                    if not tuple_constructor and not vector_operation:
                        argument_rep = self.expression_rep(argument)
                        stored = bound.get(argument[1]) if argument[0] == 'var' else None
                        if self.is_tuple(argument_rep) or self.is_tuple(stored):
                            ordinary = function[0] not in ('prim', 'con') and not (
                                isinstance(target, dict) and '_join_arity' in target)
                            if not ordinary or not self.supported_empty_input(argument_rep):
                                self.issue('aggregate-boundary', owner, f'{path}/arguments/{index}', 'unboxed-tuple argument')
                            if (self.is_empty_tuple(argument_rep) and isinstance(flags, list) and
                                    index < len(flags) and flags[index] is not False):
                                self.issue('application-levity', owner, f'{path}/arguments/{index}', 'Empty tuple argument must be unlifted')
                    self.walk(argument, bound, owner, f'{path}/arguments/{index}')
            elif tag == 'let':
                recursive, group = expr[1], expr[2]
                if type(recursive) is not bool:
                    raise ValueError('Let recursive flag must be boolean')
                ids = self.binder_ids(group, owner, path + '/bindings')
                local = self.function_scope(group, bound, recursive)
                for index, binding in enumerate(group):
                    if not isinstance(binding, dict):
                        continue
                    if is_vector(binding.get('rep')):
                        self.issue('vector-boundary', owner, f'{path}/bindings/{index}', 'vector let binding')
                    if self.is_tuple(binding.get('rep')) and 'joinValueArity' not in binding:
                        self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-tuple let binding')
                    if 'joinValueArity' in binding:
                        captured = (self.free_variables(binding['expr']) - (ids if recursive else set())) & bound.keys()
                        if any(self.is_tuple(bound[key]) for key in captured):
                            self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-tuple join capture')
                        self.compare_shapes(self.expression_rep(expr), binding.get('joinResultRep'), owner,
                                            f'{path}/bindings/{index}/joinResultRep')
                    if recursive and binding.get('lifted') is False:
                        self.issue('recursive-unlifted', owner, f'{path}/bindings/{index}', binding.get('id'))
                    self.walk(binding.get('expr'), local if recursive else bound,
                              owner, f'{path}/bindings/{index}/rhs', join_prefix=binding.get('joinValueArity', 0))
                self.compare_shapes(self.expression_rep(expr), self.expression_rep(expr[3]), owner, path + '/body/rep')
                self.walk(expr[3], local, owner, path + '/body')
            elif tag == 'case':
                self.walk(expr[1], bound, owner, path + '/scrutinee')
                if not isinstance(expr[2], str):
                    raise ValueError('Case binder must be a string')
                metadata = expr[4] if len(expr) > 4 and isinstance(expr[4], dict) else {}
                binder_proof = metadata.get('binder', {}).get('rep', self.expression_rep(expr[1]))
                self.compare_shapes(binder_proof, self.expression_rep(expr[1]), owner, path + '/binder/rep')
                if self.is_tuple(binder_proof):
                    if len(expr[3]) != 1:
                        self.issue('aggregate-boundary', owner, path, 'unboxed-tuple requires one alternative')
                arm_proofs = [self.literal_rep(alt[3]) or self.expression_rep(alt[3]) for alt in expr[3]]
                floating = next((proof for proof in [self.expression_rep(expr), *arm_proofs]
                                 if isinstance(proof, dict) and proof.get('kind') in ('float', 'double')), None)
                if floating is not None:
                    # Validation is order independent; unknown arms do not gain
                    # a floating result proof merely because another arm has one.
                    for index, proof in enumerate(arm_proofs):
                        self.compare_shapes(floating, proof, owner, f'{path}/alternatives/{index}/body/rep')
                for index, alternative in enumerate(expr[3]):
                    altpath = f'{path}/alternatives/{index}'
                    kind, value, ids, rhs = alternative[:4]
                    records = []
                    if len(alternative) > 4:
                        metadata = alternative[4]
                        records = metadata.get('binders') if isinstance(metadata, dict) else None
                        self.binder_ids(records, owner, altpath + '/binders')
                        if not isinstance(records, list) or [b.get('id') for b in records if isinstance(b, dict)] != ids:
                            self.issue('alternative-binder-metadata', owner, altpath, 'Pattern metadata must preserve binder order')
                    if not isinstance(ids, list) or any(not isinstance(i, str) for i in ids):
                        raise ValueError('Alternative binders must be strings')
                    if kind == 'data':
                        self.constructor(value, owner, altpath, False, len(ids), binder_proof)
                        if self.is_tuple(binder_proof):
                            if self.constructors.get(value, {}).get('kind') != 'unboxed-tuple':
                                self.issue('aggregate-shape', owner, altpath, 'Tuple scrutinee requires a tuple alternative')
                            components = binder_proof.get('components')
                            if isinstance(components, list):
                                if len(ids) != len(components) or not isinstance(records, list) or len(records) != len(components):
                                    self.issue('aggregate-shape', owner, altpath, 'Tuple alternative components mismatch')
                                else:
                                    for field, (component, record) in enumerate(zip(components, records)):
                                        self.compare_shapes(component, record.get('rep') if isinstance(record, dict) else None,
                                                            owner, f'{altpath}/binders/{field}/rep', component=True)
                    elif kind == 'lit':
                        self.literal(value[0], value[1], owner, altpath + '/literal')
                        if value[0] in ('float', 'double'):
                            self.issue('alternative-kind', owner, altpath, 'Floating literal alternatives are invalid GHC Core')
                    elif kind != 'default':
                        self.issue('alternative-kind', owner, altpath, kind)
                    if self.is_tuple(binder_proof) and (kind not in ('data', 'default') or kind == 'default' and ids):
                        self.issue('aggregate-shape', owner, altpath, 'Invalid tuple alternative')
                    self.compare_shapes(self.expression_rep(expr), self.expression_rep(rhs), owner, altpath + '/body/rep')
                    local = bound | {expr[2]: binder_proof} | dict.fromkeys(ids)
                    if isinstance(records, list):
                        local.update(self.binder_scope(records))
                    self.walk(rhs, local, owner, altpath + '/body')
            elif tag == 'con':
                self.constructor(expr[1], owner, path, True, expr[2], tuple_result or self.expression_rep(expr))
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
                expression = self.bindings[key].get('expr')
                if (isinstance(expression, list) and len(expression) > 1 and expression[0] == 'lam' and
                        isinstance(expression[1], list)):
                    if any(self.is_tuple(b.get('rep')) for b in expression[1] if isinstance(b, dict)):
                        self.issue('aggregate-boundary', key, '/entry', 'unboxed-tuple host argument')
                if (isinstance(expression, list) and expression and expression[0] == 'lam' and
                        len(expression) > 3 and isinstance(expression[3], dict) and
                        self.is_tuple(expression[3].get('resultRep'))):
                    self.issue('aggregate-boundary', key, '/entry', 'unboxed-tuple host result')
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
            self.walk(binding.get('expr'), {}, key, '/expr', join_prefix=binding.get('joinValueArity', 0))
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
