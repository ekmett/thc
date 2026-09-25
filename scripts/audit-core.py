#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Audit the syntactically reachable exported Core, without evaluating it.

Every alternative and local RHS of each reachable global is checked. Lexical
scope is exact: recursive RHSs see their group; nonrecursive RHSs do not; case
binders scope over alternatives only. An accepted report is a static capability
check, not a proof that arbitrary inputs terminate or avoid a Haskell error.
"""
import argparse
from collections import deque
import json
import core_data_tags
import core_md5_foreign
import core_managed_files
import core_original_foreign
import core_package_manifest
from pathlib import Path
import sys

from core_sums import is_sum, contains_sum, lifted_payload, proof_error as sum_proof_error, constructor_tag as sum_constructor_tag
from core_vectors import OPERATIONS as VECTOR_OPERATIONS, is_vector, proof_error as vector_proof_error, signature_matches as vector_signature_matches
from core_tuple_inputs import contains_tuple, proof_error as tuple_input_proof_error
from core_vector_memory import OPERATIONS as VECTOR_MEMORY_OPERATIONS, read_case as vector_read_case, validate_direct as validate_vector_memory


# The identical checked-in resource is packaged in the JVM runtime jar.
SCALAR_SIGNATURES = json.loads((Path(__file__).resolve().parent.parent /
    'src/main/resources/thc/scalar-primop-signatures.json').read_text())['primitives']
POLYGLOT_ABI = json.loads((Path(__file__).resolve().parent.parent /
    'src/main/resources/thc/polyglot-abi.json').read_text())


# Original implicit RTS dependencies, not host exceptions or fabricated dictionaries.
ARITHMETIC_EXCEPTIONS = {name: 'ghc-internal:GHC.Internal.Exception.Type.' + payload for name, payload in (
    ('raiseDivZero#', 'divZeroException'), ('raiseOverflow#', 'overflowException'),
    ('raiseUnderflow#', 'underflowException'))}


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
        self.foreign_calls = []
        self.literals = {}
        self.used_constructors = {}
        self.reachable = []
        self.chains = {}
        self.queue = deque()
        self.linked_foreign = {}
        self.archive_bindings = {}
        self.retained_exports = []
        for source, module in modules:
            foreign = module.get('foreign')
            stubs = foreign.get('stubs') if isinstance(foreign, dict) else None
            registration = ((isinstance(foreign, dict) and bool(foreign.get('files'))) or
                            (isinstance(stubs, dict) and
                             bool(stubs.get('initializers') or stubs.get('finalizers'))))
            retained = None
            if module.get('schema') == 2 and registration and 'staticForeignExportRegistration' in module:
                try:
                    retained = core_package_manifest.managed_registration(module)
                except (ValueError, KeyError, TypeError) as error:
                    self.issue('module-format', None, source, str(error))
            if retained:
                self.retained_exports.extend(retained)
            linked = (type(module.get('schema')) is int and module['schema'] == 2 and
                      'foreignLink' in module and core_package_manifest.linked_foreign(module))
            archive = core_package_manifest.foreign_execution_issue(module) if not linked and not retained else None
            if archive:
                try:
                    core_package_manifest.validate_archive_only_foreign(module)
                except ValueError as error:
                    self.issue('module-format', None, source, str(error))
            if linked:
                for symbol in module['foreignLink']['symbols']:
                    key = (module['foreignLink']['unit'], symbol)
                    if key in self.linked_foreign:
                        self.issue('module-format', None, source, 'Duplicate linked CAPI symbol ' + symbol)
                    self.linked_foreign[key] = module['foreignLink']
            if (type(module.get('schema')) is not int or
                    (module['schema'] != 1 and not linked and not archive and not retained) or
                    module.get('ghc') != '9.14.1' or
                    ('foreign' in module and not linked and not archive and not retained) or (registration and not retained)):
                self.issue('module-format', None, source,
                           archive or
                           'Requires executable Core schema 1 / GHC 9.14.1 without foreign artifacts')
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
                    if archive and not registration:
                        self.archive_bindings[key] = (source, archive)
            for constructor in module.get('constructors', []):
                key = constructor.get('id')
                if not isinstance(key, str):
                    self.issue('constructor-id', None, source, 'Constructor lacks a string id')
                # GHC can alpha-rename quantified variables in the diagnostic
                # pretty-printed type between modules. Keep every runtime and
                # exporter metadata field exact, including unknown future keys.
                elif key in self.constructors and {
                        name: value for name, value in self.constructors[key].items() if name != 'type'
                    } != {name: value for name, value in constructor.items() if name != 'type'}:
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
            elif aggregate == 'unboxed-sum':
                error = sum_proof_error(rep)
                if error:
                    self.issue('aggregate-representation', owner, path, 'unboxed-sum: ' + error)
                alternatives = rep.get('alternatives')
                if isinstance(alternatives, list):
                    for index, alternative in enumerate(alternatives):
                        self.representation(alternative, owner, f'{path}/alternatives/{index}')
            elif not isinstance(rep.get('components'), list):
                self.issue('aggregate-representation', owner, path, aggregate + ': missing exact components')
            else:
                physical = []
                for index, component in enumerate(rep['components']):
                    self.representation(component, owner, f'{path}/components/{index}')
                    if contains_sum(component):
                        self.issue('aggregate-representation', owner, path, 'unboxed-tuple: sum component unsupported')
                    registers = component.get('primReps') if isinstance(component, dict) else None
                    if not isinstance(registers, list):
                        self.issue('aggregate-representation', owner, path, aggregate + ': unresolved component')
                    else:
                        physical.extend(registers)
                        if 'aggregate' not in component and not self.supported_vector(component, 'tuple-fields') and (component.get('kind') == 'unknown' or
                                any(r not in self.cap['aggregateFieldRepresentations'] for r in registers)):
                            self.issue('aggregate-representation', owner, path, aggregate + ': unsupported component')
                        if ('aggregate' not in component and registers == ['AddrRep'] and
                                (component.get('kind') != 'address' or component.get('evaluated') is not True)):
                            self.issue('aggregate-representation', owner, path, aggregate + ': address needs an evaluated AddrRep carrier')
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
            if is_sum(binding.get('joinResultRep')):
                self.issue('aggregate-boundary', owner, path, 'unboxed-sum join result')
            if is_vector(binding.get('joinResultRep')) and not self.supported_vector(binding['joinResultRep'], 'join-results'):
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
        if kind == 'function-addr' and value not in self.cap.get('functionLabels', []):
            self.issue('unsupported-literal', owner, path, f'uncertified C function label {value}')
        if kind == 'bignat':
            if not isinstance(value, str) or not value or any(c not in '0123456789' for c in value) or len(value) > 1 and value[0] == '0':
                self.issue('invalid-literal-value', owner, path, 'bignat requires canonical nonnegative decimal')
        if kind == 'string-bytes':
            if not isinstance(value, str) or len(value) % 2 or any(c not in '0123456789abcdefABCDEF' for c in value):
                self.issue('invalid-literal-value', owner, path, 'string-bytes must contain pairs of hexadecimal digits')
        if kind == 'null-addr' and value != '0':
            self.issue('invalid-literal-value', owner, path, 'null-addr must be exactly 0')
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
    def is_tuple_value(cls, rep):
        # A local join can return a tuple without denoting one. Its binding is
        # a tail-call target; a reference to it captures control flow, not fields.
        return cls.is_tuple(rep) and '_join_arity' not in rep

    @staticmethod
    def is_vector_value(rep):
        return is_vector(rep) and '_join_arity' not in rep

    @classmethod
    def is_empty_tuple(cls, rep):
        return (cls.is_tuple(rep) and rep.get('kind') == 'unknown' and
                rep.get('components') == [] and rep.get('primReps') == [])

    def supported_empty_input(self, rep):
        return self.is_empty_tuple(rep) and 'empty-unboxed-tuple' in self.cap.get('aggregateInputs', [])

    def supported_empty_join_input(self, rep):
        return self.is_empty_tuple(rep) and 'empty-unboxed-tuple' in self.cap.get('aggregateJoinInputs', [])

    def supported_tuple_input(self, rep):
        return (self.supported_empty_input(rep) or
                'unboxed-tuple' in self.cap.get('aggregateInputs', []) and tuple_input_proof_error(rep,
                    allow_vectors='tuple-fields' in self.cap.get('vectorTransport', [])) is None)

    def supported_vector(self, rep, boundary):
        return (boundary in self.cap.get('vectorTransport', []) and is_vector(rep) and
                vector_proof_error(rep) is None and any(
                    rep['vector'] == {key: shape[key] for key in ('lanes', 'element')}
                    for shape in self.cap.get('vectorRepresentations', [])))

    @classmethod
    def shape(cls, rep):
        """Logical tuple boundaries are significant even at zero/one register.

        Boxed leaf kinds and WHNF evidence may refine independently; primitive
        representation names (including boxed liftedness) must still agree.
        """
        if not isinstance(rep, dict):
            return None
        if is_sum(rep):
            alternatives = rep.get('alternatives')
            if not isinstance(alternatives, list):
                return None
            children = tuple(cls.shape(alternative) for alternative in alternatives)
            return None if None in children else ('sum', children)
        if cls.is_tuple(rep):
            components = rep.get('components')
            if not isinstance(components, list):
                return None
            children = tuple(cls.shape(component) for component in components)
            return None if None in children else ('tuple', children)
        registers = rep.get('primReps')
        if is_vector(rep):
            return ('vector', tuple(registers)) if isinstance(registers, list) else None
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
        if not component and not (self.is_tuple(expected) or self.is_tuple(actual) or is_sum(expected) or is_sum(actual) or is_vector(expected) or is_vector(actual)):
            return
        left, right = self.shape(expected), self.shape(actual)
        if left is None or right is None or left != right:
            self.issue('aggregate-shape', owner, path, 'Conflicting or missing logical aggregate representation proofs')

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
        if expression[0] == 'con':
            constructor = self.constructors.get(expression[1], {})
            arity = constructor.get('arity')
            if constructor.get('kind', 'boxed') == 'boxed' and type(arity) is int and arity >= 0:
                fields = constructor.get('fieldTypes')
                # Heap constructors cannot consume logical tuples, even through
                # aliases/PAPs. Legacy missing fields stay unknown, never inferred
                # as aggregate signatures from their physical register width.
                return fields if isinstance(fields, list) and len(fields) == arity else [None] * arity
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

    def known_function_signature(self, expression, seen=frozenset()):
        """Resolve an exact continuation result through known lambda/global/PAP heads."""
        if not isinstance(expression, list) or not expression:
            return None
        if expression[0] == 'lam':
            metadata = expression[3] if len(expression) > 3 and isinstance(expression[3], dict) else {}
            return [binder.get('rep') for binder in expression[1]], metadata.get('resultRep')
        if expression[0] == 'var':
            key = expression[1]
            if key not in seen:
                return self.known_function_signature(self.bindings.get(key, {}).get('expr'), seen | {key})
        if expression[0] == 'app':
            signature = self.known_function_signature(expression[1], seen)
            if signature is not None and len(expression[2]) < len(signature[0]):
                return signature[0][len(expression[2]):], signature[1]
        return None

    @staticmethod
    def literal_rep(expr):
        # Signed/unsigned 8-, 16- and 32-bit literals retain exact identity.
        # Other legacy literal forms keep their historical carrier-only proof.
        if not isinstance(expr, list) or not expr:
            return None
        if expr[0] == 'void':
            return dict(kind='void', evaluated=True)
        if expr[0] == 'lit' and len(expr) >= 3:
            narrow = {'int8': 'Int8Rep', 'word8': 'Word8Rep', 'int16': 'Int16Rep', 'word16': 'Word16Rep', 'int32': 'Int32Rep', 'word32': 'Word32Rep'}
            if expr[1] == 'bignat':
                return dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
            if expr[1] in narrow:
                return dict(kind='long', primReps=[narrow[expr[1]]], evaluated=True)
            if expr[1] in ('null-addr', 'function-addr'):
                return dict(kind='address', primReps=['AddrRep'], evaluated=True)
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
                'aggregate' not in proof and not is_vector(proof)):
            return intrinsic
        return proof if proof is not None else intrinsic

    def effective_rep(self, expr, bound):
        proof = self.expression_rep(expr)
        if isinstance(expr, list) and len(expr) > 1 and expr[0] == 'var':
            stored = bound.get(expr[1]) if expr[1] in bound else self.bindings.get(expr[1], {}).get('rep')
            if proof is None or (isinstance(proof, dict) and proof.get('kind') == 'unknown' and
                    proof.get('primReps') is None and 'aggregate' not in proof and not is_vector(proof)):
                return stored
        return proof

    def known_result(self, expr, seen=frozenset()):
        if not isinstance(expr, list) or not expr:
            return None
        if expr[0] == 'lam' and len(expr) > 3 and isinstance(expr[3], dict):
            return expr[3].get('resultRep')
        if expr[0] == 'var' and len(expr) > 1 and isinstance(expr[1], str) and expr[1] not in seen:
            return self.known_result(self.bindings.get(expr[1], {}).get('expr'), seen | {expr[1]})
        if expr[0] == 'app' and len(expr) > 2 and isinstance(expr[2], list):
            try:
                formals = self.call_formals(expr[1], {})
            except (IndexError, KeyError, TypeError):
                return None  # walk() reports malformed applications in context.
            if formals is not None and len(expr[2]) < len(formals):
                return self.known_result(expr[1], seen)
        return self.expression_rep(expr)

    def tag_to_enum(self, expr, bound, owner, path):
        def reject(detail):
            self.issue('enum-family', owner, path, 'tagToEnum#: ' + detail)
        if self.cap.get('tagToEnum') != 'concrete-nullary-family':
            reject('capability disabled')
        args = expr[2]
        if len(args) != 1 or expr[3] != [False]:
            reject('exactly one unlifted Int# operand required')
            return
        actual = self.expression_rep(args[0])
        if args[0][0] == 'var':
            lexical = bound.get(args[0][1]) if args[0][1] in bound else self.bindings.get(args[0][1], {}).get('rep')
            if actual is None:
                actual = lexical
            elif isinstance(actual, dict) and isinstance(lexical, dict):
                # Match the lowered lexical value: unknown occurrence fields
                # refine from the binder, while present contradictory registers
                # and aggregate/vector identity remain visible to validation.
                effective = dict(lexical, **actual)
                if actual.get('kind') == 'unknown':
                    effective['kind'] = lexical.get('kind')
                if actual.get('primReps') is None:
                    effective['primReps'] = lexical.get('primReps')
                actual = effective
        def scalar(rep, kind, register):
            return isinstance(rep, dict) and rep.get('kind') == kind and rep.get('primReps') == [register] and 'aggregate' not in rep and not is_vector(rep)
        if not scalar(actual, 'long', 'IntRep'):
            reject('exact IntRep operand required')
        if not scalar(self.expression_rep(expr), 'data', 'BoxedRep (Just Lifted)'):
            reject('exact lifted data result required')
        family = expr[6].get('enumFamily') if len(expr) > 6 and isinstance(expr[6], dict) else None
        if not isinstance(family, dict) or set(family) != {'typeConstructor', 'constructors'} or not isinstance(family.get('typeConstructor'), str) or not family['typeConstructor']:
            reject('missing/malformed concrete family')
            return
        ids = family['constructors']
        if not isinstance(ids, list) or not ids or any(not isinstance(k, str) or not k for k in ids) or len(set(ids)) != len(ids):
            reject('invalid ordered constructors')
            return
        for key, con in self.constructors.items():
            declared = con.get('enumFamily')
            if (isinstance(declared, dict) and declared.get('typeConstructor') == family['typeConstructor'] and
                    (declared != family or key not in ids)):
                reject('contradictory supplied family record ' + key)
        for index, key in enumerate(ids):
            con = self.constructors.get(key, {})
            if (con.get('enumFamily') != family or con.get('kind') != 'boxed' or
                    type(con.get('arity')) is not int or con['arity'] != 0 or
                    type(con.get('tag')) is not int or con['tag'] != index + 1 or
                    any(con.get(field) != [] for field in ('fieldReps', 'fieldTypes', 'fieldLifted', 'strictFields'))):
                reject('contradictory/missing nullary constructor ' + key)
            else:
                self.constructor(key, owner, path + '/enumFamily', True, 0)

    def scalar_primitive(self, name, arguments, result, bound, owner, path):
        signature = SCALAR_SIGNATURES.get(name)
        if signature is None:
            return

        def check(expected, proof, position):
            if not isinstance(proof, dict) or proof.get('primReps') is None:
                return
            if proof.get('kind') == 'unknown' and not self.is_tuple(proof) and not is_sum(proof) and not is_vector(proof):
                return
            if self.is_tuple(proof) or is_sum(proof) or is_vector(proof) or proof.get('primReps') != [expected]:
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

    def original_stack_operand(self, expression, primitive, bound, index):
        """Check stored/intrinsic producers as well as the foreign occurrence proof."""
        check = core_original_foreign.validate_operand_binding
        core_original_foreign.require(isinstance(expression, list) and bool(expression), f'lowered operand {index}')
        tag = expression[0]
        if tag == 'var':
            key = expression[1]
            check(bound.get(key) if key in bound else self.bindings.get(key, {}).get('rep'),
                  primitive, f'stored operand {index}')
        elif tag in ('lit', 'void'):
            intrinsic = self.literal_rep(expression)
            core_original_foreign.require(intrinsic is not None, f'lowered operand {index}')
            check(intrinsic, primitive, f'lowered operand {index}')
        elif tag in ('lam', 'con') or tag == 'app' and expression[1][0] == 'con':
            # No accepted operand role is a closure, constructor, or aggregate.
            core_original_foreign.require(False, f'lowered operand {index}')
        elif tag == 'let':
            self.original_stack_operand(expression[3], primitive, bound | self.binder_scope(expression[2]), index)
        elif tag == 'case':
            metadata = expression[4] if len(expression) > 4 else {}
            core_original_foreign.require(isinstance(metadata, dict) and isinstance(metadata.get('binder', {}), dict),
                                          f'lowered operand {index}')
            local = bound | {expression[2]: metadata.get('binder', {}).get('rep', self.expression_rep(expression[1]))}
            for alternative in expression[3]:
                arm_metadata = alternative[4] if len(alternative) > 4 else {}
                core_original_foreign.require(isinstance(arm_metadata, dict), f'lowered operand {index}')
                records = arm_metadata.get('binders', [])
                self.original_stack_operand(alternative[3], primitive,
                                            local | dict.fromkeys(alternative[2]) | self.binder_scope(records), index)

    def polyglot_call(self, expr, bound, owner, path):
        """Accept closed managed ABI or exact saturated polyglot FCallId applications."""
        function, arguments = expr[1:3]
        metadata = expr[6] if len(expr) > 6 and isinstance(expr[6], dict) else {}
        call = metadata.get('foreignCall')
        if call is None:
            return False

        target = call.get('target') if isinstance(call, dict) else None
        symbol = target.get('symbol') if isinstance(target, dict) else None
        if isinstance(symbol, str) and symbol in core_original_foreign.OPERATIONS:
            try:
                core_original_foreign.validate(metadata, [core_original_foreign.raw_rep(arg) for arg in arguments],
                                               expr[3], core_original_foreign.raw_rep(expr))
                head_id = function[1] if isinstance(function, list) and len(function) > 1 else None
                defined = isinstance(head_id, str) and (head_id in bound or head_id in self.bindings)
                core_original_foreign.validate_head(function, defined)
                if (symbol in core_original_foreign.STACK_INFO or symbol in core_original_foreign.SEEK_CONSTANTS
                        or symbol in core_original_foreign.STAT_IMAGE
                        or symbol in core_original_foreign.GMP_SYMBOLS
                        or symbol in core_original_foreign.LIBDW_UNAVAILABLE
                        or symbol in core_original_foreign.TERMIOS_SYMBOLS
                        or symbol in (core_original_foreign.TCGETATTR_SYMBOL, core_original_foreign.TCSETATTR_SYMBOL)
                        or symbol in core_original_foreign.SIGSET_OPERATIONS
                        or symbol in ('getOrSetSystemEventThreadEventManagerStore',
                                      'getOrSetGHCConcSignalSignalHandlerStore')
                        or symbol in ('rts_setMainThread', 'rtsSupportsBoundThreads', 'lockFile', 'unlockFile', '__hscore_fstat', '__hscore_open', 'dup', 'dup2', 'fdReady', 'localeEncoding', 'hs_iconv_open', 'hs_iconv_close', 'hs_iconv',
                                      'base_strerror_r')):
                    for index, (argument, primitive) in enumerate(zip(arguments, core_original_foreign.OPERATIONS[symbol][2])):
                        self.original_stack_operand(argument, primitive, bound, index)
                if symbol == core_original_foreign.STACK_CLONE:
                    state = arguments[0]
                    if state[0] == 'var':
                        key = state[1]
                        stored = bound.get(key) if key in bound else self.bindings.get(key, {}).get('rep')
                        core_original_foreign.validate_state_binding(stored)
                    # Lowering knows these producers cannot yield State even if
                    # an occurrence falsely claims the zero-width certificate.
                    core_original_foreign.require(state[0] not in ('lit', 'lam', 'con'), 'lowered State argument')
                if symbol not in self.cap.get('managedForeignCalls', []):
                    raise ValueError('Original foreign-call capability disabled')
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path))
            except ValueError as error:
                self.issue('foreign-call', owner, path, str(error))
            return True
        if isinstance(symbol, str) and symbol.startswith(core_managed_files.PREFIX):
            try:
                core_managed_files.validate(metadata, [self.expression_rep(arg) for arg in arguments],
                                            expr[3], self.expression_rep(expr))
                head_id = function[1] if isinstance(function, list) and len(function) > 1 else None
                defined = isinstance(head_id, str) and (head_id in bound or head_id in self.bindings)
                core_managed_files.validate_head(function, defined)
                if symbol not in self.cap.get('managedForeignCalls', []):
                    raise ValueError('Managed file foreign-call capability disabled')
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path))
            except ValueError as error:
                self.issue('foreign-call', owner, path, str(error))
            return True
        if isinstance(symbol, str) and symbol in core_md5_foreign.OPERATIONS:
            try:
                core_md5_foreign.validate(metadata, [self.expression_rep(arg) for arg in arguments],
                                          expr[3], self.expression_rep(expr))
                head = self.expression_rep(function)
                if (len(function) != 3 or function[0] != 'var' or not isinstance(function[1], str) or
                        not function[1] or function[1] in bound or function[1] in self.bindings or
                        not isinstance(head, dict) or set(head) != {'kind', 'primReps', 'evaluated'} or
                        head['kind'] != 'closure' or head['primReps'] != ['BoxedRep (Just Lifted)'] or
                        head['evaluated'] is not True):
                    raise ValueError('Unresolved declared foreign variable required')
                if symbol not in self.cap.get('managedForeignCalls', []):
                    raise ValueError('Managed MD5 foreign-call capability disabled')
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path))
            except ValueError as error:
                self.issue('foreign-call', owner, path, str(error))
            return True

        link = self.linked_foreign.get((target.get('unit'), symbol)) if isinstance(target, dict) else None
        if isinstance(link, dict) and symbol in link['symbols']:
            try:
                if (function[0] != 'var' or function[1] in bound or function[1] in self.bindings or
                        not isinstance(call, dict) or set(call) != {'schema', 'target', 'convention',
                            'safety', 'arity', 'suppliedArity', 'argumentReps', 'resultRep'} or
                        call['schema'] != 1 or target != {'kind': 'static', 'symbol': symbol,
                            'unit': link['unit'], 'isFunction': True} or
                        call['convention'] != 'capi' or call['safety'] != 'unsafe'):
                    raise ValueError('Linked CAPI call lacks its exact original FCallId')
                declared = call['argumentReps']
                abi = {entry['symbol']: entry['kind'] for entry in link['abi']}
                zero = abi[symbol] == 'clock-id'
                wanted = [None] if zero else ['Word64Rep', 'AddrRep', None]
                output = 'Word64Rep' if zero else 'Int32Rep'
                def exact(rep, primitive):
                    kind = 'void' if primitive is None else 'address' if primitive == 'AddrRep' else 'long'
                    return (isinstance(rep, dict) and set(rep) == {'kind', 'primReps', 'evaluated'} and
                            rep['kind'] == kind and rep['primReps'] == ([] if primitive is None else [primitive]) and
                            type(rep['evaluated']) is bool)
                def tuple_rep(rep):
                    parts = rep.get('components') if isinstance(rep, dict) else None
                    return (isinstance(parts, list) and len(parts) == 2 and
                            rep.get('aggregate') == 'unboxed-tuple' and rep.get('kind') == 'unknown' and
                            rep.get('primReps') == [output] and exact(parts[0], None) and
                            exact(parts[1], output))
                if (call['arity'] != len(wanted) or call['suppliedArity'] != len(wanted) or
                        len(arguments) != len(wanted) or not isinstance(declared, list) or
                        len(declared) != len(wanted) or expr[3] != [False] * len(wanted) or
                        any(not exact(proof, primitive) or not exact(self.expression_rep(argument), primitive)
                            for proof, argument, primitive in zip(declared, arguments, wanted)) or
                        not tuple_rep(call['resultRep']) or not tuple_rep(self.expression_rep(expr))):
                    raise ValueError('Linked CAPI call has wrong actual or declared primitive ABI')
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path,
                                               linkedModule=link['module']))
            except (TypeError, KeyError, ValueError) as error:
                self.issue('foreign-call', owner, path, str(error))
            return True

        def reject(detail):
            self.issue('foreign-call', owner, path, detail)
            return False

        if function[0] != 'var' or function[1] in bound or function[1] in self.bindings:
            return reject('Foreign descriptor requires a direct external FCallId head')
        if not isinstance(call, dict) or call.get('schema') != 1:
            return reject('Missing GHC foreign-call schema 1 evidence')
        target = call.get('target')
        if not isinstance(target, dict) or target.get('kind') != 'static' or target.get('isFunction') is not True:
            return reject('Requires a static function target')
        symbol = target.get('symbol')
        javascript_prefix = 'thc_javascript_v1_'
        if ('intrinsic' in call or 'javascriptSource' in call or
                isinstance(symbol, str) and symbol.startswith(javascript_prefix)):
            if call.get('intrinsic') != 'javascript-v1' or not isinstance(call.get('javascriptSource'), str):
                return reject('Missing exact javascript-v1 source evidence')
            source = call['javascriptSource']
            try:
                encoded = source.encode('utf-8').hex()
            except UnicodeEncodeError:
                return reject('JavaScript source is not valid UTF-8')
            if symbol != javascript_prefix + encoded:
                return reject('JavaScript target does not encode the declared source')
            if call.get('convention') != 'ccall' or call.get('safety') not in ('safe', 'unsafe'):
                return reject('JavaScript import requires a synchronous ccall declaration')
            declared = call.get('argumentReps')
            if not isinstance(declared, list) or not declared or len(declared) != len(arguments):
                return reject('JavaScript import lacks exact argument declarations')
            def exact_js(rep, register):
                if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                    return False
                return (rep.get('kind') == {'IntRep': 'long', 'DoubleRep': 'double',
                                            'State# RealWorld': 'void'}[register] and
                        rep.get('primReps') == ([] if register == 'State# RealWorld' else [register]))
            register_names = [value.get('primReps') if isinstance(value, dict) else None for value in declared]
            if (register_names[-1] != [] or any(value not in (["IntRep"], ["DoubleRep"])
                                                 for value in register_names[:-1])):
                return reject('JavaScript import requires Int/Double arguments followed by State#')
            if (type(call.get('arity')) is not int or call['arity'] != len(declared) or
                    type(call.get('suppliedArity')) is not int or call['suppliedArity'] != len(declared)):
                return reject('JavaScript import must be exactly saturated')
            for index, (argument, declared_rep) in enumerate(zip(arguments, declared)):
                register = 'State# RealWorld' if index == len(declared) - 1 else declared_rep['primReps'][0]
                if not exact_js(declared_rep, register) or not exact_js(self.effective_rep(argument, bound), register):
                    return reject(f'JavaScript argument {index} lacks exact {register} proof')
            if expr[3] != [False] * len(declared):
                return reject('JavaScript FFI arguments must be unlifted')
            result = call.get('resultRep')
            actual_result = self.expression_rep(expr)
            def exact_result(proof):
                if not isinstance(proof, dict) or proof.get('aggregate') != 'unboxed-tuple' or proof.get('kind') != 'unknown':
                    return None
                components = proof.get('components')
                if not isinstance(components, list) or len(components) not in (1, 2) or not exact_js(components[0], 'State# RealWorld'):
                    return None
                if len(components) == 1:
                    return 'void' if proof.get('primReps') == [] else None
                for register in ('IntRep', 'DoubleRep'):
                    if exact_js(components[1], register) and proof.get('primReps') == [register]:
                        return register
                return None
            output = exact_result(result)
            if output is None or exact_result(actual_result) != output:
                return reject('JavaScript result requires exact State# singleton or State#/Int#/Double# tuple')
            self.foreign_calls.append(dict(symbol=symbol, javascriptSource=source,
                                           result=output, owner=owner, path=path))
            return True
        spec = POLYGLOT_ABI['operations'].get(symbol)
        if spec is None:
            return reject('Unsupported foreign target ' + repr(symbol))
        if call.get('convention') != POLYGLOT_ABI['convention'] or call.get('safety') != POLYGLOT_ABI['safety']:
            return reject('GHC calling convention or safety differs from polyglot ABI')
        declared = spec['arguments']
        if (type(call.get('arity')) is not int or call['arity'] != len(declared) or
                type(call.get('suppliedArity')) is not int or call['suppliedArity'] != len(declared) or
                len(arguments) != len(declared)):
            return reject('Foreign call must be exactly saturated at declared arity')
        def exact(rep, register):
            if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                return False
            kinds = {'AddrRep': 'address', 'IntRep': 'long',
                     'BoxedRep (Just Lifted)': 'object', 'State# RealWorld': 'void'}
            return (rep.get('kind') == kinds[register] and
                    rep.get('primReps') == ([] if register == 'State# RealWorld' else [register]))
        declared_reps = call.get('argumentReps')
        if (not isinstance(declared_reps, list) or len(declared_reps) != len(declared) or
                any(not exact(rep, register) for rep, register in zip(declared_reps, declared))):
            return reject('GHC declared argument representations differ from polyglot ABI')
        for index, (argument, register) in enumerate(zip(arguments, declared)):
            actual = self.effective_rep(argument, bound)
            if not exact(actual, register):
                return reject(f'Argument {index} lacks exact {register} proof')
        expected_lifted = [register == 'BoxedRep (Just Lifted)' for register in declared]
        if expr[3] != expected_lifted:
            return reject('Argument levity differs from GHC foreign signature')
        result = call.get('resultRep')
        components = result.get('components') if isinstance(result, dict) else None
        wanted = spec['result']
        if (not isinstance(components, list) or len(components) != len(wanted) or
                result.get('aggregate') != 'unboxed-tuple' or result.get('kind') != 'unknown' or
                any(not exact(rep, register) for rep, register in zip(components, wanted)) or
                result.get('primReps') != [r for rep in components for r in rep['primReps']] or
                self.shape(self.expression_rep(expr)) != self.shape(result)):
            return reject('GHC declared State# tuple result differs from polyglot ABI')
        self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path))
        return True

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
        if info.get('kind') == 'unboxed-sum' and 'unboxed-sum' in self.cap.get('aggregateResults', []):
            if not is_sum(tuple_rep) or sum_proof_error(tuple_rep):
                self.issue('aggregate-representation', owner, path, 'unboxed-sum: exact instantiated constructor result required')
            try:
                sum_constructor_tag(info, arity)
            except ValueError as error:
                self.issue('constructor-arity', owner, path, str(error))
            return
        if info.get('kind') == 'unboxed-tuple' and self.is_tuple(tuple_rep) and 'unboxed-tuple' in self.cap.get('aggregateResults', []):
            components = tuple_rep.get('components')
            if not isinstance(components, list) or len(components) != expected:
                self.issue('aggregate-representation', owner, path, 'unboxed-tuple: constructor components mismatch')
            # Global tuple workers are representation-polymorphic. The instantiated
            # application/case proof supplies fields; generic worker nulls do not.
            return
        if info.get('kind', 'boxed') not in self.cap['constructorKinds']:
            self.issue('constructor-kind', owner, path, f'{key}: {info.get("kind")}')
        fields = info.get('fieldTypes')
        if isinstance(fields, list) and any(contains_sum(field) for field in fields):
            self.issue('aggregate-boundary', owner, path, 'unboxed-sum heap field')
        if isinstance(fields, list) and any(contains_tuple(field) for field in fields):
            self.issue('aggregate-boundary', owner, path, 'unboxed-tuple heap field')
        reps = info.get('fieldReps')
        if not isinstance(reps, list) or len(reps) != expected:
            self.issue('constructor-representations', owner, path, f'{key}: missing/misaligned fieldReps')
        else:
            for index, registers in enumerate(reps):
                if not isinstance(registers, list) or len(registers) > 1:
                    self.issue('constructor-field-representation', owner, path, f'{key}[{index}]: {registers!r}')
                elif registers and isinstance(registers[0], str) and registers[0].startswith('VecRep '):
                    types, lifted, strict = info.get('fieldTypes'), info.get('fieldLifted'), info.get('strictFields')
                    proof = types[index] if isinstance(types, list) and len(types) == expected else None
                    if not self.supported_vector(proof, 'heap-fields'):
                        self.issue('vector-boundary', owner, path, f'{key}[{index}]: vector heap field')
                    if (not isinstance(proof, dict) or proof.get('primReps') != registers or
                            proof.get('evaluated') is not True or
                            not isinstance(lifted, list) or len(lifted) != expected or lifted[index] is not False or
                            not isinstance(strict, list) or len(strict) != expected or type(strict[index]) is not bool):
                        self.issue('constructor-field-representation', owner, path,
                                   f'{key}[{index}]: vector field requires exact unlifted/evaluated fieldTypes')
                    if proof is not None:
                        self.representation(proof, owner, path + f'/fieldTypes/{index}')
                elif registers and registers[0] not in self.cap['fieldRepresentations']:
                    self.issue('constructor-field-representation', owner, path, f'{key}[{index}]: {registers[0]}')
                if (isinstance(fields, list) and len(fields) == expected and is_vector(fields[index]) and
                        (not isinstance(registers, list) or registers != fields[index].get('primReps'))):
                    self.issue('constructor-field-representation', owner, path,
                               f'{key}[{index}]: vector field proof disagrees with fieldReps')
        # AddrRep always denotes the managed LiteralAddress carrier, including
        # legacy constructor records without the optional precise fieldTypes.
        for index, registers in enumerate(reps if isinstance(reps, list) else []):
            if registers != ['AddrRep']:
                continue
            lifted = info.get('fieldLifted')
            if 'fieldLifted' in info and (not isinstance(lifted, list) or len(lifted) != expected or lifted[index] is not False):
                self.issue('constructor-field-representation', owner, path, f'{key}[{index}]: address field must be unlifted')
            if 'fieldTypes' in info:
                types, strict = info['fieldTypes'], info.get('strictFields')
                if (not isinstance(types, list) or len(types) != expected or
                        not isinstance(strict, list) or len(strict) != expected or type(strict[index]) is not bool or
                        not isinstance(lifted, list) or len(lifted) != expected or lifted[index] is not False):
                    self.issue('constructor-field-representation', owner, path, f'{key}[{index}]: malformed address field metadata')
                    continue
                proof = types[index]
                self.representation(proof, owner, path + f'/fieldTypes/{index}')
                if (not isinstance(proof, dict) or proof.get('kind') != 'address' or
                        proof.get('primReps') != ['AddrRep'] or proof.get('evaluated') is not True):
                    self.issue('constructor-field-representation', owner, path, f'{key}[{index}]: address field lacks its exact evaluated carrier')
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

    def walk(self, expr, bound, owner, path, primitive_arity=None, tuple_result=None, join_prefix=0, sum_payload=False):
        if not isinstance(expr, list) or not expr or not isinstance(expr[0], str):
            self.issue('malformed-expression', owner, path, repr(expr)[:160])
            return
        tag = expr[0]
        try:
            read = vector_read_case(expr, self.constructors)
            if read is not None:
                # Exempt only these two checked structural sites, never a shared
                # proof object or an equal map reused at another ABI boundary.
                exempt = {('case', 'binder', 'rep'), ('producer', 'rep')}
                def metadata_proofs(value, site):
                    if isinstance(value, dict):
                        for key, child in value.items():
                            child_site = site + (key,)
                            if key in ('rep', 'resultRep', 'joinResultRep') and child_site not in exempt:
                                self.representation(child, owner, path + '/' + '/'.join(map(str, child_site)))
                            metadata_proofs(child, child_site)
                    elif isinstance(value, list):
                        for index, child in enumerate(value):
                            metadata_proofs(child, site + (index,))
                metadata_proofs(expr[4], ('case',))
                metadata_proofs(expr[1][6], ('producer',))
                alternative = expr[3][0]
                metadata_proofs(alternative[4], ('pattern',))
                self.binder_ids(alternative[4]['binders'], owner, path + '/alternatives/0/binders')
                self.constructor(alternative[1], owner, path + '/alternatives/0', False, 2, expr[4]['binder']['rep'])
                self.walk(expr[1][1], bound, owner, path + '/scrutinee/function', 3)
                for index, argument in enumerate(read['arguments']):
                    self.walk(argument, bound, owner, f'{path}/scrutinee/arguments/{index}')
                declared, actual = self.expression_rep(expr), self.expression_rep(read['body'])
                known = all(isinstance(rep, dict) and isinstance(rep.get('primReps'), list)
                            for rep in (declared, actual))
                self.compare_shapes(declared, actual, owner, path + '/alternatives/0/body/rep', component=known)
                self.walk(read['body'], bound | self.binder_scope(alternative[4]['binders']), owner,
                          path + '/alternatives/0/body')
                return
            self.expression_metadata(expr, owner, path)
            if tag == 'var':
                if not isinstance(expr[1], str):
                    raise ValueError('Variable id must be a string')
                if expr[1] not in bound:
                    self.reference(expr[1], owner, path)
                    stored = self.bindings.get(expr[1], {}).get('rep')
                    if self.is_tuple(stored) or self.is_tuple(self.expression_rep(expr)) or is_sum(stored) or is_sum(self.expression_rep(expr)):
                        self.compare_shapes(stored, self.effective_rep(expr, bound) if sum_payload or is_sum(stored) or self.is_tuple(stored) else self.expression_rep(expr), owner, path + '/rep')
                else:
                    proof = bound[expr[1]]
                    if isinstance(proof, dict) and '_join_arity' in proof:
                        if (primitive_arity if primitive_arity is not None else 0) != proof['_join_arity']:
                            self.issue('join-arity', owner, path, 'Local join must be exactly saturated at its logical arity')
                        if proof['_join_arity'] == 0 and primitive_arity is None:
                            proof = proof['_join_result']
                    self.compare_shapes(proof, self.effective_rep(expr, bound) if sum_payload or is_sum(proof) or self.is_tuple(proof) else self.expression_rep(expr), owner, path + '/rep')
            elif tag == 'lit':
                self.literal(expr[1], expr[2], owner, path)
                self.compare_shapes(self.expression_rep(expr), self.literal_rep(expr), owner, path + '/rep')
                if expr[1] in ('int8', 'word8', 'int16', 'word16', 'int32', 'word32'):
                    proof, intrinsic = self.expression_rep(expr), self.literal_rep(expr)
                    if (not isinstance(proof, dict) or proof.get('kind') != 'long' or
                            proof.get('primReps') != intrinsic['primReps'] or 'aggregate' in proof or is_vector(proof)):
                        self.issue('scalar-representation', owner, path + '/rep', 'Narrow literal requires exact signed/unsigned identity')
                if expr[1] == 'bignat':
                    proof = self.expression_rep(expr)
                    if (not isinstance(proof, dict) or proof.get('kind') != 'object' or
                            proof.get('primReps') != ['BoxedRep (Just Unlifted)'] or 'aggregate' in proof or is_vector(proof)):
                        self.issue('scalar-representation', owner, path + '/rep', 'BigNat literal requires exact unlifted ByteArray# identity')
                if expr[1] == 'function-addr':
                    raw = expr[3].get('rep') if len(expr) > 3 and isinstance(expr[3], dict) else None
                    if (not isinstance(raw, dict) or raw.get('kind') != 'address' or
                            raw.get('primReps') != ['AddrRep'] or 'aggregate' in raw or is_vector(raw)):
                        self.issue('scalar-representation', owner, path + '/rep',
                                   'Original C function label requires explicit exact AddrRep proof')
            elif tag == 'void':
                self.compare_shapes(self.expression_rep(expr), self.literal_rep(expr), owner, path + '/rep')
            elif tag == 'lam':
                ids = self.binder_ids(expr[1], owner, path + '/binders')
                for index, binder in enumerate(expr[1]):
                    if is_sum(binder.get('rep')):
                        self.issue('aggregate-boundary', owner, path, 'unboxed-sum formal argument')
                    if is_vector(binder.get('rep')) and not self.supported_vector(binder['rep'],
                            'join-arguments' if index < join_prefix else 'arguments'):
                        self.issue('vector-boundary', owner, path, 'vector formal argument')
                    if is_vector(binder.get('rep')) and binder.get('lifted') is not False:
                        self.issue('application-levity', owner, path, 'Vector formal must be unlifted')
                    if self.is_tuple(binder.get('rep')) and not (
                            self.supported_empty_join_input(binder.get('rep')) if index < join_prefix else
                            self.supported_tuple_input(binder.get('rep'))):
                        self.issue('aggregate-boundary', owner, path, 'unboxed-tuple formal argument')
                    if self.is_tuple(binder.get('rep')) and binder.get('lifted') is not False:
                        self.issue('application-levity', owner, path, 'Tuple formal must be unlifted')
                captured = {key for key in (self.free_variables(expr[2]) - ids) & bound.keys()
                            if self.is_tuple_value(bound[key])}
                if any(is_sum(bound[key]) for key in (self.free_variables(expr[2]) - ids) & bound.keys()):
                    self.issue('aggregate-boundary', owner, path, 'unboxed-sum capture')
                # The consumed join lambda branches within its enclosing frame;
                # a residual lambda still allocates an ordinary closure.
                local_join_prefix = join_prefix > 0 and join_prefix == len(expr[1])
                vector_captures = {key for key in (self.free_variables(expr[2]) - ids) & bound.keys()
                                   if self.is_vector_value(bound[key])}
                if vector_captures and not (
                        all(self.supported_vector(bound[key], 'join-captures' if local_join_prefix else 'captures')
                            for key in vector_captures)):
                    self.issue('vector-boundary', owner, path, 'vector capture')
                if captured and not (local_join_prefix and
                                     'unboxed-tuple' in self.cap.get('aggregateJoinCaptures', []) and
                                     all(self.supported_tuple_input(bound[key]) for key in captured)):
                    self.issue('aggregate-boundary', owner, path, 'unboxed-tuple capture')
                metadata = expr[3] if len(expr) > 3 and isinstance(expr[3], dict) else {}
                if any(is_vector(rep) and not self.supported_vector(rep,
                        'join-results' if local_join_prefix else 'results') for rep in
                        (metadata.get('resultRep'), self.expression_rep(expr[2]))):
                    self.issue('vector-boundary', owner, path, 'vector function result')
                local = bound | self.binder_scope(expr[1])
                self.compare_shapes(metadata.get('resultRep'), self.effective_rep(expr[2], local)
                                    if self.is_tuple(metadata.get('resultRep')) else self.expression_rep(expr[2]), owner, path + '/resultRep')
                self.walk(expr[2], local, owner, path + '/body')
            elif tag == 'app':
                arguments = expr[2]
                flags = expr[3] if len(expr) > 3 else None
                if not isinstance(arguments, list):
                    raise ValueError('Application arguments must be an array')
                if not isinstance(flags, list) or len(flags) != len(arguments) or any(type(f) is not bool for f in flags):
                    self.issue('application-levity', owner, path, 'Missing/invalid argument representation flags')
                function = expr[1]
                sum_constructor = function[0] == 'con' and self.constructors.get(function[1], {}).get('kind') == 'unboxed-sum'
                data_tag = function[0] == 'prim' and function[1] in core_data_tags.OPERATIONS
                if data_tag:
                    if self.cap.get('dataToTag') != 'concrete-algebraic-family-64':
                        self.issue('data-tag-family', owner, path, 'capability disabled')
                    actual = self.expression_rep(arguments[0]) if len(arguments) == 1 else None
                    if len(arguments) == 1 and arguments[0][0] == 'var':
                        key = arguments[0][1]
                        lexical = bound.get(key) if key in bound else self.bindings.get(key, {}).get('rep')
                        if actual is None:
                            actual = lexical
                        elif isinstance(actual, dict) and isinstance(lexical, dict):
                            effective = dict(lexical, **actual)
                            if actual.get('kind') in ('unknown', 'object'): effective['kind'] = lexical.get('kind')
                            if actual.get('primReps') is None: effective['primReps'] = lexical.get('primReps')
                            if actual.get('primReps') == ['BoxedRep Nothing'] and lexical.get('primReps') in (
                                    ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)']):
                                effective['primReps'] = lexical['primReps']
                            actual = effective
                    try:
                        for key in core_data_tags.validate(expr, actual, self.constructors):
                            self.constructor(key, owner, path + '/dataToTagFamily', False, self.constructors[key]['arity'])
                    except ValueError as error:
                        self.issue('data-tag-family', owner, path, str(error))
                tuple_constructor = function[0] == 'con' and self.constructors.get(function[1], {}).get('kind') == 'unboxed-tuple'
                proof = self.expression_rep(expr)
                if sum_constructor:
                    try:
                        sum_constructor_tag(self.constructors.get(function[1]), len(arguments))
                    except ValueError as error:
                        self.issue('constructor-arity', owner, path, str(error))

                enum_application = function[0] == 'prim' and function[1] == 'tagToEnum#'
                if enum_application:
                    self.tag_to_enum(expr, bound, owner, path)
                arithmetic_exception = function[0] == 'prim' and function[1] in ARITHMETIC_EXCEPTIONS
                if arithmetic_exception:
                    if (len(arguments) != 1 or flags != [False] or
                            not self.is_empty_tuple(self.expression_rep(arguments[0]))):
                        self.issue('primitive-representation', owner, path,
                                   function[1] + ': expected one exact unlifted empty tuple argument')
                    if contains_sum(proof):
                        self.issue('aggregate-boundary', owner, path, 'arithmetic exception sum result')
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
                        if (not isinstance(rep, dict) or set(rep) != {'kind', 'primReps', 'evaluated'} or
                                type(rep.get('evaluated')) is not bool):
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
                    if (len(arguments) != len(expected) or any(type(flag) is not bool for flag in flags) or
                            flags != [r == 'element' for r in expected] or
                            any(not array_role(self.expression_rep(a), r) for a, r in zip(arguments, expected))):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact Array arguments required')
                    result = array['result']
                    if isinstance(result, list):
                        fields = proof.get('components') if isinstance(proof, dict) else None
                        valid = (self.is_tuple(proof) and proof.get('kind') == 'unknown' and
                                 set(proof) == {'kind', 'primReps', 'evaluated', 'aggregate', 'components'} and
                                 type(proof.get('evaluated')) is bool and
                                 isinstance(fields, list) and len(fields) == len(result) and
                                 all(array_role(rep, role) for rep, role in zip(fields, result)) and
                                 proof.get('primReps') == [r for field in fields for r in field['primReps']])
                    else:
                        valid = array_role(proof, result)
                    if not valid:
                        self.issue('primitive-representation', owner, path, function[1] + ': exact Array result required')
                if function[0] == 'prim' and function[1] == 'touch#':
                    def touch_scalar(rep):
                        return (isinstance(rep, dict) and set(rep) == {'kind', 'primReps', 'evaluated'} and
                                type(rep['evaluated']) is bool)
                    def touch_state(rep):
                        return touch_scalar(rep) and rep['kind'] == 'void' and rep['primReps'] == []
                    actual = [self.expression_rep(a) for a in arguments]
                    valid = (len(actual) == 2 and touch_scalar(actual[0]) and
                             actual[0]['kind'] in ('object', 'data', 'closure') and
                             actual[0]['primReps'] in (['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)']) and
                             touch_state(actual[1]) and touch_state(proof) and
                             isinstance(flags, list) and all(type(flag) is bool for flag in flags) and
                             flags == [actual[0]['primReps'] == ['BoxedRep (Just Lifted)'], False])
                    if not valid:
                        self.issue('primitive-representation', owner, path,
                                   'touch#: exact reference, State input and bare State result required')
                    for index, argument in enumerate(arguments):
                        stored = ((bound.get(argument[1]) if argument[1] in bound else self.bindings.get(argument[1], {}).get('rep'))
                                  if argument[0] == 'var' else self.literal_rep(argument))
                        if isinstance(stored, dict):
                            reps = stored.get('primReps')
                            kinds = ('unknown', 'object', 'data', 'closure') if index == 0 else ('unknown', 'void')
                            if (stored.get('kind', 'unknown') not in kinds or
                                    isinstance(reps, list) and reps != ['BoxedRep Nothing'] and
                                    self.shape(stored) != self.shape(actual[index])):
                                self.issue('primitive-representation', owner, path,
                                           'touch#: argument contradicts its stored/intrinsic proof')
                if function[0] == 'prim' and function[1] == 'keepAlive#':
                    def kept_reference(rep):
                        return (isinstance(rep, dict) and 'aggregate' not in rep and not is_vector(rep) and
                                rep.get('kind') in ('object', 'data', 'closure') and rep.get('primReps') in (
                                    ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)']))
                    def state_rep(rep):
                        return (isinstance(rep, dict) and 'aggregate' not in rep and not is_vector(rep) and
                                rep.get('kind') == 'void' and rep.get('primReps') == [])
                    actual = [self.expression_rep(a) for a in arguments]
                    valid = (len(actual) == 3 and kept_reference(actual[0]) and state_rep(actual[1]) and
                             kept_reference(actual[2]) and actual[2].get('kind') in ('object', 'closure') and
                             actual[2].get('primReps') == ['BoxedRep (Just Lifted)'] and
                             flags == [actual[0]['primReps'] == ['BoxedRep (Just Lifted)'], False, True])
                    if not valid:
                        self.issue('primitive-representation', owner, path, 'keepAlive#: exact reference, State and continuation required')
                    if (not isinstance(proof, dict) or is_vector(proof) or
                            ('aggregate' not in proof and (proof.get('primReps') is None or proof.get('kind') == 'unknown'))):
                        self.issue('primitive-representation', owner, path, 'keepAlive#: exact supported result required')
                    signature = self.known_function_signature(arguments[2]) if len(arguments) == 3 else None
                    if signature is not None:
                        formals, returned = signature
                        if not formals or not state_rep(formals[0]):
                            self.issue('primitive-representation', owner, path, 'keepAlive#: State continuation input required')
                        elif len(formals) == 1:
                            self.compare_shapes(proof, returned, owner, path + '/continuation-result', component=True)
                        elif not kept_reference(proof) or proof.get('primReps') != ['BoxedRep (Just Lifted)']:
                            self.issue('primitive-representation', owner, path, 'keepAlive#: partial continuation returns a lifted function')
                if function[0] == 'prim' and function[1] in ('raiseIO#', 'catch#',
                        'unmaskAsyncExceptions#', 'maskAsyncExceptions#', 'maskUninterruptible#', 'getMaskingState#'):
                    def exception_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        if role == 'state':
                            return rep.get('kind') == 'void' and rep.get('primReps') == []
                        if role == 'int':
                            return rep.get('kind') == 'long' and rep.get('primReps') == ['IntRep']
                        return ((rep.get('kind') == 'closure' if role == 'closure' else
                                 rep.get('kind') in ('object', 'data', 'closure')) and
                                rep.get('primReps') == ['BoxedRep (Just Lifted)'])
                    roles = {'raiseIO#': ('boxed', 'state'), 'catch#': ('closure', 'closure', 'state'),
                             'unmaskAsyncExceptions#': ('closure', 'state'),
                             'maskAsyncExceptions#': ('closure', 'state'),
                             'maskUninterruptible#': ('closure', 'state'),
                             'getMaskingState#': ('state',)}[function[1]]
                    actual = [self.expression_rep(argument) for argument in arguments]
                    if (len(actual) != len(roles) or flags != [role != 'state' for role in roles] or
                            any(not exception_role(rep, role) for rep, role in zip(actual, roles))):
                        self.issue('primitive-representation', owner, path,
                                   function[1] + ': exact lifted exception and State# arguments required')
                    fields = proof.get('components') if isinstance(proof, dict) else None
                    output = 'int' if function[1] == 'getMaskingState#' else 'boxed'
                    output_reps = ['IntRep'] if output == 'int' else ['BoxedRep (Just Lifted)']
                    if not (self.is_tuple(proof) and proof.get('kind') == 'unknown' and
                            isinstance(fields, list) and len(fields) == 2 and
                            exception_role(fields[0], 'state') and exception_role(fields[1], output) and
                            proof.get('primReps') == output_reps):
                        self.issue('primitive-representation', owner, path,
                                   function[1] + ': exact State#/result tuple required')
                thread = self.cap.get('managedThreadPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if thread is not None:
                    def thread_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'int':
                            return kind == 'long' and reps == ['IntRep']
                        if role == 'threadId':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        if role == 'action':
                            return kind == 'closure' and reps == ['BoxedRep (Just Lifted)']
                        return role == 'payload' and kind in ('object', 'data', 'closure') and reps == ['BoxedRep (Just Lifted)']
                    expected = thread['arguments']
                    actual = [self.expression_rep(argument) for argument in arguments]
                    expected_flags = [role in ('action', 'payload') for role in expected]
                    if (len(actual) != len(expected) or flags != expected_flags or
                            any(not thread_role(rep, role) for rep, role in zip(actual, expected))):
                        self.issue('primitive-representation', owner, path,
                                   function[1] + ': exact thread action, ThreadId#, payload and State# arguments required')
                    for argument, role in zip(arguments, expected):
                        stored = (bound.get(argument[1]) if argument[1] in bound else
                                  self.bindings.get(argument[1], {}).get('rep')) if argument[0] == 'var' else None
                        if isinstance(stored, dict) and stored.get('primReps') not in (None, ['BoxedRep Nothing']) and not thread_role(stored, role):
                            self.issue('primitive-representation', owner, path,
                                       function[1] + ': binding metadata contradicts thread operand role')
                    result = thread['result']
                    if isinstance(result, list):
                        fields = proof.get('components') if isinstance(proof, dict) else None
                        valid = (self.is_tuple(proof) and proof.get('kind') == 'unknown' and
                                 isinstance(fields, list) and len(fields) == len(result) and
                                 all(thread_role(rep, role) for rep, role in zip(fields, result)) and
                                 proof.get('primReps') == [rep for field in fields for rep in field['primReps']])
                    else:
                        valid = thread_role(proof, result)
                    if not valid:
                        self.issue('primitive-representation', owner, path,
                                   function[1] + ': exact State#/ThreadId# result required')
                if function[0] == 'prim' and function[1] == 'getCurrentCCS#':
                    def scalar(rep, kind, reps):
                        return (isinstance(rep, dict) and 'aggregate' not in rep and not is_vector(rep) and
                                rep.get('kind') == kind and rep.get('primReps') == reps)
                    actual = [self.expression_rep(argument) for argument in arguments]
                    fields = proof.get('components') if isinstance(proof, dict) else None
                    if not (len(actual) == 2 and flags == [True, False] and
                            isinstance(actual[0], dict) and 'aggregate' not in actual[0] and
                            not is_vector(actual[0]) and actual[0].get('kind') in ('object', 'data', 'closure') and
                            actual[0].get('primReps') == ['BoxedRep (Just Lifted)'] and
                            scalar(actual[1], 'void', []) and self.is_tuple(proof) and
                            proof.get('kind') == 'unknown' and isinstance(fields, list) and len(fields) == 2 and
                            scalar(fields[0], 'void', []) and scalar(fields[1], 'address', ['AddrRep']) and
                            proof.get('primReps') == ['AddrRep']):
                        self.issue('primitive-representation', owner, path,
                                   'getCurrentCCS#: exact lifted dummy, State# and State#/Addr# tuple required')
                if function[0] == 'prim' and function[1] == 'noDuplicate#':
                    def exact_state(rep):
                        return (isinstance(rep, dict) and 'aggregate' not in rep and not is_vector(rep) and
                                rep.get('kind') == 'void' and rep.get('primReps') == [])
                    if (len(arguments) != 1 or flags != [False] or
                            not exact_state(self.expression_rep(arguments[0])) or not exact_state(proof)):
                        self.issue('primitive-representation', owner, path,
                                   'noDuplicate#: exact State# input and result required')
                bytearray_primitive = (self.cap.get('managedByteArrayPrimitives', {}).get(function[1]) or
                                       self.cap.get('managedPinnedMemoryPrimitives', {}).get(function[1])) if function[0] == 'prim' else None
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
                    for index, (argument, required) in enumerate(zip(arguments, expected)):
                        stored = None
                        if argument[0] == 'var':
                            stored = (bound.get(argument[1]) if argument[1] in bound else
                                      self.bindings.get(argument[1], {}).get('rep'))
                        elif argument[0] in ('lit', 'void'):
                            stored = self.literal_rep(argument)
                        # Unknown metadata may refine from the exact occurrence;
                        # known lexical/intrinsic facts cannot be relabelled by it.
                        if isinstance(stored, dict) and (
                                'aggregate' in stored or is_vector(stored) or
                                stored.get('kind') not in (None, 'unknown', required['kind']) or
                                stored.get('primReps') is not None and stored['primReps'] != required['primReps'] and
                                not (stored['primReps'] == ['BoxedRep Nothing'] and
                                     required['primReps'] == ['BoxedRep (Just Unlifted)'])):
                            self.issue('primitive-representation', owner, path + f'/args/{index}',
                                       function[1] + ': stored ByteArray operand contradicts its required representation')
                    if not exact(proof, bytearray_primitive['result']):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact ByteArray result required')
                weak = self.cap.get('managedWeakPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if weak is not None:
                    def weak_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or 'vector' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'weak':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        if role == 'flag':
                            return kind == 'long' and reps == ['IntRep']
                        if role == 'address':
                            return kind == 'address' and reps == ['AddrRep']
                        if role == 'action':
                            return kind in ('object', 'closure') and reps == ['BoxedRep (Just Lifted)']
                        return role == 'boxed' and kind in ('object', 'data', 'closure') and reps in (
                            ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])
                    actual = [self.expression_rep(argument) for argument in arguments]
                    expected = weak['arguments']
                    expected_flags = [isinstance(rep, dict) and rep.get('primReps') == ['BoxedRep (Just Lifted)'] for rep in actual]
                    if (len(actual) != len(expected) or not isinstance(flags, list) or
                            any(type(flag) is not bool for flag in flags) or flags != expected_flags or
                            any(not weak_role(rep, role) for rep, role in zip(actual, expected))):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact Weak arguments required')
                    for argument, rep, role in zip(arguments, actual, expected):
                        stored = (bound.get(argument[1]) if argument[1] in bound else
                                  self.bindings.get(argument[1], {}).get('rep')) if argument[0] == 'var' else None
                        registers = stored.get('primReps') if isinstance(stored, dict) else None
                        if registers == ['BoxedRep Nothing'] and isinstance(rep, dict) and rep.get('primReps') in (
                                ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)']):
                            stored = dict(stored, primReps=rep['primReps'])
                        if isinstance(registers, list) and (self.shape(stored) != self.shape(rep) or
                                stored.get('kind') != 'unknown' and not weak_role(stored, role)):
                            self.issue('primitive-representation', owner, path,
                                       function[1] + ': Weak argument contradicts its binding proof')
                    fields = proof.get('components') if isinstance(proof, dict) else None
                    output = weak['result']
                    if not (self.is_tuple(proof) and proof.get('kind') == 'unknown' and
                            isinstance(fields, list) and len(fields) == len(output) and
                            all(weak_role(rep, role) for rep, role in zip(fields, output)) and
                            proof.get('primReps') == [r for field in fields for r in field['primReps']]):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact Weak result required')
                    signature = self.known_function_signature(arguments[2]) if function[1] == 'mkWeak#' and len(arguments) == 4 else None
                    if signature is not None:
                        inputs, result = signature
                        fields = result.get('components') if isinstance(result, dict) else None
                        if not (len(inputs) == 1 and weak_role(inputs[0], 'state') and
                                self.is_tuple(result) and result.get('kind') == 'unknown' and
                                isinstance(fields, list) and len(fields) == 2 and
                                weak_role(fields[0], 'state') and weak_role(fields[1], 'boxed') and
                                fields[1].get('primReps') == ['BoxedRep (Just Lifted)'] and
                                result.get('primReps') == fields[1]['primReps']):
                            self.issue('primitive-representation', owner, path,
                                       'mkWeak#: finalizer requires State# -> (# State#, lifted value #)')
                mutvar = self.cap.get('managedMutVarPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                stable_ptr = self.cap.get('managedStablePtrPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if stable_ptr is not None:
                    def stable_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'address':
                            return kind == 'address' and reps == ['AddrRep']
                        if role == 'int':
                            return kind == 'long' and reps == ['IntRep']
                        return role == 'lifted' and kind in ('object', 'data', 'closure') and reps == ['BoxedRep (Just Lifted)']
                    actual = [self.expression_rep(argument) for argument in arguments]
                    expected = stable_ptr['arguments']
                    expected_flags = [role == 'lifted' for role in expected]
                    if len(actual) != len(expected) or flags != expected_flags or any(
                            not stable_role(rep, role) for rep, role in zip(actual, expected)):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact StablePtr# arguments required')
                    output = stable_ptr['result']
                    if isinstance(output, list):
                        fields = proof.get('components') if isinstance(proof, dict) else None
                        valid = self.is_tuple(proof) and isinstance(fields, list) and len(fields) == 2 and all(
                            stable_role(rep, role) for rep, role in zip(fields, output)) and proof.get('primReps') == fields[1]['primReps']
                    else:
                        valid = stable_role(proof, output)
                    if not valid:
                        self.issue('primitive-representation', owner, path, function[1] + ': exact StablePtr# result required')
                if mutvar is not None:
                    def role_matches(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'mutvar':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        if role == 'function':
                            return kind == 'closure' and reps == ['BoxedRep (Just Lifted)']
                        if role == 'lifted':
                            return kind in ('object', 'data', 'closure') and reps == ['BoxedRep (Just Lifted)']
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
                            role_matches(rep, role) for rep, role in zip(fields, result)) and proof.get('primReps') == [
                                item for field in fields[1:] for item in field.get('primReps', [])]
                    else:
                        valid = role_matches(proof, result)
                    if not valid:
                        self.issue('primitive-representation', owner, path, function[1] + ': exact MutVar result required')
                mvar = self.cap.get('managedMVarPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if mvar is not None:
                    def mvar_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or 'vector' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'mvar':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        if role == 'flag':
                            return kind == 'long' and reps == ['IntRep']
                        return role == 'boxed' and kind in ('object', 'data', 'closure') and reps in (
                            ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])
                    actual = [self.expression_rep(argument) for argument in arguments]
                    expected = mvar['arguments']
                    expected_flags = [isinstance(rep, dict) and rep.get('primReps') == ['BoxedRep (Just Lifted)'] for rep in actual]
                    if (len(actual) != len(expected) or not isinstance(flags, list) or
                            any(type(flag) is not bool for flag in flags) or flags != expected_flags or
                            any(not mvar_role(rep, role) for rep, role in zip(actual, expected))):
                        self.issue('primitive-representation', owner, path, function[1] + ': exact MVar arguments required')
                    # An occurrence cannot manufacture the MVar role from a
                    # contradictory concrete local/global binding proof.
                    for argument, rep, role in zip(arguments, actual, expected):
                        stored = (bound.get(argument[1]) if argument[1] in bound else
                                  self.bindings.get(argument[1], {}).get('rep')) if argument[0] == 'var' else None
                        registers = stored.get('primReps') if isinstance(stored, dict) else None
                        if registers == ['BoxedRep Nothing'] and isinstance(rep, dict) and rep.get('primReps') in (
                                ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)']):
                            stored = dict(stored, primReps=rep['primReps'])
                        if isinstance(registers, list) and (
                                self.shape(stored) != self.shape(rep) or
                                stored.get('kind') != 'unknown' and not mvar_role(stored, role)):
                            self.issue('primitive-representation', owner, path,
                                       function[1] + ': MVar argument contradicts its binding proof')
                    result = mvar['result']
                    if isinstance(result, list):
                        fields = proof.get('components') if isinstance(proof, dict) else None
                        valid = (self.is_tuple(proof) and proof.get('kind') == 'unknown' and
                                 isinstance(fields, list) and len(fields) == len(result) and
                                 all(mvar_role(rep, role) for rep, role in zip(fields, result)) and
                                 proof.get('primReps') == [r for field in fields for r in field['primReps']])
                    else:
                        valid = mvar_role(proof, result)
                    if not valid:
                        self.issue('primitive-representation', owner, path, function[1] + ': exact MVar result required')
                target = bound.get(function[1]) if function[0] == 'var' else None
                if isinstance(target, dict) and '_join_result' in target:
                    self.compare_shapes(target['_join_result'], proof, owner, path + '/rep')
                formals = self.call_formals(function, bound)
                if formals is not None:
                    for index, (formal, actual) in enumerate(zip(formals, arguments)):
                        actual_proof = self.effective_rep(actual, bound)
                        if (self.is_tuple(formal) or self.is_tuple(actual_proof) or is_sum(formal) or is_sum(actual_proof)
                                or is_vector(formal) or is_vector(actual_proof)):
                            self.compare_shapes(formal, actual_proof, owner,
                                                f'{path}/arguments/{index}/formal')
                vector_operation = function[1] if function[0] == 'prim' and function[1] in VECTOR_OPERATIONS else None
                vector_memory = function[1] if function[0] == 'prim' and function[1] in VECTOR_MEMORY_OPERATIONS else None
                if vector_memory:
                    validate_vector_memory(vector_memory, arguments, flags, proof)
                if vector_operation:
                    expected, result = VECTOR_OPERATIONS[vector_operation]
                    if not isinstance(flags, list) or len(flags) != len(arguments) or any(flag is not False for flag in flags):
                        self.issue('vector-shape', owner, path, 'Vector primitive operands must be unlifted')
                    if len(arguments) != len(expected):
                        self.issue('vector-shape', owner, path, 'Vector primitive arity mismatch')
                    for index, (wanted, actual) in enumerate(zip(expected, arguments)):
                        if not vector_signature_matches(wanted, self.expression_rep(actual)):
                            self.issue('vector-shape', owner, f'{path}/arguments/{index}', 'Exact vector primitive argument representation required')
                        self.compare_shapes(wanted, self.expression_rep(actual), owner, f'{path}/arguments/{index}', component=True)
                    if not vector_signature_matches(result, proof):
                        self.issue('vector-shape', owner, path + '/rep', 'Exact vector primitive result representation required')
                    self.compare_shapes(result, proof, owner, path + '/rep', component=True)
                elif is_vector(proof) and not vector_memory and not (function[0] not in ('prim', 'con') and
                        self.supported_vector(proof, 'join-results' if isinstance(target, dict) and '_join_arity' in target else 'results')):
                    self.issue('vector-boundary', owner, path, 'vector call result')
                if enum_application or data_tag:
                    self.expression_metadata(function, owner, path + '/function')
                    self.primitives.setdefault(function[1], []).append(dict(self.location(owner, path), arity=len(arguments)))
                elif self.polyglot_call(expr, bound, owner, path):
                    # The descriptor was emitted for this direct GHC FCallId,
                    # and the runtime links this exact versioned symbol.
                    self.expression_metadata(function, owner, path + '/function')
                else:
                    self.walk(function, bound, owner, path + '/function', len(arguments), proof if tuple_constructor or sum_constructor else None)
                for index, argument in enumerate(arguments):
                    if sum_constructor and is_sum(proof) and sum_proof_error(proof) is None:
                        try:
                            selected = sum_constructor_tag(self.constructors.get(function[1]), len(arguments)) - 1
                            expected = proof['alternatives'][selected]
                            self.compare_shapes(expected, self.effective_rep(argument, bound), owner, f'{path}/arguments/{index}/rep', component=True)
                            if not isinstance(flags, list) or index >= len(flags) or flags[index] is not lifted_payload(expected):
                                self.issue('application-levity', owner, path, 'Sum payload levity mismatch')
                        except ValueError as error:
                            self.issue('constructor-arity', owner, path, str(error))
                    if not sum_constructor and is_sum(self.effective_rep(argument, bound)):
                        self.issue('aggregate-boundary', owner, f'{path}/arguments/{index}', 'unboxed-sum argument')
                    if tuple_constructor and self.is_tuple(proof) and isinstance(proof.get('components'), list):
                        components = proof['components']
                        if index < len(components):
                            self.compare_shapes(components[index], self.effective_rep(argument, bound), owner,
                                                f'{path}/arguments/{index}/rep', component=True)
                    if not vector_operation and not vector_memory and (is_vector(self.expression_rep(argument)) or argument[0] == 'var' and is_vector(bound.get(argument[1]))):
                        actual = self.effective_rep(argument, bound)
                        join = isinstance(target, dict) and '_join_arity' in target
                        boxed_constructor = function[0] == 'con' and self.constructors.get(function[1], {}).get('kind', 'boxed') == 'boxed'
                        boundary = ('tuple-fields' if tuple_constructor else 'heap-fields' if boxed_constructor else
                                    'join-arguments' if join else 'arguments')
                        if not ((tuple_constructor or boxed_constructor or function[0] != 'prim' and function[0] != 'con')
                                and self.supported_vector(actual, boundary)):
                            self.issue('vector-boundary', owner, f'{path}/arguments/{index}', 'vector argument')
                        if not isinstance(flags, list) or index >= len(flags) or flags[index] is not False:
                            self.issue('application-levity', owner, f'{path}/arguments/{index}', 'Vector argument must be unlifted')
                    if not tuple_constructor and not sum_constructor and not vector_operation:
                        argument_rep = self.effective_rep(argument, bound)
                        stored = bound.get(argument[1]) if argument[0] == 'var' else None
                        if self.is_tuple(argument_rep) or self.is_tuple(stored):
                            join = isinstance(target, dict) and '_join_arity' in target
                            ordinary = function[0] not in ('prim', 'con') and not join
                            supported = self.supported_empty_join_input(argument_rep) if join else (
                                ordinary and self.supported_tuple_input(argument_rep) or
                                arithmetic_exception and self.is_empty_tuple(argument_rep))
                            if not supported:
                                self.issue('aggregate-boundary', owner, f'{path}/arguments/{index}', 'unboxed-tuple argument')
                            if (self.is_tuple(argument_rep) and isinstance(flags, list) and
                                    index < len(flags) and flags[index] is not False):
                                self.issue('application-levity', owner, f'{path}/arguments/{index}', 'Tuple argument must be unlifted')
                    self.walk(argument, bound, owner, f'{path}/arguments/{index}', sum_payload=sum_constructor or sum_payload)
            elif tag == 'let':
                recursive, group = expr[1], expr[2]
                if type(recursive) is not bool:
                    raise ValueError('Let recursive flag must be boolean')
                ids = self.binder_ids(group, owner, path + '/bindings')
                local = self.function_scope(group, bound, recursive)
                for index, binding in enumerate(group):
                    if not isinstance(binding, dict):
                        continue
                    if is_sum(binding.get('rep')) or is_sum(self.expression_rep(binding.get('expr'))):
                        self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-sum let binding')
                    if ('joinValueArity' not in binding and is_vector(binding.get('rep')) and
                            not self.supported_vector(binding['rep'], 'let-bindings')):
                        self.issue('vector-boundary', owner, f'{path}/bindings/{index}', 'vector let binding')
                    if is_vector(binding.get('rep')) and binding.get('lifted') is not False:
                        self.issue('application-levity', owner, f'{path}/bindings/{index}', 'Vector let binding must be unlifted')
                    tuple_value = self.is_tuple(binding.get('rep')) or self.is_tuple(
                        self.effective_rep(binding.get('expr'), local if recursive else bound))
                    if tuple_value and 'joinValueArity' not in binding:
                        self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-tuple let binding')
                    if 'joinValueArity' in binding:
                        captured = (self.free_variables(binding['expr']) - (ids if recursive else set())) & bound.keys()
                        if any(is_sum(bound[key]) for key in captured):
                            self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-sum join capture')
                        if any(self.is_vector_value(bound[key]) and not self.supported_vector(bound[key], 'join-captures')
                               for key in captured):
                            self.issue('vector-boundary', owner, f'{path}/bindings/{index}', 'vector join capture')
                        tuple_captures = [bound[key] for key in captured if self.is_tuple_value(bound[key])]
                        if tuple_captures and ('unboxed-tuple' not in self.cap.get('aggregateJoinCaptures', []) or
                                               not all(self.supported_tuple_input(rep) for rep in tuple_captures)):
                            self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-tuple join capture')
                        self.compare_shapes(self.expression_rep(expr), binding.get('joinResultRep'), owner,
                                            f'{path}/bindings/{index}/joinResultRep')
                    elif binding.get('lifted') is True:
                        captures = self.free_variables(binding['expr']) & bound.keys()
                        if any(self.is_vector_value(bound[key]) and not self.supported_vector(bound[key], 'captures')
                               for key in captures):
                            self.issue('vector-boundary', owner, f'{path}/bindings/{index}', 'vector thunk capture')
                    if recursive and binding.get('lifted') is False:
                        self.issue('recursive-unlifted', owner, f'{path}/bindings/{index}', binding.get('id'))
                    self.walk(binding.get('expr'), local if recursive else bound,
                              owner, f'{path}/bindings/{index}/rhs', join_prefix=binding.get('joinValueArity', 0), sum_payload=sum_payload)
                self.compare_shapes(self.expression_rep(expr), self.effective_rep(expr[3], local)
                                    if sum_payload or self.is_tuple(self.expression_rep(expr)) else self.expression_rep(expr[3]), owner, path + '/body/rep')
                self.walk(expr[3], local, owner, path + '/body', sum_payload=sum_payload)
            elif tag == 'case':
                self.walk(expr[1], bound, owner, path + '/scrutinee', sum_payload=sum_payload)
                if not isinstance(expr[2], str):
                    raise ValueError('Case binder must be a string')
                metadata = expr[4] if len(expr) > 4 and isinstance(expr[4], dict) else {}
                binder_proof = metadata.get('binder', {}).get('rep', self.expression_rep(expr[1]))
                self.compare_shapes(binder_proof, self.effective_rep(expr[1], bound)
                                    if sum_payload or is_sum(binder_proof) or self.is_tuple(binder_proof) else self.expression_rep(expr[1]), owner, path + '/binder/rep')
                if is_sum(binder_proof) and metadata.get('binder', {}).get('lifted') is not False:
                    self.issue('case-binder-metadata', owner, path, 'Sum case binder must be unlifted')
                if is_vector(binder_proof) and metadata.get('binder', {}).get('lifted') is not False:
                    self.issue('application-levity', owner, path, 'Vector case binder must be unlifted')
                if is_sum(binder_proof) and not expr[3]:
                    self.issue('aggregate-shape', owner, path, 'Empty sum case')
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
                sum_tags = set()
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
                    if is_sum(binder_proof):
                        if kind == 'data':
                            try:
                                selected = sum_constructor_tag(self.constructors.get(value), len(ids))
                                if selected in sum_tags:
                                    raise ValueError('Duplicate sum alternative tag')
                                sum_tags.add(selected)
                                alternatives = binder_proof.get('alternatives')
                                if (not isinstance(alternatives, list) or len(alternatives) != 2 or
                                        not isinstance(records, list) or len(records) != 1 or not isinstance(records[0], dict)):
                                    raise ValueError('Sum alternative requires one exact payload binder')
                                expected = alternatives[selected - 1]
                                self.compare_shapes(expected, records[0].get('rep'), owner, altpath + '/binders/0/rep', component=True)
                                if records[0].get('lifted') is not lifted_payload(expected):
                                    raise ValueError('Sum payload binder levity mismatch')
                            except ValueError as error:
                                self.issue('aggregate-shape', owner, altpath, str(error))
                        elif kind != 'default' or ids or records or 'default' in sum_tags:
                            self.issue('aggregate-shape', owner, altpath, 'Invalid or duplicate sum default alternative')
                        else:
                            sum_tags.add('default')
                    if kind == 'data':
                        self.constructor(value, owner, altpath, False, len(ids), binder_proof)
                        fields = self.constructors.get(value, {}).get('fieldTypes')
                        if (self.constructors.get(value, {}).get('kind') != 'unboxed-tuple' and
                                isinstance(fields, list) and any(is_vector(field) for field in fields) and
                                (not isinstance(records, list) or len(records) != len(fields))):
                            self.issue('alternative-binder-metadata', owner, altpath,
                                       'Vector constructor pattern requires every exact field binder')
                        # Polymorphic (#,#) fieldTypes are unknown. Its instantiated
                        # case-binder components below carry the exact vector proof.
                        if (self.constructors.get(value, {}).get('kind') != 'unboxed-tuple' and
                                isinstance(fields, list) and isinstance(records, list) and len(fields) == len(records)):
                            for field, (expected, record) in enumerate(zip(fields, records)):
                                actual = record.get('rep') if isinstance(record, dict) else None
                                if is_vector(expected) or is_vector(actual):
                                    self.compare_shapes(expected, actual, owner,
                                                        f'{altpath}/binders/{field}/rep', component=True)
                                    if not isinstance(record, dict) or record.get('lifted') is not False:
                                        self.issue('application-levity', owner, altpath,
                                                   f'Vector constructor binder {field} must be unlifted')
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
                        if value[0] == 'bignat':
                            self.issue('alternative-kind', owner, altpath, 'BigNat literal alternatives are invalid GHC Core')
                        if value[0] in ('float', 'double', 'function-addr'):
                            self.issue('alternative-kind', owner, altpath,
                                       'Floating and C function literal alternatives are invalid GHC Core')
                    elif kind != 'default':
                        self.issue('alternative-kind', owner, altpath, kind)
                    if self.is_tuple(binder_proof) and (kind not in ('data', 'default') or kind == 'default' and ids):
                        self.issue('aggregate-shape', owner, altpath, 'Invalid tuple alternative')
                    local = bound | {expr[2]: binder_proof} | dict.fromkeys(ids)
                    if isinstance(records, list):
                        local.update(self.binder_scope(records))
                    self.compare_shapes(self.expression_rep(expr), self.effective_rep(rhs, local)
                                        if sum_payload or self.is_tuple(self.expression_rep(expr)) else self.expression_rep(rhs), owner, altpath + '/body/rep')
                    self.walk(rhs, local, owner, altpath + '/body', sum_payload=sum_payload)
            elif tag == 'con':
                if self.constructors.get(expr[1], {}).get('kind') == 'unboxed-sum' and primitive_arity != 1:
                    self.issue('aggregate-boundary', owner, path, 'Sum constructor requires a saturated application')
                self.constructor(expr[1], owner, path, True, expr[2], tuple_result or self.expression_rep(expr))
            elif tag == 'prim':
                name = expr[1]
                self.primitives.setdefault(name, []).append(dict(self.location(owner, path), arity=primitive_arity))
                if name in ARITHMETIC_EXCEPTIONS:
                    self.reference(ARITHMETIC_EXCEPTIONS[name], owner, path + '/implicit-exception')
                expected = self.cap['primitives'].get(name)
                if expected is None:
                    self.issue('unsupported-primitive', owner, path, name)
                elif primitive_arity != expected:
                    self.issue('primitive-arity', owner, path, f'{name}: got {primitive_arity}, expected {expected}')
            else:
                self.issue('unsupported-node', owner, path, tag)
        except (IndexError, KeyError, TypeError, ValueError) as error:
            self.issue('malformed-expression', owner, path, str(error))

    def io_main_contract(self, key, expression, formals, result):
        """Only GHC's erased IO () state transformer may cross this host boundary."""
        def remaining_binders(expr, seen=frozenset()):
            if not isinstance(expr, list) or not expr:
                return None
            if expr[0] == 'lam' and len(expr) > 1 and isinstance(expr[1], list):
                return expr[1]
            if expr[0] == 'var' and len(expr) > 1 and isinstance(expr[1], str) and expr[1] not in seen:
                return remaining_binders(self.bindings.get(expr[1], {}).get('expr'), seen | {expr[1]})
            if expr[0] == 'app' and len(expr) > 2 and isinstance(expr[2], list):
                original = remaining_binders(expr[1], seen)
                # Drop logical operands, including zero-width ones, but never
                # infer a returned function's signature after saturation.
                if original is not None and len(expr[2]) < len(original):
                    return original[len(expr[2]):]
            return None
        remaining = remaining_binders(expression)
        binder = remaining[0] if isinstance(remaining, list) and len(remaining) == 1 else None
        state = formals[0] if isinstance(formals, list) and len(formals) == 1 else None
        components = result.get('components') if isinstance(result, dict) and result.get('aggregate') == 'unboxed-tuple' else None
        state_result = components[0] if isinstance(components, list) and len(components) == 2 else None
        unit_result = components[1] if isinstance(components, list) and len(components) == 2 else None
        if (self.bindings[key].get('type') != 'IO ()' or
                not isinstance(result, dict) or result.get('kind') != 'unknown' or
                result.get('primReps') != ['BoxedRep (Just Lifted)'] or
                not isinstance(binder, dict) or binder.get('type') != 'State# RealWorld' or
                not isinstance(state, dict) or state.get('kind') != 'void' or state.get('primReps') != [] or
                not isinstance(state_result, dict) or state_result.get('kind') != 'void' or state_result.get('primReps') != [] or
                not isinstance(unit_result, dict) or unit_result.get('primReps') != ['BoxedRep (Just Lifted)'] or
                unit_result.get('kind') not in ('data', 'object')):
            self.issue('io-main-boundary', key, '/entry', 'requires IO () with State# RealWorld -> (# State#, () #)')

    def run(self, entries, io_main=False):
        # Executable shutdown is another exact IO () root in the same package
        # closure. Each root receives the same boundary check below.
        roots = []
        for entry in entries:
            candidates = [entry] if entry in self.bindings else [k for k, b in self.bindings.items() if b.get('name') == entry]
            if len(candidates) != 1:
                self.issue('entry-resolution', None, entry, dict(candidates=candidates))
            else:
                key = candidates[0]
                roots.append(key)
                expression = self.bindings[key].get('expr')
                try:
                    formals = self.call_formals(expression, {})
                except (IndexError, KeyError, TypeError):
                    formals = None  # walk() reports malformed metadata in context.
                result = self.known_result(expression)
                if io_main:
                    self.io_main_contract(key, expression, formals, result)
                else:
                    if formals is not None and any(self.supported_vector(proof, 'arguments') for proof in formals):
                        self.issue('vector-boundary', key, '/entry', 'vector host argument')
                    if self.supported_vector(result, 'results'):
                        self.issue('vector-boundary', key, '/entry', 'vector host result')
                    if formals is not None and any(self.is_tuple(proof) for proof in formals):
                        self.issue('aggregate-boundary', key, '/entry', 'unboxed-tuple host argument')
                    if self.is_tuple(result):
                        self.issue('aggregate-boundary', key, '/entry', 'unboxed-tuple host result')
                if is_sum(self.known_result(expression)) or is_sum(self.bindings[key].get('rep')):
                    self.issue('aggregate-boundary', key, '/entry', 'unboxed-sum host result')
                if key not in self.chains:
                    self.chains[key] = [key]
                    self.queue.append(key)
        # Retention does not call an export. The current backends must still
        # lower its closure body, so unsupported retained bodies remain gaps.
        for key in self.retained_exports:
            if key not in self.chains:
                self.chains[key] = [key]
                self.queue.append(key)
        reported_archives = set()
        while self.queue:
            key = self.queue.popleft()
            self.reachable.append(key)
            if key in self.archive_bindings:
                source, detail = self.archive_bindings[key]
                if source not in reported_archives:
                    self.issue('module-format', key, source, detail)
                    reported_archives.add(source)
            binding = self.bindings[key]
            self.binding_metadata(binding, key, '/binding')
            if is_sum(binding.get('rep')) or is_sum(self.expression_rep(binding.get('expr'))):
                self.issue('aggregate-boundary', key, '/binding', 'unboxed-sum global binding')
            if type(binding.get('lifted')) is not bool:
                self.issue('unknown-binder-levity', key, '/binding', key)
            self.walk(binding.get('expr'), {}, key, '/expr', join_prefix=binding.get('joinValueArity', 0))
        for issue in self.issues:
            if issue['owner'] in self.chains:
                issue['reachableVia'] = self.chains[issue['owner']]
        missing = [dict(id=key, reachableVia=self.chains[uses[0]['owner']] + [key], references=uses)
                   for key, uses in sorted(self.missing.items())]
        return dict(schema=1, audit='syntactic-reachable-core', roots=roots, retainedExports=self.retained_exports,
                    capabilityProfile=self.cap.get('name'), accepted=not self.issues and not missing,
                    summary=dict(suppliedBindings=len(self.bindings), reachableBindings=len(self.reachable),
                                 missingGlobals=len(missing), issues=len(self.issues)),
                    reachableBindings=[dict(id=k, source=self.sources[k], reachableVia=self.chains[k]) for k in self.reachable],
                    dependencies=self.edges, missingGlobals=missing,
                    runtimeExternals=[dict(id=key, uses=[edge for edge in self.edges if edge['dependency'] == key])
                                      for key in sorted((set(self.cap.get('externalBindings', [])) - self.bindings.keys()) & {edge['dependency'] for edge in self.edges})],
                    primitives=[dict(name=k, expectedArity=self.cap['primitives'].get(k), uses=v) for k, v in sorted(self.primitives.items())],
                    foreignCalls=self.foreign_calls,
                    constructors=[dict(id=k, metadata=self.constructors.get(k), uses=v) for k, v in sorted(self.used_constructors.items())],
                    literals=[v for _, v in sorted(self.literals.items())], issues=self.issues,
                    limits=['All syntactically reachable branches and local RHSs are audited, including lazy error paths.',
                            'Acceptance checks the declared capability profile, not termination, branch feasibility, or runtime correctness.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('modules', nargs='*', help='Exported JSON modules, or directories containing exported *.json modules')
    parser.add_argument('--module-list', action='append', type=Path, default=[], help='Read an exact newline-delimited module manifest; relative paths are relative to the manifest')
    parser.add_argument('--package-manifest', type=Path, help='Validate exact GHC-unit Core modules, including ZIP bundles, before combining with any loose consumer modules')
    parser.add_argument('--entry', action='append', required=True, help='Exact global id or unambiguous occurrence name; repeatable')
    parser.add_argument('--io-main', action='store_true', help='Validate the exact IO () host entry contract instead of the scalar host result')
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
        if not files and not args.package_manifest:
            parser.error('Supply modules or --module-list')
        modules = (core_package_manifest.load_for_audit(args.package_manifest) if args.package_manifest else [])
        modules += [(str(path), json.loads(path.read_text())) for path in dict.fromkeys(files)]
        report = Audit(modules, json.loads(args.capabilities.read_text())).run(args.entry, io_main=args.io_main)
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
