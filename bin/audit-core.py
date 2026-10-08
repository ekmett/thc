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
from itertools import chain
from collections import deque, OrderedDict
from collections.abc import Mapping, MutableMapping, MutableSet
from contextlib import closing
import hashlib
import json
import re
import os
import shutil
import sqlite3
import tempfile
import core_data_tags
import core_managed_files
import core_original_foreign
import core_package_manifest
from pathlib import Path
import sys

from core_sums import is_sum, contains_sum, lifted_payload, payload_levity_matches, proof_error as sum_proof_error, constructor_tag as sum_constructor_tag
from core_vectors import OPERATIONS as VECTOR_OPERATIONS, is_vector, proof_error as vector_proof_error, signature_matches as vector_signature_matches, shuffle_indices
from core_tuple_inputs import contains_tuple, proof_error as tuple_input_proof_error
from core_vector_memory import OPERATIONS as VECTOR_MEMORY_OPERATIONS, read_case as vector_read_case, validate_direct as validate_vector_memory


# The identical checked-in resource is packaged in the JVM runtime jar.
SCALAR_SIGNATURES = json.loads((Path(__file__).resolve().parent.parent /
    'src/main/resources/thc/scalar-primop-signatures.json').read_text(encoding='utf-8'))['primitives']
POLYGLOT_ABI = json.loads((Path(__file__).resolve().parent.parent /
    'src/test/resources/thc/polyglot-abi.json').read_text(encoding='utf-8'))

# Original implicit RTS dependencies, not host exceptions or fabricated dictionaries.
ARITHMETIC_EXCEPTIONS = {name: 'ghc-internal:GHC.Internal.Exception.Type.' + payload for name, payload in (
    ('raiseDivZero#', 'divZeroException'), ('raiseOverflow#', 'overflowException'),
    ('raiseUnderflow#', 'underflowException'))}


_ANY = object()


def known_levity_or_boxed_pointer(lifted, proof):
    return (type(lifted) is bool or lifted is None and isinstance(proof, dict) and
            proof.get('kind') in ('object', 'data', 'closure') and
            proof.get('primReps') == ['BoxedRep Nothing'])


class AuditStoreError(RuntimeError):
    """Infrastructure failure; never translate this into a capability issue."""


def _key(value):
    if not isinstance(value, str):
        raise TypeError('Audit store keys must be strings')
    # JSON identifiers can contain escaped surrogates/NULs. SQLite TEXT cannot
    # carry all such Python strings, but an exact BLOB key can.
    return value.encode('utf-8', errors='surrogatepass')


def _text(value):
    return value.decode('utf-8', errors='surrogatepass')


def _lookup_key(value):
    if not isinstance(value, str):
        # Original dictionaries permit a hashable non-string *lookup* and
        # return no match; unhashable lookups retain their original TypeError.
        {}.get(value)
        raise KeyError(value)
    return _key(value)


def _pack(value):
    # This serializes already parsed values; it is not an input admission path.
    data = core_package_manifest.json_dumps(value).encode('ascii')
    return data, hashlib.sha256(data).digest()


def _unpack(data, digest):
    if hashlib.sha256(data).digest() != digest:
        raise AuditStoreError('Audit store record checksum mismatch')
    try:
        return core_package_manifest.json_loads(data)
    except (ValueError, TypeError) as error:
        raise AuditStoreError('Audit store record JSON is invalid') from error


class _Binding(Mapping):
    def __init__(self, store, key, header, has_expression):
        self._store = store
        self._key = key
        self._header = header
        self._has_expression = has_expression

    def __getitem__(self, key):
        if key == 'expr' and self._has_expression:
            return self._store._expression(self._key)
        return self._header[key]

    def __iter__(self):
        return iter(self._header)

    def __len__(self):
        return len(self._header)


class _Bindings(Mapping):
    def __init__(self, store):
        self._store = store

    def __getitem__(self, key):
        encoded = _lookup_key(key)
        row = self._store._one('SELECT header,header_hash,has_expression FROM bindings WHERE key=?', (encoded,))
        if row is None:
            raise KeyError(key)
        return _Binding(self._store, encoded, _unpack(row[0], row[1]), bool(row[2]))

    def __contains__(self, key):
        try:
            encoded = _lookup_key(key)
        except KeyError:
            return False
        return self._store._one('SELECT 1 FROM bindings WHERE key=?', (encoded,)) is not None

    def __iter__(self):
        for row in self._store._rows('SELECT key FROM bindings ORDER BY ordinal'):
            yield _text(row[0])

    def __len__(self):
        return self._store._one('SELECT count(*) FROM bindings')[0]


class _Sources(Mapping):
    def __init__(self, store):
        self._store = store

    def __getitem__(self, key):
        row = self._store._one('SELECT source FROM bindings WHERE key=?', (_lookup_key(key),))
        if row is None:
            raise KeyError(key)
        return _text(row[0])

    def __iter__(self):
        return iter(self._store.bindings)

    def __len__(self):
        return len(self._store.bindings)


class _Records(Mapping):
    def __init__(self, store, namespace):
        self._store = store
        self._namespace = _key(namespace)

    def __getitem__(self, key):
        row = self._store._one('SELECT payload,digest FROM records WHERE namespace=? AND key=?',
                               (self._namespace, _lookup_key(key)))
        if row is None:
            raise KeyError(key)
        return _unpack(*row)

    def __iter__(self):
        for row in self._store._rows('SELECT key FROM records WHERE namespace=? ORDER BY ordinal', (self._namespace,)):
            yield _text(row[0])

    def __len__(self):
        return self._store._one('SELECT count(*) FROM records WHERE namespace=?', (self._namespace,))[0]


class AuditStore:
    """Fresh disk catalogue with lazy bodies and ordered diagnostic storage.

    Returned records/expressions are read-only by contract. The expression cache
    is bounded by both entry count and *encoded* bytes, not an asserted Python
    heap size; oversized expressions are returned uncached. Active caller-held
    objects remain the caller's responsibility. No rows are fetched en masse.
    complete means input ingestion is sealed, never that an audit was accepted.
    """

    def __init__(self, path, provenance, *, cache_bytes=8 * 1024 * 1024,
                 cache_entries=32, page_cache_kib=4096, batch_rows=256):
        if any(type(value) is not int or value < 0 for value in (cache_bytes, cache_entries)):
            raise ValueError('Invalid audit expression cache bound')
        if any(type(value) is not int or value <= 0 for value in (page_cache_kib, batch_rows)):
            raise ValueError('Invalid audit SQLite bound')
        provenance_data, provenance_hash = _pack(provenance)
        self.path = Path(path)
        self._complete = False
        self._closed = False
        self._pending = 0
        self._batch_rows = batch_rows
        self._cache_limit = cache_bytes
        self._cache_entries = cache_entries
        self._cache = OrderedDict()
        self._cache_bytes = 0
        self.expression_loads = 0
        # Never overwrite/reopen another run's DB or follow an existing symlink.
        descriptor = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
        self._connection = sqlite3.connect(self.path, cached_statements=32)
        try:
            self._connection.execute('PRAGMA temp_store=FILE')
            self._connection.execute('PRAGMA mmap_size=0')
            self._connection.execute(f'PRAGMA cache_size=-{page_cache_kib}')
            self._connection.execute('PRAGMA foreign_keys=ON')
            self._connection.executescript('''
                BEGIN;
                CREATE TABLE metadata(key TEXT PRIMARY KEY, payload BLOB NOT NULL, digest BLOB NOT NULL);
                CREATE TABLE bindings(
                    ordinal INTEGER PRIMARY KEY, key BLOB UNIQUE NOT NULL, source BLOB NOT NULL, name BLOB,
                    header BLOB NOT NULL, header_hash BLOB NOT NULL, has_expression INTEGER NOT NULL,
                    expression BLOB, expression_hash BLOB);
                CREATE INDEX binding_names ON bindings(name,ordinal);
                CREATE TABLE records(ordinal INTEGER PRIMARY KEY, namespace BLOB NOT NULL, key BLOB NOT NULL,
                    payload BLOB NOT NULL, digest BLOB NOT NULL, UNIQUE(namespace,key));
                CREATE TABLE events(ordinal INTEGER PRIMARY KEY, kind BLOB NOT NULL, group_key BLOB, owner BLOB,
                    payload BLOB NOT NULL, digest BLOB NOT NULL);
                CREATE INDEX event_groups ON events(kind,group_key,ordinal);
                CREATE INDEX event_owners ON events(kind,owner,ordinal);
                CREATE TABLE discovery(ordinal INTEGER PRIMARY KEY, key BLOB UNIQUE NOT NULL, predecessor BLOB,
                    popped INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(predecessor) REFERENCES discovery(key));
                CREATE INDEX pending_discovery ON discovery(popped,ordinal);
                CREATE TABLE members(namespace BLOB NOT NULL, owner BLOB NOT NULL, value BLOB NOT NULL,
                    PRIMARY KEY(namespace,owner,value)) WITHOUT ROWID;
            ''')
            self._connection.execute('INSERT INTO metadata VALUES(?,?,?)',
                                     ('provenance', provenance_data, provenance_hash))
            self._set_metadata('format', 'thc-audit-store-v1')
            self._set_metadata('phase', 'ingesting')
            self._connection.commit()
        except BaseException:
            self._connection.close()
            self._closed = True
            raise
        self.bindings = _Bindings(self)
        self.sources = _Sources(self)

    @property
    def complete(self):
        return self._complete

    @property
    def cached_expression_bytes(self):
        return self._cache_bytes

    @property
    def cached_expression_count(self):
        return len(self._cache)

    def _open(self):
        if self._closed:
            raise RuntimeError('Audit store is closed')

    def _ingesting(self):
        self._open()
        if self._complete:
            raise RuntimeError('Audit input catalogue is already sealed')

    def _ready(self):
        self._open()
        if not self._complete:
            raise RuntimeError('Audit input catalogue is not sealed')

    def _one(self, query, parameters=()):
        self._open()
        with closing(self._connection.execute(query, parameters)) as cursor:
            return cursor.fetchone()

    def _rows(self, query, parameters=()):
        self._open()
        with closing(self._connection.execute(query, parameters)) as cursor:
            yield from cursor

    def _tick(self):
        self._pending += 1
        if self._pending >= self._batch_rows:
            self._connection.commit()
            self._pending = 0

    def _set_metadata(self, key, value):
        payload, digest = _pack(value)
        self._connection.execute('INSERT OR REPLACE INTO metadata VALUES(?,?,?)', (key, payload, digest))

    def provenance(self):
        return _unpack(*self._one('SELECT payload,digest FROM metadata WHERE key=?', ('provenance',)))

    def put_binding(self, source, binding):
        """Stage one checked binding, keeping the first duplicate.

        Return its previous source on duplicates, so the existing auditor can
        emit its existing duplicate-binding diagnostic without changing policy.
        """
        self._ingesting()
        key, source = _key(binding['id']), _key(source)
        previous = self._one('SELECT source FROM bindings WHERE key=?', (key,))
        if previous is not None:
            return _text(previous[0])
        header = dict(binding)
        has_expression = 'expr' in header
        expression, expression_hash = _pack(header['expr']) if has_expression else (None, None)
        if has_expression:
            header['expr'] = None  # Keep original key ordering, not the AST.
        header, header_hash = _pack(header)
        name = _key(binding['name']) if isinstance(binding.get('name'), str) else None
        self._connection.execute('''INSERT INTO bindings
            (key,source,name,header,header_hash,has_expression,expression,expression_hash)
            VALUES(?,?,?,?,?,?,?,?)''', (key, source, name, header, header_hash,
                                       int(has_expression), expression, expression_hash))
        self._tick()
        return None

    def _expression(self, key):
        self._open()
        if key in self._cache:
            self._cache.move_to_end(key)
            return self._cache[key][0]
        row = self._one('SELECT expression,expression_hash FROM bindings WHERE key=? AND has_expression=1', (key,))
        if row is None:
            raise KeyError('expr')
        value = _unpack(*row)
        self.expression_loads += 1
        size = len(row[0])
        if self._cache_entries and size <= self._cache_limit:
            while self._cache and (self._cache_bytes + size > self._cache_limit or len(self._cache) >= self._cache_entries):
                _, (_, previous_size) = self._cache.popitem(last=False)
                self._cache_bytes -= previous_size
            self._cache[key] = (value, size)
            self._cache_bytes += size
        return value

    def named(self, name):
        for row in self._rows('SELECT key FROM bindings WHERE name=? ORDER BY ordinal', (_key(name),)):
            yield _text(row[0])

    def records(self, namespace):
        return _Records(self, namespace)

    def put_record(self, namespace, key, value):
        """Store a checked constructor/module/native record; preserve key order.

        Conflict comparison/admission remains with the existing auditor. Call
        this only after its check decides to retain or replace a record.
        """
        self._ingesting()
        payload, digest = _pack(value)
        self._connection.execute('''INSERT INTO records(namespace,key,payload,digest) VALUES(?,?,?,?)
            ON CONFLICT(namespace,key) DO UPDATE SET payload=excluded.payload,digest=excluded.digest''',
            (_key(namespace), _key(key), payload, digest))
        self._tick()

    def seal(self, *, validation_complete):
        self._ingesting()
        if validation_complete is not True:
            raise ValueError('Cannot seal an incomplete validated module stream')
        try:
            self._set_metadata('phase', 'ready')
            self._connection.commit()
        except BaseException:
            # A later close must not commit a ready marker after failed sealing.
            self._connection.rollback()
            raise
        self._pending = 0
        self._complete = True

    def append_event(self, kind, value, *, group=None, owner=None):
        self._open()
        payload, digest = _pack(value)
        cursor = self._connection.execute('INSERT INTO events(kind,group_key,owner,payload,digest) VALUES(?,?,?,?,?)',
            (_key(kind), None if group is None else _key(group), None if owner is None else _key(owner), payload, digest))
        ordinal = cursor.lastrowid
        cursor.close()
        self._tick()
        return ordinal

    def _event_filter(self, kind, group, owner):
        clauses, parameters = ['kind=?'], [_key(kind)]
        for column, value in (('group_key', group), ('owner', owner)):
            if value is not _ANY:
                clauses.append(column + ' IS ?')
                parameters.append(None if value is None else _key(value))
        return ' AND '.join(clauses), parameters

    def events(self, kind, *, group=_ANY, owner=_ANY):
        self._ready()
        where, parameters = self._event_filter(kind, group, owner)
        for row in self._rows('SELECT payload,digest FROM events WHERE ' + where + ' ORDER BY ordinal', parameters):
            yield _unpack(*row)

    def event_count(self, kind, *, group=_ANY, owner=_ANY):
        where, parameters = self._event_filter(kind, group, owner)
        return self._one('SELECT count(*) FROM events WHERE ' + where, parameters)[0]

    def event_groups(self, kind):
        """Stream distinct group keys in string order, with ungrouped None first."""
        self._ready()
        for row in self._rows('SELECT DISTINCT group_key FROM events WHERE kind=? ORDER BY group_key', (_key(kind),)):
            yield None if row[0] is None else _text(row[0])

    def event_group_count(self, kind):
        return self._one('SELECT count(*) FROM (SELECT DISTINCT group_key FROM events WHERE kind=?)', (_key(kind),))[0]

    def members(self, namespace, owner):
        return _DiskSet(self, namespace, owner)

    def discover(self, key, predecessor=None):
        """Queue first discovery only, preserving the caller's exact BFS order."""
        self._ready()
        key = _key(key)
        if self._one('SELECT 1 FROM discovery WHERE key=?', (key,)) is not None:
            return False
        predecessor = None if predecessor is None else _key(predecessor)
        if predecessor is not None and self._one('SELECT 1 FROM discovery WHERE key=?', (predecessor,)) is None:
            raise ValueError('Audit predecessor must have been discovered already')
        self._connection.execute('INSERT INTO discovery(key,predecessor) VALUES(?,?)', (key, predecessor))
        self._tick()
        return True

    def pop_pending(self):
        self._ready()
        row = self._one('SELECT ordinal,key FROM discovery WHERE popped=0 ORDER BY ordinal LIMIT 1')
        if row is None:
            return None
        self._connection.execute('UPDATE discovery SET popped=1 WHERE ordinal=?', (row[0],))
        self._tick()
        return _text(row[1])

    def is_discovered(self, key):
        self._ready()
        return self._one('SELECT 1 FROM discovery WHERE key=?', (_key(key),)) is not None

    def predecessor(self, key):
        self._ready()
        row = self._one('SELECT predecessor FROM discovery WHERE key=?', (_key(key),))
        if row is None:
            raise KeyError(key)
        return None if row[0] is None else _text(row[0])

    def reachable_count(self):
        self._ready()
        return self._one('SELECT count(*) FROM discovery WHERE popped=1')[0]

    def pending_count(self):
        self._ready()
        return self._one('SELECT count(*) FROM discovery WHERE popped=0')[0]

    def reachable(self):
        self._ready()
        for key, predecessor in self._rows('SELECT key,predecessor FROM discovery WHERE popped=1 ORDER BY ordinal'):
            yield _text(key), None if predecessor is None else _text(predecessor)

    def reachable_via(self, key):
        self._ready()
        result = []
        while key is not None:
            result.append(key)
            key = self.predecessor(key)
        result.reverse()
        return result

    def close(self):
        if not self._closed:
            try:
                self._connection.commit()
            finally:
                self._connection.close()
                self._closed = True
                self._cache.clear()
                self._cache_bytes = 0

    def checkpoint(self, phase):
        self._ready()
        self._set_metadata('auditPhase', phase)
        self._connection.commit()
        self._pending = 0

    def __enter__(self):
        self._open()
        return self

    def __exit__(self, exception_type, exception, traceback):
        try:
            if exception_type is not None and not self._closed:
                self._connection.rollback()
        finally:
            self.close()
        if exception_type is None and not self._complete:
            raise RuntimeError('Audit input catalogue was not sealed')


class _InputRecords(MutableMapping):
    """Existing registration assignments, with admission still in Audit."""
    def __init__(self, store, namespace, compound=False):
        self.store, self.namespace, self.compound = store, namespace, compound
        self.view = store.records(namespace)

    def encoded(self, key):
        if not self.compound:
            if not isinstance(key, str):
                {}.get(key)
                raise KeyError(key)
            return key
        {}.get(key)
        if (not isinstance(key, tuple) or len(key) != 2 or
                not all(value is None or isinstance(value, str) for value in key)):
            raise KeyError(key)
        return json.dumps(key, ensure_ascii=True, separators=(',', ':'))

    def __getitem__(self, key):
        return self.view[self.encoded(key)]

    def __setitem__(self, key, value):
        self.store.put_record(self.namespace, self.encoded(key), value)

    def __delitem__(self, key):
        raise TypeError('Registered audit input cannot be deleted')

    def __iter__(self):
        for key in self.view:
            yield tuple(json.loads(key)) if self.compound else key

    def __len__(self):
        return len(self.view)


class _DiskSet(MutableSet):
    def __init__(self, store, namespace, owner):
        self.store, self.scope = store, (_key(namespace), _key(owner))

    def __contains__(self, value):
        return isinstance(value, str) and self.store._one(
            'SELECT 1 FROM members WHERE namespace=? AND owner=? AND value=?', (*self.scope, _key(value))) is not None

    def __iter__(self):
        for row in self.store._rows('SELECT value FROM members WHERE namespace=? AND owner=? ORDER BY value', self.scope):
            yield _text(row[0])

    def __len__(self):
        return self.store._one('SELECT count(*) FROM members WHERE namespace=? AND owner=?', self.scope)[0]

    def add(self, value):
        self.store._open()
        self.store._connection.execute('INSERT OR IGNORE INTO members VALUES(?,?,?)', (*self.scope, _key(value)))
        self.store._tick()

    def discard(self, value):
        raise TypeError('Audit proof/discovery sets are append-only')

    def update(self, values):
        for value in values: self.add(value)


class _ProofSets:
    def __init__(self, store): self.store = store
    def __getitem__(self, key): return self.store.members('package-scalar-proofs', key)
    def setdefault(self, key, default): return self[key]


class _EventList:
    def __init__(self, store, kind, group=_ANY, group_field=None, owner_field=None):
        self.store, self.kind, self.group = store, kind, group
        self.group_field, self.owner_field = group_field, owner_field

    def append(self, value):
        group = value.get(self.group_field) if self.group_field else (None if self.group is _ANY else self.group)
        owner = value.get(self.owner_field) if self.owner_field else None
        self.store.append_event(self.kind, value, group=group, owner=owner)

    def extend(self, values):
        for value in values: self.append(value)

    def __iter__(self): return self.store.events(self.kind, group=self.group)
    def __len__(self): return self.store.event_count(self.kind, group=self.group)


class _UseGroups:
    def __init__(self, store, kind): self.store, self.kind = store, kind
    def __getitem__(self, key): return _EventList(self.store, self.kind, key, owner_field='owner')
    def setdefault(self, key, default): return self[key]
    def __iter__(self): return self.store.event_groups(self.kind)
    def __len__(self): return self.store.event_group_count(self.kind)


class _Literals(_UseGroups):
    def __init__(self, store): super().__init__(store, 'literal-uses')
    def __getitem__(self, key):
        return dict(kind=key, examples=_EventList(self.store, 'literal-examples', key),
                    uses=super().__getitem__(key))


class _Predecessors(Mapping):
    def __init__(self, store): self.store = store
    def __getitem__(self, key): return self.store.predecessor(key)
    def __contains__(self, key): return isinstance(key, str) and self.store.is_discovered(key)
    def __iter__(self):
        for row in self.store._rows('SELECT key FROM discovery ORDER BY ordinal'): yield _text(row[0])
    def __len__(self): return self.store._one('SELECT count(*) FROM discovery')[0]


class _Reachable:
    def __init__(self, store): self.store = store
    def __iter__(self):
        for key, predecessor in self.store.reachable(): yield key
    def __len__(self): return self.store.reachable_count()


class _StreamArray:
    """Repeatable report section: never materialize a whole nested use group."""
    def __init__(self, values): self.values = values
    def __iter__(self): return iter(self.values())


class _StreamReport(dict):
    pass


class Audit:
    def __init__(self, modules, capabilities, foreign_exception_bridge_unit=None, *, store=None, runtime=None, ownership_command=None):
        self.cap = capabilities
        self.store = store
        self._foreign_descriptors = {}
        self._archive_candidates = []
        self.bindings = store.bindings if store is not None else {}
        self.constructors = _InputRecords(store, 'constructors') if store is not None else {}
        self.sources = store.sources if store is not None else {}
        self.issues = _EventList(store, 'issues', owner_field='owner') if store is not None else []
        self.edges = _EventList(store, 'edges', group_field='dependency', owner_field='caller') if store is not None else []
        self.missing = _UseGroups(store, 'missing') if store is not None else {}
        self.primitives = _UseGroups(store, 'primitives') if store is not None else {}
        self.foreign_calls = _EventList(store, 'foreign', owner_field='owner') if store is not None else []
        self.literals = _Literals(store) if store is not None else {}
        self.unresolved_native_symbols = _EventList(store, 'unresolved-native-symbols', owner_field='owner') if store is not None else []
        self.used_constructors = _UseGroups(store, 'constructors') if store is not None else {}
        self.reachable = _Reachable(store) if store is not None else []
        self.predecessors = _Predecessors(store) if store is not None else {}
        self.queue = deque() if store is None else None
        self.linked_foreign = _InputRecords(store, 'linked-foreign', compound=True) if store is not None else {}
        self.package_scalar_links = _InputRecords(store, 'package-links') if store is not None else {}
        self.package_scalar_proofs = _ProofSets(store) if store is not None else {}
        self.native_callback_helpers = set()
        self.boxed_foreign_calls = _InputRecords(store, 'boxed-foreign-calls', compound=True) if store is not None else {}
        self.original_boxed_bindings = _InputRecords(store, 'original-boxed-bindings') if store is not None else {}
        self.archive_bindings = _InputRecords(store, 'archive-bindings') if store is not None else {}
        self.retained_exports = _EventList(store, 'retained-exports') if store is not None else []
        self.exception_bridges = _InputRecords(store, 'exception-bridges') if store is not None else {}
        self.exception_bridge_unit = foreign_exception_bridge_unit
        self.provided_modules = store.members('provided-modules', '') if store is not None else set()
        self.complete_modules = store.members('complete-modules', '') if store is not None else set()
        if foreign_exception_bridge_unit is not None and (not isinstance(foreign_exception_bridge_unit, str) or not foreign_exception_bridge_unit):
            self.issue('foreign-exception-bridge', None, 'manifest', 'Invalid foreignExceptionBridgeUnit')
        for source, module in modules:
            self._register_module(source, module)
            del module  # Drop the full AST before parsing the next module.
        # Ingestion already reads each module. Classify its distinct foreign
        # identities once, after the complete package-link inventory is known.
        required = set(self.package_scalar_links)
        required.update(unit for _, _, unit, _, _ in self._archive_candidates)
        self.foreign_ownership = core_original_foreign.ForeignOwnership(
            (call for call in self._foreign_descriptors.values()
             if isinstance(call['target'].get('unit'), str) and call['target']['unit'] in required),
            runtime, ownership_command)
        for source, detail, unit, archive, key in self._archive_candidates:
            if core_package_manifest.native_archive_blocks(dict(unit=unit), dict(self.bindings[key]), archive,
                    self.foreign_ownership):
                self.archive_bindings[key] = (source, detail)
        del self._archive_candidates, self._foreign_descriptors
        if store is not None and self.foreign_ownership.provenance is not None:
            store.put_record('input-provenance', 'foreign-ownership', self.foreign_ownership.provenance)
        for name in self.provided_modules if store is not None else sorted(self.provided_modules):
            if name not in self.complete_modules:
                self.issue('module-format', None, name, 'Interface closure lacks its exact complete provided module')
        for unit, link in self.package_scalar_links.items():
            if self.package_scalar_proofs[unit] != {entry['entry'] for entry in link['abi']}:
                self.issue('module-format', None, unit, 'Package C ABI lacks complete typed import provenance')
        if store is not None:
            store.seal(validation_complete=getattr(modules, 'complete', True))

    def _register_module(self, source, module):
        if module.get('boundary') == 'optimized-Core-after-Tidy-before-CorePrep':
            self.complete_modules.add(str(module.get('unit')) + ':' + str(module.get('module')))
        if 'providedModules' in module:
            supplied = module['providedModules']
            if (module.get('unit') != 'dependency-closure' or module.get('module') != 'THC.InterfaceClosure'
                    or module.get('boundary') != 'actual-interface-unfoldings' or not isinstance(supplied, list)
                    or any(not isinstance(name, str) or not name.strip() for name in supplied)):
                self.issue('module-format', None, source, 'Invalid provided-module interface closure')
            else:
                self.provided_modules.update(supplied)
        bridge = module.get('foreignExceptionBridge')
        if bridge is not None:
            unit = module.get('unit')
            prefix = str(unit) + ':THC.Internal.Exception.'
            expected = dict(schema=1, unit=unit, module='THC.Internal.Exception',
                box=prefix + 'boxForeign', project=prefix + 'projectForeign',
                payloadType=prefix + 'ForeignException',
                exceptionType='ghc-internal:GHC.Internal.Exception.Type.SomeException')
            ids = {b.get('id') for b in module.get('bindings', [])}
            if (not isinstance(bridge, dict) or bridge != expected or type(bridge.get('schema')) is not int or
                    module.get('module') != 'THC.Internal.Exception' or not isinstance(unit, str) or not unit or
                    expected['box'] not in ids or expected['project'] not in ids):
                self.issue('foreign-exception-bridge', None, source, 'Invalid genuine bridge module/identity/helpers')
            elif unit in self.exception_bridges:
                self.issue('foreign-exception-bridge', None, source, 'Duplicate bridge unit')
            else:
                self.exception_bridges[unit] = bridge

        scalar_link = None
        native_archive = None
        try:
            native_archive = core_package_manifest.package_native_archive(module)
            scalar_link = core_package_manifest.package_scalar_link(module)
            if scalar_link:
                link, proved = scalar_link
                if any(other['unit'] != link['unit'] and other['componentSha256'] == link['componentSha256']
                       for other in self.package_scalar_links.values()):
                    raise ValueError('Package C entry namespace belongs to another unit: ' + link['componentSha256'])
                previous = self.package_scalar_links.setdefault(link['unit'], link)
                if previous != link:
                    raise ValueError('Conflicting package C component identity: ' + link['unit'])
                self.package_scalar_proofs.setdefault(link['unit'], set()).update(proved)
            proof = module.get('staticForeignImports', {})
            if native_archive and proof.get('status') == 'verified':
                imported = {(entry['emitted']['unit'], entry['emitted']['symbol']) for entry in proof['imports']}
                admitted = {}
                for call in proof['expectedCalls']:
                    if not core_original_foreign.boxed_owned_call(call): continue
                    key = (call['target']['unit'], call['target']['symbol'])
                    if key in imported:
                        if key not in admitted: admitted[key] = self.boxed_foreign_calls.get(key, [])
                        records = admitted[key]
                        if call not in records: records.append(call)
                for key, records in admitted.items(): self.boxed_foreign_calls[key] = records
        except (ValueError, KeyError, TypeError) as error:
            self.issue('module-format', None, source, str(error))
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
        managed_imports = False
        try:
            managed_imports = not scalar_link and core_package_manifest.managed_import_stubs(module)
        except (ValueError, KeyError, TypeError) as error:
            self.issue('module-format', None, source, str(error))
        linked = (type(module.get('schema')) is int and module['schema'] == 2 and
                  'foreignLink' in module and core_package_manifest.linked_foreign(module))
        if scalar_link or managed_imports:
            callback_proof = module.get('staticForeignImportStubs', module.get('staticForeignImports', {}))
            if callback_proof.get('schema') in (3, 4):
                for wrapper in callback_proof['wrappers']:
                    helper = wrapper['helper']
                    if helper in self.native_callback_helpers:
                        self.issue('module-format', None, source, 'Duplicate native callback helper ' + helper)
                    self.native_callback_helpers.add(helper)
        archive = core_package_manifest.foreign_execution_issue(module) if native_archive or (not linked and not scalar_link and not retained and not managed_imports) else None
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
                (module['schema'] != 1 and not linked and not scalar_link and not archive and not retained and not managed_imports) or
                module.get('ghc') != '9.14.1' or
                ('foreign' in module and not linked and not scalar_link and not archive and not retained and not managed_imports) or (registration and not retained)):
            self.issue('module-format', None, source,
                       archive or
                       'Requires executable Core schema 1 / GHC 9.14.1 without foreign artifacts')
        for binding in module.get('bindings', []):
            for call in core_package_manifest.native_archive_calls(binding):
                identity = core_original_foreign.ForeignOwnership.key(call)
                if identity is not None: self._foreign_descriptors.setdefault(identity, call)
            key = binding.get('id')
            if not isinstance(key, str):
                self.issue('binding-id', None, source, 'Binding lacks a string id')
                continue
            if key in self.bindings:
                self.issue('duplicate-binding', key, source, 'Also supplied by ' + self.sources[key])
            else:
                if module.get('schema') == 2: self.original_boxed_bindings[key] = True
                if self.store is None:
                    self.bindings[key] = binding
                    self.sources[key] = source
                else:
                    self.store.put_binding(source, binding)
                if archive and not registration:
                    if native_archive is None or native_archive['unclassifiedReason'] is not None or native_archive['unresolvedSymbols']:
                        self.archive_bindings[key] = (source, archive)
                    elif core_package_manifest.native_archive_blocks(module, binding, native_archive, lambda call: False):
                        self._archive_candidates.append((source, archive, module['unit'], native_archive, key))
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

    def require_exception_bridge(self, owner, path):
        if self.exception_bridge_unit is not None:
            bridge = self.exception_bridges.get(self.exception_bridge_unit)
        else:
            bridge = next(iter(self.exception_bridges.values())) if len(self.exception_bridges) == 1 else None
        if bridge is None:
            self.issue('foreign-exception-bridge', owner, path,
                       'Foreign execution requires an exact or unambiguous genuine THC.Exception runtime bundle')
            return
        self.reference(bridge['box'], owner, path + '/foreign-exception-box')
        self.reference(bridge['project'], owner, path + '/foreign-exception-project')

    def issue(self, code, owner, path, detail):
        self.issues.append(dict(code=code, owner=owner, path=path, detail=detail))

    def location(self, owner, path):
        return dict(owner=owner, path=path)

    def reachable_via(self, key):
        # Discovery records a forest, even when the dependency graph has cycles.
        # Expand a witness only for a diagnostic, not for every reached binding.
        chain = []
        while key is not None:
            chain.append(key)
            key = self.predecessors[key]
        chain.reverse()
        return chain

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
                    registers = component.get('primReps') if isinstance(component, dict) else None
                    if not isinstance(registers, list):
                        if not (is_sum(component) and sum_proof_error(component) is None or self.is_tuple(component) and
                                tuple_input_proof_error(component, allow_vectors=True, allow_addresses=True, allow_sums=True) is None):
                            self.issue('aggregate-representation', owner, path, aggregate + ': unresolved component')
                        physical = None
                    else:
                        if physical is not None:
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
            if (is_sum(binding.get('joinResultRep')) and
                    'unboxed-sum' not in self.cap.get('aggregateJoinResults', [])):
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
        if 'exceptionPayload' in metadata:
            proof = metadata['exceptionPayload']
            if (expr[0] != 'app' or not isinstance(expr[1], list) or expr[1][:1] != ['prim'] or
                    expr[1][1] not in ('raise#', 'raiseIO#') or not isinstance(proof, dict) or
                    type(proof.get('schema')) is not int or
                    proof != {'schema': 1, 'type': 'ghc-internal:GHC.Internal.Exception.Type.SomeException'}):
                self.issue('exception-payload', owner, path, 'Invalid SomeException raise provenance')
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
            if not known_levity_or_boxed_pointer(binder.get('lifted'), binder.get('rep')):
                self.issue('unknown-binder-levity', owner, f'{path}/{i}', binder['id'])
        return ids

    def reference(self, key, owner, path):
        location = self.location(owner, path)
        self.edges.append(dict(caller=owner, dependency=key, path=path))
        if key in self.bindings:
            self._discover(key, owner)
        elif key not in self.cap.get('externalBindings', []):
            self.missing.setdefault(key, []).append(location)

    def _discover(self, key, predecessor=None):
        if self.store is not None:
            self.store.discover(key, predecessor)
        elif key not in self.predecessors:
            self.predecessors[key] = predecessor
            self.queue.append(key)

    def literal(self, kind, value, owner, path):
        item = self.literals.setdefault(kind, dict(kind=kind, examples=[], uses=[]))
        if value not in item['examples'] and len(item['examples']) < 8:
            item['examples'].append(value)
        item['uses'].append(self.location(owner, path))
        capability_kind = {'float-bits': 'float', 'double-bits': 'double'}.get(kind, kind)
        if capability_kind not in self.cap['literalKinds']:
            self.issue('unsupported-literal', owner, path, kind)
            return
        if kind == 'rubbish' and value is not None:
            self.issue('invalid-literal-value', owner, path, 'Rubbish has no payload; its representation belongs in metadata')
        if kind == 'function-addr' and (not isinstance(value, str) or not value or '\0' in value):
            self.issue('invalid-literal-value', owner, path, 'C function label requires a nonempty symbol without NUL')
            return
        if kind == 'function-addr' and value not in self.cap.get('functionLabels', []) and value not in self.native_callback_helpers:
            selected = [(unit, entry['entry']) for unit, link in self.package_scalar_links.items()
                for entry in link['abi'] if entry['symbol'] == value and
                    (entry['entry'] in link.get('dataSymbols', []) or
                     entry['entry'] in link.get('finalizers', []) and entry['entry'] in self.package_scalar_proofs[unit])]
            candidates = [entry for link in self.package_scalar_links.values()
                for entry in link['abi'] if entry['symbol'] == value]
            if len(candidates) > 1:
                self.issue('unsupported-literal', owner, path, f'ambiguous C function label {value}')
            elif candidates and not selected:
                self.issue('unsupported-literal', owner, path, f'uncertified C function label {value}')
            elif not selected:
                self.unresolved_native_symbols.append(dict(symbol=value, kind=kind, owner=owner, path=path,
                    resolution='required-on-expression-evaluation'))
        if kind == 'data-addr' and value not in self.cap.get('dataLabels', []):
            selected = [(unit, entry['entry']) for unit, link in self.package_scalar_links.items()
                for entry in link['abi'] if entry['symbol'] == value and entry['entry'] in link.get('dataSymbols', [])]
            if len(selected) != 1:
                self.issue('unsupported-literal', owner, path, f'unlinked or ambiguous C data label {value}')
        if kind == 'bignat':
            if not isinstance(value, str) or not value or any(c not in '0123456789' for c in value) or len(value) > 1 and value[0] == '0':
                self.issue('invalid-literal-value', owner, path, 'bignat requires canonical nonnegative decimal')
        if kind == 'string-bytes':
            if not isinstance(value, str) or len(value) % 2 or any(c not in '0123456789abcdefABCDEF' for c in value):
                self.issue('invalid-literal-value', owner, path, 'string-bytes must contain pairs of hexadecimal digits')
        if kind == 'null-addr' and value != '0':
            self.issue('invalid-literal-value', owner, path, 'null-addr must be exactly 0')
        limits = {'float-bits': (0, (1 << 32) - 1), 'double-bits': (0, (1 << 64) - 1)}.get(
            kind, self.cap.get('integerLiteralRanges', {}).get(kind))
        if limits is not None:
            lo, hi = limits
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

    @staticmethod
    def is_sum_value(rep):
        # A zero-arity sum-returning join is control flow, not a captured sum.
        return is_sum(rep) and '_join_arity' not in rep

    @classmethod
    def is_empty_tuple(cls, rep):
        return (cls.is_tuple(rep) and rep.get('kind') == 'unknown' and
                rep.get('components') == [] and rep.get('primReps') == [])

    def supported_empty_input(self, rep):
        return self.is_empty_tuple(rep) and 'empty-unboxed-tuple' in self.cap.get('aggregateInputs', [])

    def supported_empty_join_input(self, rep):
        return self.is_empty_tuple(rep) and 'empty-unboxed-tuple' in self.cap.get('aggregateJoinInputs', [])

    def supported_tuple_join_input(self, rep):
        return (self.supported_empty_join_input(rep) or
                'unboxed-tuple' in self.cap.get('aggregateJoinInputs', []) and tuple_input_proof_error(rep,
                    allow_vectors='join-arguments' in self.cap.get('vectorTransport', []), allow_addresses=True,
                    allow_sums='unboxed-sum' in self.cap.get('aggregateJoinInputs', [])) is None)

    def supported_tuple_input(self, rep, sum_capability='aggregateInputs'):
        return (self.supported_empty_input(rep) or
                'unboxed-tuple' in self.cap.get('aggregateInputs', []) and tuple_input_proof_error(rep,
                    allow_vectors='tuple-fields' in self.cap.get('vectorTransport', []), allow_addresses=True,
                    allow_sums='unboxed-sum' in self.cap.get(sum_capability, [])) is None)

    def supported_sum(self, rep, capability):
        return ('unboxed-sum' in self.cap.get(capability, []) and is_sum(rep) and
                sum_proof_error(rep) is None)

    def supported_vector(self, rep, boundary):
        return (boundary in self.cap.get('vectorTransport', []) and is_vector(rep) and
                vector_proof_error(rep) is None and any(
                    rep['vector'] == {key: shape[key] for key in ('lanes', 'element')}
                    for shape in self.cap.get('vectorRepresentations', [])))

    def supported_heap_aggregate(self, rep):
        if not isinstance(rep, dict) or rep.get('evaluated') is not True:
            return False
        if rep.get('aggregate') not in self.cap.get('aggregateHeapFields', []):
            return False
        return (sum_proof_error(rep) is None if is_sum(rep) else
                tuple_input_proof_error(rep, allow_addresses=True,
                    allow_vectors='heap-fields' in self.cap.get('vectorTransport', []),
                    allow_sums='unboxed-sum' in self.cap.get('aggregateHeapFields', [])) is None)

    @classmethod
    def shape(cls, rep):
        """Logical tuple boundaries are significant even at zero/one register.

        Boxed leaf kinds and WHNF evidence may refine independently; known
        primitive representation names remain distinct.
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

    @classmethod
    def compatible_shapes(cls, left, right):
        if left is None or right is None:
            return False
        if left == right:
            return True
        if left[0] != right[0]:
            return False
        if left[0] in ('tuple', 'sum'):
            return len(left[1]) == len(right[1]) and all(cls.compatible_shapes(a, b) for a, b in zip(left[1], right[1]))
        boxed = {('BoxedRep Nothing',), ('BoxedRep (Just Lifted)',), ('BoxedRep (Just Unlifted)',)}
        return (left[0] == 'scalar' and left[1] in boxed and right[1] in boxed and
                ('BoxedRep Nothing',) in (left[1], right[1]))

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
        if not self.compatible_shapes(left, right):
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
            if expr[1] in ('null-addr', 'function-addr', 'data-addr'):
                return dict(kind='address', primReps=['AddrRep'], evaluated=True)
            kind = {'float': 'float', 'float-bits': 'float', 'double': 'double', 'double-bits': 'double', 'string-bytes': 'address',
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
        # An erased result newtype cast retains its conservative object carrier;
        # enumFamily below certifies the original nominal enum operation.
        if not any(scalar(self.expression_rep(expr), kind, 'BoxedRep (Just Lifted)') for kind in ('data', 'object')):
            reject('exact lifted data/object result required')
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
        dynamic = isinstance(target, dict) and target.get('kind') == 'dynamic'
        callback_runtime = isinstance(target, dict) and (
            symbol == 'createAdjustor' and target.get('unit') is None or
            symbol == 'freeHaskellFunctionPtr' and target.get('unit') == 'ghc-internal')
        if dynamic or callback_runtime:
            try:
                require = core_original_foreign.require
                core_original_foreign.validate_head(function, function[1] in bound or function[1] in self.bindings)
                require(isinstance(call, dict) and set(call) == core_original_foreign.DESCRIPTOR_KEYS and
                    type(call['schema']) is int and call['schema'] == 1 and call['convention'] == 'ccall' and
                    call['safety'] in ('safe', 'unsafe'), 'dynamic synchronous C ABI')
                require(target == {'kind': 'dynamic'} if dynamic else set(target) == {'kind', 'symbol', 'unit', 'isFunction'} and
                    target['kind'] == 'static' and target['isFunction'] is True, 'dynamic/runtime target')
                declared = call['argumentReps']
                require(isinstance(declared, list) and len(declared) >= 2, 'dynamic argument inventory')
                primitives = {'IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep',
                    'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep', 'FloatRep', 'DoubleRep', 'AddrRep'}
                reps = []
                for proof in declared[:-1]:
                    require(isinstance(proof, dict) and isinstance(proof.get('primReps'), list) and
                        len(proof['primReps']) == 1 and proof['primReps'][0] in primitives, 'dynamic scalar argument')
                    reps.append(proof['primReps'][0])
                wanted = reps + [None]
                require(reps[0] == 'AddrRep' and len(arguments) == len(wanted) and expr[3] == [False] * len(wanted) and
                    all(type(call[k]) is int and call[k] == len(wanted) for k in ('arity', 'suppliedArity')),
                    'dynamic function pointer/arity')
                for i, (actual, proof, rep) in enumerate(zip(arguments, declared, wanted)):
                    require(core_original_foreign.scalar(proof, rep, True) and core_original_foreign.scalar(
                        core_original_foreign.raw_rep(actual), rep), 'dynamic operand ABI')
                    self.original_stack_operand(actual, rep, bound, i)
                parts = call['resultRep'].get('components')
                require(isinstance(parts, list) and len(parts) in (1, 2), 'dynamic State/result tuple')
                result = None if len(parts) == 1 else parts[1]['primReps'][0]
                require(result is None or result in primitives, 'dynamic scalar result')
                outputs = (None,) if result is None else (None, result)
                require(core_original_foreign.result(call['resultRep'], outputs, True) and
                    core_original_foreign.result(core_original_foreign.raw_rep(expr), outputs), 'dynamic result ABI')
                if callback_runtime:
                    require(call['safety'] == 'unsafe' and (reps == ['AddrRep'] * 3 and result == 'AddrRep'
                        if symbol == 'createAdjustor' else reps == ['AddrRep'] and result is None), 'callback runtime ABI')
                self.foreign_calls.append(dict(symbol='<dynamic>' if dynamic else symbol, owner=owner, path=path))
                if dynamic: self.require_exception_bridge(owner, path)
            except (ValueError, KeyError, TypeError, IndexError) as error:
                self.issue('foreign-call', owner, path, str(error))
            return True
        runtime_arguments = self.cap.get('runtimeServiceCalls', {}).get(symbol) if isinstance(symbol, str) else None
        if runtime_arguments is not None:
            try:
                core_original_foreign.validate_head(function, function[1] in bound or function[1] in self.bindings)
                require = core_original_foreign.require
                require(set(call) == core_original_foreign.DESCRIPTOR_KEYS and type(call['schema']) is int and
                        call['schema'] == 1 and call['convention'] == 'ccall' and
                        call['safety'] == ('safe' if symbol == 'thc_exception_v1_text' else 'unsafe'),
                        'THC runtime service exact v1 C ABI')
                require(set(target) == {'kind', 'symbol', 'unit', 'isFunction'} and target['kind'] == 'static' and
                        target['isFunction'] is True and (target['unit'] is None or isinstance(target['unit'], str)),
                        'THC runtime service static target')
                require(all(type(call[k]) is int and call[k] == len(runtime_arguments) for k in ('arity', 'suppliedArity')) and
                        len(arguments) == len(runtime_arguments) and len(expr[3]) == len(runtime_arguments) and
                        all(flag is False for flag in expr[3]), 'THC runtime service saturated arguments')
                declared = call['argumentReps']
                require(isinstance(declared, list) and len(declared) == len(runtime_arguments), 'THC runtime service argument declarations')
                for index, (argument, proof, primitive) in enumerate(zip(arguments, declared, runtime_arguments)):
                    require(core_original_foreign.scalar(proof, primitive, True) and
                            core_original_foreign.scalar(core_original_foreign.raw_rep(argument), primitive),
                            f'THC runtime service argument {index}')
                    self.original_stack_operand(argument, primitive, bound, index)
                require(core_original_foreign.result(call['resultRep'], (None, 'Int64Rep'), True) and
                        core_original_foreign.result(core_original_foreign.raw_rep(expr), (None, 'Int64Rep')),
                        'THC runtime service State#/CLLong result')
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path))
                if symbol == 'thc_exception_v1_text':
                    self.require_exception_bridge(owner, path)
            except (ValueError, KeyError, TypeError, IndexError) as error:
                self.issue('foreign-call', owner, path, str(error))
            return True
        if symbol in ('thc_cpu_affinity_v1_support', 'thc_cpu_affinity_v1_applied'):
            try:
                core_original_foreign.validate_head(function, function[1] in bound or function[1] in self.bindings)
                state = {'kind': 'void', 'primReps': [], 'evaluated': False}
                result = {'kind': 'unknown', 'primReps': ['Int32Rep'], 'evaluated': False,
                          'aggregate': 'unboxed-tuple', 'components': [dict(state, evaluated=True),
                          {'kind': 'long', 'primReps': ['Int32Rep'], 'evaluated': True}]}
                expected = dict(schema=1, target=target, convention='ccall', safety='unsafe',
                                arity=1, suppliedArity=1, argumentReps=[state], resultRep=result)
                if (any(type(call.get(key)) is not int for key in ('schema', 'arity', 'suppliedArity')) or
                        set(target) != {'kind', 'symbol', 'unit', 'isFunction'} or target['kind'] != 'static' or
                        target['isFunction'] is not True or call != expected or len(arguments) != 1 or
                        expr[3] != [False] or self.shape(self.expression_rep(expr)) != self.shape(result)):
                    raise ValueError('THC CPU-affinity query requires its exact v1 State#/CInt ABI')
                self.original_stack_operand(arguments[0], None, bound, 0)
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path))
            except (ValueError, KeyError, TypeError, IndexError) as error:
                self.issue('foreign-call', owner, path, str(error))
            return True
        package_link = self.package_scalar_links.get(target.get('unit')) if isinstance(target, dict) else None
        # JavaScript in a mixed native unit uses its full descriptor check below.
        if (package_link is not None and call.get('convention') in ('ccall', 'capi') and
                call.get('intrinsic') != 'javascript-v1' and not self.foreign_ownership(call)):
            try:
                head = self.expression_rep(function)
                if (len(function) != 3 or function[0] != 'var' or not isinstance(function[1], str) or not function[1] or
                    function[1] in bound or function[1] in self.bindings or not isinstance(head, dict) or
                    set(head) != {'kind', 'primReps', 'evaluated'} or head['kind'] != 'closure' or
                    head['primReps'] != ['BoxedRep (Just Lifted)'] or head['evaluated'] is not True):
                    raise ValueError('Package C call requires its unresolved foreign identifier')
                package_abi = core_package_manifest.select_package_scalar_call(call,
                    [entry for entry in package_link['abi'] if entry['entry'] not in package_link.get('dataSymbols', [])], package_link['unit'],
                    [core_original_foreign.raw_rep(argument) for argument in arguments], expr[3],
                    core_original_foreign.raw_rep(expr))
                for index, (argument, primitive) in enumerate(zip(arguments, package_abi['arguments'] + [None])):
                    self.original_stack_operand(argument, 'BoxedRep (Just Unlifted)' if primitive in ('ByteArray#', 'MutableByteArray#') else primitive, bound, index)
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path, linkedUnit=package_link['unit']))
                self.require_exception_bridge(owner, path)
            except (ValueError, KeyError, TypeError) as error:
                self.issue('foreign-call', owner, path, str(error))
            return True

        if isinstance(call, dict) and call.get('schema') == 2 and symbol not in POLYGLOT_ABI['operations']:
            # Typed array FCalls carry their C ABI directly. An exported closure
            # may precede its owner bundle; this checks the call, not symbol
            # availability, and leaves ordinary linkage explicitly outstanding.
            try:
                core_original_foreign.validate_head(function, function[1] in bound or function[1] in self.bindings)
                if (call.get('convention') not in ('ccall', 'capi') or call.get('safety') not in ('safe', 'unsafe') or
                        not isinstance(target, dict) or not isinstance(target.get('unit'), str) or not target['unit'] or
                        not isinstance(symbol, str) or re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', symbol) is None):
                    raise ValueError('Invalid typed native FCall target/convention/safety')
                scalars = ('IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep',
                           'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep', 'FloatRep', 'DoubleRep', 'AddrRep')
                declared = call['argumentReps']
                types = call['argumentTypes']
                if not isinstance(types, list) or len(types) != len(declared):
                    raise ValueError('Typed native FCall argument type count differs')
                carriers = [kind if kind is not None else value['primReps'][0]
                            for kind, value in zip(types[:-1], declared[:-1])]
                parts = call['resultRep']['components']
                result = 'void' if len(parts) == 1 else parts[1]['primReps'][0]
                if (any(carrier not in scalars + ('ByteArray#', 'MutableByteArray#') for carrier in carriers) or
                        result not in scalars + ('void',)):
                    raise ValueError('Unsupported typed native FCall carrier')
                abi = dict(symbol=symbol, arguments=carriers, result=result,
                           convention=call['convention'], safety=call['safety'])
                core_package_manifest.validate_package_scalar_call(call, abi, target['unit'],
                    [core_original_foreign.raw_rep(argument) for argument in arguments], expr[3],
                    core_original_foreign.raw_rep(expr))
                for index, (argument, primitive) in enumerate(zip(arguments, carriers + [None])):
                    self.original_stack_operand(argument,
                        'BoxedRep (Just Unlifted)' if primitive in ('ByteArray#', 'MutableByteArray#') else primitive,
                        bound, index)
                self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path,
                                               arguments=carriers, nativeLinkRequired=True))
            except (ValueError, KeyError, TypeError, IndexError) as error:
                self.issue('foreign-call', owner, path, str(error))
            return True

        if isinstance(symbol, str) and core_original_foreign.operation_symbol(target) in core_original_foreign.OPERATIONS:
            try:
                if owner in self.original_boxed_bindings and core_original_foreign.boxed_owned_call(call):
                    core_original_foreign.require(call in self.boxed_foreign_calls.get((target['unit'], symbol), []),
                        'boxed RTS call lacks exact nominal stock-import admission')
                core_original_foreign.validate(metadata, [core_original_foreign.raw_rep(arg) for arg in arguments],
                                               expr[3], core_original_foreign.raw_rep(expr))
                head_id = function[1] if isinstance(function, list) and len(function) > 1 else None
                defined = isinstance(head_id, str) and (head_id in bound or head_id in self.bindings)
                core_original_foreign.validate_head(function, defined)
                if symbol == 'stg_sig_install':
                    self.reference('ghc-internal:GHC.Internal.Conc.Signal.runHandlersPtr', owner, path + '/signal-dispatcher')
                if symbol == core_original_foreign.STACK_CLONE:
                    state = arguments[0]
                    if state[0] == 'var':
                        key = state[1]
                        stored = bound.get(key) if key in bound else self.bindings.get(key, {}).get('rep')
                        core_original_foreign.validate_state_binding(stored)
                    # Lowering knows these producers cannot yield State even if
                    # an occurrence falsely claims the zero-width certificate.
                    core_original_foreign.require(state[0] not in ('lit', 'lam', 'con'), 'lowered State argument')
                # Every closed original signature takes scalar or unlifted-object
                # carriers handled by this checker. The catalog regression guards
                # new carrier kinds; do not maintain a second symbol whitelist.
                # Select the validated unit-specific ABI (e.g. array's memcpy).
                for index, (argument, primitive) in enumerate(zip(arguments, core_original_foreign.operation(target, call.get('argumentReps'))[2])):
                    self.original_stack_operand(argument, primitive, bound, index)
                if core_original_foreign.operation_symbol(target) not in self.cap.get('managedForeignCalls', []):
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
                zero = abi[symbol] in ('clock-id', 'time-clock-id')
                word = 'Int32Rep' if link['module'] == 'Data.Time.Clock.Internal.CTimespec' else 'Word64Rep'
                wanted = [None] if zero else [word, 'AddrRep', None]
                output = word if zero else 'Int32Rep'
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
                for index, (argument, primitive) in enumerate(zip(arguments, wanted)):
                    self.original_stack_operand(argument, primitive, bound, index)
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
        if not isinstance(call, dict) or type(call.get('schema')) is not int or call['schema'] not in (1, 2):
            return reject('Missing GHC foreign-call schema evidence')
        target = call.get('target')
        if not isinstance(target, dict) or target.get('kind') != 'static' or target.get('isFunction') is not True:
            return reject('Requires a static function target')
        symbol = target.get('symbol')
        javascript_prefix = 'thc_javascript_v1_'
        if ('intrinsic' in call or 'javascriptSource' in call or
                isinstance(symbol, str) and symbol.startswith(javascript_prefix)):
            if call['schema'] != 1:
                return reject('JavaScript import requires schema 1 evidence')
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
            self.require_exception_bridge(owner, path)
            return True
        spec = POLYGLOT_ABI['operations'].get(symbol)
        if spec is None:
            return reject('Unsupported foreign target ' + repr(symbol))
        types = spec.get('argumentTypes')
        if (call['schema'] != (1 if types is None else 2) or
                (('argumentTypes' in call) if types is None else call.get('argumentTypes') != types)):
            return reject('GHC nominal array types differ from polyglot ABI')
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
            kinds = {'AddrRep': 'address', 'IntRep': 'long', 'WordRep': 'long',
                     'Int8Rep': 'long', 'Int16Rep': 'long', 'Int32Rep': 'long', 'Int64Rep': 'long',
                     'FloatRep': 'float', 'DoubleRep': 'double',
                     'BoxedRep (Just Lifted)': 'object', 'BoxedRep (Just Unlifted)': 'object', 'State# RealWorld': 'void'}
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
        if spec.get('scalar') is True:
            compatible = len(wanted) == 1 and exact(result, wanted[0]) and exact(self.expression_rep(expr), wanted[0])
        else:
            compatible = not (not isinstance(components, list) or len(components) != len(wanted) or
                result.get('aggregate') != 'unboxed-tuple' or result.get('kind') != 'unknown' or
                any(not exact(rep, register) for rep, register in zip(components, wanted)) or
                result.get('primReps') != [r for rep in components for r in rep['primReps']] or
                self.shape(self.expression_rep(expr)) != self.shape(result))
        if not compatible:
            return reject('GHC declared scalar or State# tuple result differs from polyglot ABI')
        self.foreign_calls.append(dict(symbol=symbol, owner=owner, path=path))
        if spec.get('exceptionBridge', True):
            self.require_exception_bridge(owner, path)
        return True

    def free_variables(self, expr):
        pending, result = [self._free_variables(expr)], None
        while pending:
            try:
                child = pending[-1].send(result)
            except StopIteration as done:
                pending.pop()
                result = done.value
            else:
                pending.append(self._free_variables(child))
                result = None
        return result

    def _free_variables(self, expr):
        if not isinstance(expr, list) or not expr:
            return set()
        if expr[0] == 'var':
            return {expr[1]}
        if expr[0] == 'lam':
            return (yield expr[2]) - {b['id'] for b in expr[1]}
        if expr[0] == 'app':
            result = yield expr[1]
            for argument in expr[2]:
                result |= (yield argument)
            return result
        if expr[0] == 'let':
            ids = {b['id'] for b in expr[2]}
            rhs = set()
            for binding in expr[2]:
                rhs |= (yield binding['expr'])
            return (rhs - ids if expr[1] else rhs) | ((yield expr[3]) - ids)
        if expr[0] == 'case':
            result = yield expr[1]
            for alternative in expr[3]:
                result |= (yield alternative[3]) - set(alternative[2]) - {expr[2]}
            return result
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
                sum_constructor_tag(info, arity, tuple_rep)
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
        if isinstance(fields, list) and any(contains_sum(field) and not self.supported_heap_aggregate(field) for field in fields):
            self.issue('aggregate-boundary', owner, path, 'unboxed-sum heap field')
        if isinstance(fields, list) and any(contains_tuple(field) and not self.supported_heap_aggregate(field) for field in fields):
            self.issue('aggregate-boundary', owner, path, 'unboxed-tuple heap field')
        reps = info.get('fieldReps')
        if not isinstance(reps, list) or len(reps) != expected:
            self.issue('constructor-representations', owner, path, f'{key}: missing/misaligned fieldReps')
        else:
            for index, registers in enumerate(reps):
                proof = fields[index] if isinstance(fields, list) and len(fields) == expected else None
                if self.supported_heap_aggregate(proof):
                    lifted, strict = info.get('fieldLifted'), info.get('strictFields')
                    if (proof.get('primReps') != registers or
                            not isinstance(lifted, list) or len(lifted) != expected or lifted[index] is not False or
                            not isinstance(strict, list) or len(strict) != expected or type(strict[index]) is not bool):
                        self.issue('constructor-field-representation', owner, path,
                                   f'{key}[{index}]: aggregate field requires exact unlifted fieldTypes')
                    self.representation(proof, owner, path + f'/fieldTypes/{index}')
                elif not isinstance(registers, list) or len(registers) > 1:
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
            if isinstance(fields, list) and index < len(fields) and self.supported_heap_aggregate(fields[index]):
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
                    proof = fields[index] if isinstance(fields, list) and len(fields) == expected else None
                    if type(is_strict) is not bool or (is_strict and not known_levity_or_boxed_pointer(is_lifted, proof)):
                        self.issue('constructor-strictness', owner, path, f'{key}[{index}]: unknown strictness/levity')
                    elif is_strict and is_lifted and not self.cap['strictLiftedFields']:
                        self.issue('strict-lifted-field', owner, path, f'{key}[{index}]')

    def walk(self, expr, bound, owner, path, primitive_arity=None, tuple_result=None, join_prefix=0, sum_payload=False):
        pending = [self._walk(expr, bound, owner, path, primitive_arity, tuple_result, join_prefix, sum_payload)]
        while pending:
            try:
                child = next(pending[-1])
            except StopIteration:
                pending.pop()
            else:
                pending.append(child)

    def _walk(self, expr, bound, owner, path, primitive_arity=None, tuple_result=None, join_prefix=0, sum_payload=False):
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
                yield self._walk(expr[1][1], bound, owner, path + '/scrutinee/function', 3)
                for index, argument in enumerate(read['arguments']):
                    yield self._walk(argument, bound, owner, f'{path}/scrutinee/arguments/{index}')
                declared, actual = self.expression_rep(expr), self.expression_rep(read['body'])
                known = all(isinstance(rep, dict) and isinstance(rep.get('primReps'), list)
                            for rep in (declared, actual))
                self.compare_shapes(declared, actual, owner, path + '/alternatives/0/body/rep', component=known)
                yield self._walk(read['body'], bound | self.binder_scope(alternative[4]['binders']), owner,
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
                if expr[1] != 'rubbish':
                    self.compare_shapes(self.expression_rep(expr), self.literal_rep(expr), owner, path + '/rep')
                if expr[1] == 'rubbish':
                    raw = expr[3].get('rep') if len(expr) > 3 and isinstance(expr[3], dict) else None
                    if (not isinstance(raw, dict) or raw.get('evaluated') is not True or
                            raw.get('kind') == 'unknown' and not (self.is_tuple(raw) or is_sum(raw))):
                        self.issue('rubbish-representation', owner, path + '/rep',
                                   'Rubbish requires an explicit evaluated logical representation')
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
                if expr[1] in ('function-addr', 'data-addr'):
                    raw = expr[3].get('rep') if len(expr) > 3 and isinstance(expr[3], dict) else None
                    if (not isinstance(raw, dict) or raw.get('kind') != 'address' or
                            raw.get('primReps') != ['AddrRep'] or 'aggregate' in raw or is_vector(raw)):
                        self.issue('scalar-representation', owner, path + '/rep',
                                   'Original C label requires explicit exact AddrRep proof')
                    if expr[1] == 'data-addr' and (not isinstance(raw, dict) or raw.get('evaluated') is not True):
                        self.issue('scalar-representation', owner, path + '/rep',
                                   'RTS data label requires evaluated AddrRep proof')
            elif tag == 'void':
                self.compare_shapes(self.expression_rep(expr), self.literal_rep(expr), owner, path + '/rep')
            elif tag == 'lam':
                ids = self.binder_ids(expr[1], owner, path + '/binders')
                for index, binder in enumerate(expr[1]):
                    if is_sum(binder.get('rep')) and not self.supported_sum(binder.get('rep'),
                            'aggregateJoinInputs' if index < join_prefix else 'aggregateInputs'):
                        self.issue('aggregate-boundary', owner, path, 'unboxed-sum formal argument')
                    if is_sum(binder.get('rep')) and binder.get('lifted') is not False:
                        self.issue('application-levity', owner, path, 'Sum formal must be unlifted')
                    if is_vector(binder.get('rep')) and not self.supported_vector(binder['rep'],
                            'join-arguments' if index < join_prefix else 'arguments'):
                        self.issue('vector-boundary', owner, path, 'vector formal argument')
                    if is_vector(binder.get('rep')) and binder.get('lifted') is not False:
                        self.issue('application-levity', owner, path, 'Vector formal must be unlifted')
                    if self.is_tuple(binder.get('rep')) and not (
                            self.supported_tuple_join_input(binder.get('rep')) if index < join_prefix else
                            self.supported_tuple_input(binder.get('rep'))):
                        self.issue('aggregate-boundary', owner, path, 'unboxed-tuple formal argument')
                    if self.is_tuple(binder.get('rep')) and binder.get('lifted') is not False:
                        self.issue('application-levity', owner, path, 'Tuple formal must be unlifted')
                captured = {key for key in (self.free_variables(expr[2]) - ids) & bound.keys()
                            if self.is_tuple_value(bound[key])}
                sum_captures = [bound[key] for key in (self.free_variables(expr[2]) - ids) & bound.keys()
                                if self.is_sum_value(bound[key])]
                # The consumed join lambda branches within its enclosing frame;
                # a residual lambda still allocates an ordinary closure.
                local_join_prefix = join_prefix > 0 and join_prefix == len(expr[1])
                if sum_captures and not all(self.supported_sum(rep,
                        'aggregateJoinCaptures' if local_join_prefix else 'aggregateCaptures') for rep in sum_captures):
                    self.issue('aggregate-boundary', owner, path, 'unboxed-sum capture')
                vector_captures = {key for key in (self.free_variables(expr[2]) - ids) & bound.keys()
                                   if self.is_vector_value(bound[key])}
                if vector_captures and not (
                        all(self.supported_vector(bound[key], 'join-captures' if local_join_prefix else 'captures')
                            for key in vector_captures)):
                    self.issue('vector-boundary', owner, path, 'vector capture')
                if captured and not ('unboxed-tuple' in self.cap.get(
                                     'aggregateJoinCaptures' if local_join_prefix else 'aggregateCaptures', []) and
                                     all(self.supported_tuple_input(bound[key],
                                         'aggregateJoinCaptures' if local_join_prefix else 'aggregateCaptures') for key in captured)):
                    self.issue('aggregate-boundary', owner, path, 'unboxed-tuple capture')
                metadata = expr[3] if len(expr) > 3 and isinstance(expr[3], dict) else {}
                if any(is_vector(rep) and not self.supported_vector(rep,
                        'join-results' if local_join_prefix else 'results') for rep in
                        (metadata.get('resultRep'), self.expression_rep(expr[2]))):
                    self.issue('vector-boundary', owner, path, 'vector function result')
                local = bound | self.binder_scope(expr[1])
                self.compare_shapes(metadata.get('resultRep'), self.effective_rep(expr[2], local)
                                    if self.is_tuple(metadata.get('resultRep')) else self.expression_rep(expr[2]), owner, path + '/resultRep')
                yield self._walk(expr[2], local, owner, path + '/body')
            elif tag == 'app':
                arguments = expr[2]
                flags = expr[3] if len(expr) > 3 else None
                if not isinstance(arguments, list):
                    raise ValueError('Application arguments must be an array')
                if not isinstance(flags, list) or len(flags) != len(arguments) or any(
                        not known_levity_or_boxed_pointer(flag, self.expression_rep(argument))
                        for flag, argument in zip(flags, arguments)):
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
                        sum_constructor_tag(self.constructors.get(function[1]), len(arguments), proof)
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
                if function[0] == 'prim':
                    self.scalar_primitive(function[1], arguments, proof, bound, owner, path)
                    if function[1] in ('waitRead#', 'waitWrite#'):
                        def exact(rep, kind, registers):
                            return (isinstance(rep, dict) and rep.get('kind') == kind and
                                    rep.get('primReps') == registers and 'aggregate' not in rep and
                                    'vector' not in rep)
                        actual = [self.expression_rep(arg) for arg in arguments]
                        if (len(actual) != 2 or flags != [False, False] or
                                not exact(actual[0], 'long', ['IntRep']) or
                                not exact(actual[1], 'void', []) or not exact(proof, 'void', [])):
                            self.issue('primitive-representation', owner, path,
                                       function[1] + ': expected Int#, State# -> State#')
                tuple_primitive = self.cap.get('tuplePrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if tuple_primitive is not None:
                    expected_args = [('scalar', (rep,)) for rep in tuple_primitive['arguments']]
                    expected_result = ('tuple', tuple(('scalar', (rep,)) for rep in tuple_primitive['result']))
                    argument_reps = [self.expression_rep(argument) for argument in arguments]
                    actual_args = [self.shape(rep) for rep in argument_reps]
                    if (actual_args != expected_args or flags != [False] * len(expected_args) or
                            any(not isinstance(rep, dict) or 'aggregate' in rep or
                                rep.get('kind') != {'FloatRep': 'float', 'DoubleRep': 'double'}.get(register, 'long')
                                for rep, register in zip(argument_reps, tuple_primitive['arguments']))):
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
                        return kind in ('object', 'data', 'closure') and reps in (
                            ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])
                    expected = array['arguments']
                    actual = [self.expression_rep(argument) for argument in arguments]
                    if (len(arguments) != len(expected) or any(type(flag) is not bool for flag in flags) or
                            flags != [isinstance(rep, dict) and rep.get('primReps') == ['BoxedRep (Just Lifted)'] for rep in actual] or
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
                    if (not isinstance(proof, dict) or
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
                if function[0] == 'prim' and function[1] in ('newPromptTag#', 'prompt#', 'control0#'):
                    def continuation_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        if role == 'state':
                            return rep.get('kind') == 'void' and rep.get('primReps') == []
                        if role == 'tag':
                            return rep.get('kind') == 'object' and rep.get('primReps') == ['BoxedRep (Just Unlifted)']
                        return rep.get('kind') == 'closure' and rep.get('primReps') == ['BoxedRep (Just Lifted)']
                    roles = ['state'] if function[1] == 'newPromptTag#' else ['tag', 'closure', 'state']
                    actual = [self.expression_rep(argument) for argument in arguments]
                    if (len(actual) != len(roles) or flags != [role == 'closure' for role in roles] or
                            any(not continuation_role(rep, role) for rep, role in zip(actual, roles))):
                        self.issue('primitive-representation', owner, path, function[1] + ': prompt/action/State# contract')
                    fields = proof.get('components') if isinstance(proof, dict) else None
                    if not (self.is_tuple(proof) and isinstance(fields, list) and len(fields) == 2 and
                            continuation_role(fields[0], 'state')):
                        self.issue('primitive-representation', owner, path, function[1] + ': State#/result tuple required')
                    elif function[1] == 'newPromptTag#' and not continuation_role(fields[1], 'tag'):
                        self.issue('primitive-representation', owner, path, 'newPromptTag#: PromptTag# result required')
                    elif function[1] == 'prompt#' and fields[1].get('primReps') != ['BoxedRep (Just Lifted)']:
                        self.issue('primitive-representation', owner, path, 'prompt#: lifted result required')
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
                                rep.get('primReps') in ([['BoxedRep (Just Lifted)']] if role == 'closure' else
                                    [['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)']]))
                    roles = {'raiseIO#': ('boxed', 'state'), 'catch#': ('closure', 'closure', 'state'),
                             'unmaskAsyncExceptions#': ('closure', 'state'),
                             'maskAsyncExceptions#': ('closure', 'state'),
                             'maskUninterruptible#': ('closure', 'state'),
                             'getMaskingState#': ('state',)}[function[1]]
                    actual = [self.expression_rep(argument) for argument in arguments]
                    expected_flags = [isinstance(rep, dict) and rep.get('primReps') == ['BoxedRep (Just Lifted)']
                                      for rep in actual]
                    if (len(actual) != len(roles) or flags != expected_flags or
                            any(not exception_role(rep, role) for rep, role in zip(actual, roles))):
                        self.issue('primitive-representation', owner, path,
                                   function[1] + ': exact boxed exception and State# arguments required')
                    fields = proof.get('components') if isinstance(proof, dict) else None
                    if not (self.is_tuple(proof) and proof.get('kind') == 'unknown' and
                            isinstance(fields, list) and len(fields) == 2 and
                            exception_role(fields[0], 'state') and
                            (function[1] != 'getMaskingState#' or exception_role(fields[1], 'int')) and
                            tuple_input_proof_error(proof, allow_vectors=True, allow_addresses=True,
                                                    allow_sums=True) is None):
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
                        if role == 'int64':
                            return kind == 'long' and reps == ['Int64Rep']
                        if role in ('threadId', 'byteArray', 'threadArray'):
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
                compact = (self.cap.get('managedCompactPrimitives', {}).get(function[1]) or
                           self.cap.get('managedCompactImagePrimitives', {}).get(function[1])) if function[0] == 'prim' else None
                if compact is not None:
                    def compact_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role in ('int', 'word'):
                            return kind == 'long' and reps == [('Int' if role == 'int' else 'Word') + 'Rep']
                        if role == 'region':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        if role == 'address':
                            return kind == 'address' and reps == ['AddrRep']
                        if role == 'boxed':
                            return kind in ('data', 'object', 'closure') and reps in (
                                ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])
                        return kind in ('data', 'object', 'closure') and reps == ['BoxedRep (Just Lifted)']
                    expected = compact['arguments']
                    valid_args = (len(arguments) == len(expected) and flags == [r == 'lifted' for r in expected] and
                                  all(compact_role(self.expression_rep(a), r) for a, r in zip(arguments, expected)))
                    fields = proof.get('components') if isinstance(proof, dict) else None
                    valid_result = compact_role(proof, 'state') if compact['result'] == 'state' else (
                        self.is_tuple(proof) and isinstance(fields, list) and len(fields) == 2 and
                        compact_role(fields[0], 'state') and compact_role(fields[1], compact['result']) and
                        proof.get('primReps') == fields[1].get('primReps'))
                    if isinstance(compact['result'], list):
                        valid_result = (self.is_tuple(proof) and isinstance(fields, list) and
                            len(fields) == len(compact['result']) and
                            all(compact_role(rep, role) for rep, role in zip(fields, compact['result'])) and
                            proof.get('primReps') == [r for rep in fields for r in rep['primReps']])
                    if not valid_args or not valid_result:
                        self.issue('primitive-representation', owner, path, function[1] + ': invalid compact signature')
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
                    if (function[1] == 'addCFinalizerToWeak#' and arguments and
                            arguments[0][0] == 'lit' and arguments[0][1] != 'function-addr'):
                        self.issue('primitive-representation', owner, path,
                                   'addCFinalizerToWeak#: data addresses cannot denote a C finalizer')
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
                stable_name = self.cap.get('managedStableNamePrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                if stable_name is not None:
                    def name_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role == 'int':
                            return kind == 'long' and reps == ['IntRep']
                        if role == 'name':
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        return role == 'boxed' and kind in ('object', 'data', 'closure') and reps in (
                            ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])
                    actual = [self.expression_rep(argument) for argument in arguments]
                    expected = stable_name['arguments']
                    expected_flags = [isinstance(rep, dict) and rep.get('primReps') == ['BoxedRep (Just Lifted)'] for rep in actual]
                    if len(actual) != len(expected) or flags != expected_flags or any(
                            not name_role(rep, role) for rep, role in zip(actual, expected)):
                        self.issue('primitive-representation', owner, path, function[1] + ': StableName# arguments required')
                    output = stable_name['result']
                    if isinstance(output, list):
                        fields = proof.get('components') if isinstance(proof, dict) else None
                        valid = self.is_tuple(proof) and isinstance(fields, list) and len(fields) == 2 and all(
                            name_role(rep, role) for rep, role in zip(fields, output)) and proof.get('primReps') == fields[1]['primReps']
                    else:
                        valid = name_role(proof, output)
                    if not valid:
                        self.issue('primitive-representation', owner, path, function[1] + ': StableName# result required')
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
                        if role == 'flag':
                            return kind == 'long' and reps == ['IntRep']
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
                stm = self.cap.get('managedSTMPrimitives', {}).get(function[1]) if function[0] == 'prim' else None
                mvar = stm or (self.cap.get('managedMVarPrimitives', {}).get(function[1]) if function[0] == 'prim' else None)
                cell_family = 'STM' if stm else 'MVar'
                if mvar is not None:
                    def mvar_role(rep, role):
                        if not isinstance(rep, dict) or 'aggregate' in rep or 'vector' in rep or is_vector(rep):
                            return False
                        kind, reps = rep.get('kind'), rep.get('primReps')
                        if role == 'state':
                            return kind == 'void' and reps == []
                        if role in ('mvar', 'tvar'):
                            return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
                        if role == 'action':
                            return kind == 'closure' and reps == ['BoxedRep (Just Lifted)']
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
                        self.issue('primitive-representation', owner, path, function[1] + ': exact ' + cell_family + ' arguments required')
                    # An occurrence cannot manufacture the MVar role from a
                    # contradictory concrete local/global binding proof.
                    for argument, rep, role in zip(arguments, actual, expected):
                        stored = (bound.get(argument[1]) if argument[1] in bound else
                                  self.bindings.get(argument[1], {}).get('rep')) if argument[0] == 'var' else None
                        registers = stored.get('primReps') if isinstance(stored, dict) else None
                        if registers == ['BoxedRep Nothing'] and isinstance(rep, dict) and rep.get('primReps') in (
                                ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)']):
                            stored = dict(stored, primReps=rep['primReps'])
                        # An erased STM newtype cast refines an opaque lifted
                        # object to its function type without changing carrier,
                        # levity or evaluatedness. Runtime forcing still checks
                        # that the value is actually a closure.
                        if (role == 'action' and isinstance(stored, dict) and stored.get('kind') == 'object' and
                                stored.get('primReps') == ['BoxedRep (Just Lifted)']):
                            stored = dict(stored, kind='closure')
                        if isinstance(registers, list) and (
                                self.shape(stored) != self.shape(rep) or
                                stored.get('kind') != 'unknown' and not mvar_role(stored, role)):
                            self.issue('primitive-representation', owner, path,
                                       function[1] + ': ' + cell_family + ' argument contradicts its binding proof')
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
                        self.issue('primitive-representation', owner, path, function[1] + ': exact ' + cell_family + ' result required')
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
                    if vector_operation.startswith('shuffle') and len(arguments) == 3:
                        try:
                            shuffle_indices(arguments[2], result['vector']['lanes'])
                        except ValueError as error:
                            self.issue('vector-shape', owner, path, str(error))
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
                elif (is_vector(proof) and not vector_memory and not arithmetic_exception and
                        not (function[0] == 'prim' and function[1] in ('keepAlive#', 'raise#', 'raiseIO#')) and not (function[0] not in ('prim', 'con') and
                        self.supported_vector(proof, 'join-results' if isinstance(target, dict) and '_join_arity' in target else 'results'))):
                    self.issue('vector-boundary', owner, path, 'vector call result')
                if enum_application or data_tag:
                    self.expression_metadata(function, owner, path + '/function')
                    self.primitives.setdefault(function[1], []).append(dict(self.location(owner, path), arity=len(arguments)))
                elif self.polyglot_call(expr, bound, owner, path):
                    # The descriptor was emitted for this direct GHC FCallId,
                    # and the runtime links this exact versioned symbol.
                    self.expression_metadata(function, owner, path + '/function')
                else:
                    yield self._walk(function, bound, owner, path + '/function', len(arguments), proof if tuple_constructor or sum_constructor else None)
                for index, argument in enumerate(arguments):
                    constructor = self.constructors.get(function[1], {}) if function[0] == 'con' else {}
                    fields = constructor.get('fieldTypes')
                    heap_field = (fields[index] if constructor.get('kind', 'boxed') == 'boxed' and
                                  constructor.get('arity') == len(arguments) and isinstance(fields, list) and
                                  len(fields) == len(arguments) else None)
                    heap_aggregate = self.supported_heap_aggregate(heap_field)
                    if heap_aggregate:
                        self.compare_shapes(heap_field, self.effective_rep(argument, bound), owner,
                                            f'{path}/arguments/{index}/rep', component=True)
                        if not isinstance(flags, list) or index >= len(flags) or flags[index] is not False:
                            self.issue('application-levity', owner, path, 'Aggregate heap field must be unlifted')
                    if sum_constructor and is_sum(proof) and sum_proof_error(proof) is None:
                        try:
                            selected = sum_constructor_tag(self.constructors.get(function[1]), len(arguments), proof) - 1
                            expected = proof['alternatives'][selected]
                            self.compare_shapes(expected, self.effective_rep(argument, bound), owner, f'{path}/arguments/{index}/rep', component=True)
                            if not isinstance(flags, list) or index >= len(flags) or not payload_levity_matches(
                                    expected, self.effective_rep(argument, bound), flags[index]):
                                self.issue('application-levity', owner, path, 'Sum payload levity mismatch')
                        except ValueError as error:
                            self.issue('constructor-arity', owner, path, str(error))
                    if not sum_constructor and not heap_aggregate and is_sum(self.effective_rep(argument, bound)):
                        join = isinstance(target, dict) and '_join_arity' in target
                        capability = ('aggregateResults' if tuple_constructor else
                                      'aggregateJoinInputs' if join else 'aggregateInputs')
                        if (function[0] in ('prim', 'con') and not tuple_constructor or
                                not self.supported_sum(self.effective_rep(argument, bound), capability)):
                            self.issue('aggregate-boundary', owner, f'{path}/arguments/{index}', 'unboxed-sum argument')
                        if not isinstance(flags, list) or index >= len(flags) or flags[index] is not False:
                            self.issue('application-levity', owner, path, 'Sum argument must be unlifted')
                    if tuple_constructor and self.is_tuple(proof) and isinstance(proof.get('components'), list):
                        components = proof['components']
                        if index < len(components):
                            self.compare_shapes(components[index], self.effective_rep(argument, bound), owner,
                                                f'{path}/arguments/{index}/rep', component=True)
                    if not vector_operation and not vector_memory and (is_vector(self.expression_rep(argument)) or argument[0] == 'var' and is_vector(bound.get(argument[1]))):
                        actual = self.effective_rep(argument, bound)
                        join = isinstance(target, dict) and '_join_arity' in target
                        boxed_constructor = function[0] == 'con' and self.constructors.get(function[1], {}).get('kind', 'boxed') == 'boxed'
                        sum_payload = sum_constructor and self.supported_sum(proof, 'aggregateResults')
                        boundary = ('tuple-fields' if tuple_constructor or sum_payload else 'heap-fields' if boxed_constructor else
                                    'join-arguments' if join else 'arguments')
                        if not ((tuple_constructor or sum_payload or boxed_constructor or function[0] != 'prim' and function[0] != 'con')
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
                            supported = self.supported_tuple_join_input(argument_rep) if join else (
                                heap_aggregate or ordinary and self.supported_tuple_input(argument_rep) or
                                arithmetic_exception and self.is_empty_tuple(argument_rep))
                            if not supported:
                                self.issue('aggregate-boundary', owner, f'{path}/arguments/{index}', 'unboxed-tuple argument')
                            if (self.is_tuple(argument_rep) and isinstance(flags, list) and
                                    index < len(flags) and flags[index] is not False):
                                self.issue('application-levity', owner, f'{path}/arguments/{index}', 'Tuple argument must be unlifted')
                    yield self._walk(argument, bound, owner, f'{path}/arguments/{index}', sum_payload=sum_constructor or sum_payload)
            elif tag == 'let':
                recursive, group = expr[1], expr[2]
                if type(recursive) is not bool:
                    raise ValueError('Let recursive flag must be boolean')
                ids = self.binder_ids(group, owner, path + '/bindings')
                local = self.function_scope(group, bound, recursive)
                for index, binding in enumerate(group):
                    if not isinstance(binding, dict):
                        continue
                    stored = binding.get('rep')
                    actual = self.effective_rep(binding.get('expr'), local if recursive else bound)
                    if 'joinValueArity' not in binding:
                        sum_value = is_sum(stored) or is_sum(actual)
                        tuple_value = self.is_tuple(stored) or self.is_tuple(actual)
                        if sum_value and not (not recursive and binding.get('lifted') is False and
                                self.supported_sum(stored, 'aggregateLetBindings')):
                            self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-sum let binding')
                        if tuple_value and not (not recursive and binding.get('lifted') is False and
                                'unboxed-tuple' in self.cap.get('aggregateLetBindings', []) and
                                self.supported_tuple_input(stored, 'aggregateLetBindings')):
                            self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-tuple let binding')
                        if sum_value or tuple_value:
                            self.compare_shapes(stored, actual, owner, f'{path}/bindings/{index}/rep')
                    if ('joinValueArity' not in binding and is_vector(binding.get('rep')) and
                            not self.supported_vector(binding['rep'], 'let-bindings')):
                        self.issue('vector-boundary', owner, f'{path}/bindings/{index}', 'vector let binding')
                    if is_vector(binding.get('rep')) and binding.get('lifted') is not False:
                        self.issue('application-levity', owner, f'{path}/bindings/{index}', 'Vector let binding must be unlifted')
                    if 'joinValueArity' in binding:
                        captured = (self.free_variables(binding['expr']) - (ids if recursive else set())) & bound.keys()
                        if any(self.is_sum_value(bound[key]) and not self.supported_sum(bound[key], 'aggregateJoinCaptures')
                               for key in captured):
                            self.issue('aggregate-boundary', owner, f'{path}/bindings/{index}', 'unboxed-sum join capture')
                        if any(self.is_vector_value(bound[key]) and not self.supported_vector(bound[key], 'join-captures')
                               for key in captured):
                            self.issue('vector-boundary', owner, f'{path}/bindings/{index}', 'vector join capture')
                        tuple_captures = [bound[key] for key in captured if self.is_tuple_value(bound[key])]
                        if tuple_captures and ('unboxed-tuple' not in self.cap.get('aggregateJoinCaptures', []) or
                                               not all(self.supported_tuple_input(rep, 'aggregateJoinCaptures') for rep in tuple_captures)):
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
                    yield self._walk(binding.get('expr'), local if recursive else bound,
                              owner, f'{path}/bindings/{index}/rhs', join_prefix=binding.get('joinValueArity', 0), sum_payload=sum_payload)
                self.compare_shapes(self.expression_rep(expr), self.effective_rep(expr[3], local)
                                    if sum_payload or self.is_tuple(self.expression_rep(expr)) else self.expression_rep(expr[3]), owner, path + '/body/rep')
                yield self._walk(expr[3], local, owner, path + '/body', sum_payload=sum_payload)
            elif tag == 'case':
                yield self._walk(expr[1], bound, owner, path + '/scrutinee', sum_payload=sum_payload)
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
                if self.is_tuple(binder_proof):
                    if len(expr[3]) > 1:
                        self.issue('aggregate-boundary', owner, path, 'unboxed-tuple requires at most one alternative')
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
                                selected = sum_constructor_tag(self.constructors.get(value), len(ids), binder_proof)
                                if selected in sum_tags:
                                    raise ValueError('Duplicate sum alternative tag')
                                sum_tags.add(selected)
                                alternatives = binder_proof.get('alternatives')
                                if (not isinstance(alternatives, list) or len(alternatives) < 2 or
                                        not isinstance(records, list) or len(records) != 1 or not isinstance(records[0], dict)):
                                    raise ValueError('Sum alternative requires one exact payload binder')
                                expected = alternatives[selected - 1]
                                self.compare_shapes(expected, records[0].get('rep'), owner, altpath + '/binders/0/rep', component=True)
                                if not payload_levity_matches(expected, records[0].get('rep'), records[0].get('lifted')):
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
                        if (self.constructors.get(value, {}).get('kind', 'boxed') == 'boxed' and
                                isinstance(fields, list) and any(is_vector(field) or self.supported_heap_aggregate(field) for field in fields) and
                                (not isinstance(records, list) or len(records) != len(fields))):
                            self.issue('alternative-binder-metadata', owner, altpath,
                                       'Aggregate/vector constructor pattern requires every exact field binder')
                        # Polymorphic (#,#) fieldTypes are unknown. Its instantiated
                        # case-binder components below carry the exact vector proof.
                        if (self.constructors.get(value, {}).get('kind', 'boxed') == 'boxed' and
                                isinstance(fields, list) and isinstance(records, list) and len(fields) == len(records)):
                            for field, (expected, record) in enumerate(zip(fields, records)):
                                actual = record.get('rep') if isinstance(record, dict) else None
                                if (is_vector(expected) or is_vector(actual) or self.supported_heap_aggregate(expected)
                                        or is_sum(actual) or self.is_tuple(actual)):
                                    self.compare_shapes(expected, actual, owner,
                                                        f'{altpath}/binders/{field}/rep', component=True)
                                    if not isinstance(record, dict) or record.get('lifted') is not False:
                                        self.issue('application-levity', owner, altpath,
                                                   f'Aggregate/vector constructor binder {field} must be unlifted')
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
                        if value[0] in ('bignat', 'rubbish'):
                            self.issue('alternative-kind', owner, altpath, 'BigNat/rubbish literal alternatives are invalid GHC Core')
                        if value[0] in ('float', 'float-bits', 'double', 'double-bits', 'function-addr', 'data-addr'):
                            self.issue('alternative-kind', owner, altpath,
                                       'Floating and C label literal alternatives are invalid GHC Core')
                    elif kind != 'default':
                        self.issue('alternative-kind', owner, altpath, kind)
                    if self.is_tuple(binder_proof) and (kind not in ('data', 'default') or kind == 'default' and ids):
                        self.issue('aggregate-shape', owner, altpath, 'Invalid tuple alternative')
                    local = bound | {expr[2]: binder_proof} | dict.fromkeys(ids)
                    if isinstance(records, list):
                        local.update(self.binder_scope(records))
                    self.compare_shapes(self.expression_rep(expr), self.effective_rep(rhs, local)
                                        if sum_payload or self.is_tuple(self.expression_rep(expr)) else self.expression_rep(rhs), owner, altpath + '/body/rep')
                    yield self._walk(rhs, local, owner, altpath + '/body', sum_payload=sum_payload)
            elif tag == 'con':
                if self.constructors.get(expr[1], {}).get('kind') == 'unboxed-sum' and primitive_arity != 1:
                    self.issue('aggregate-boundary', owner, path, 'Sum constructor requires a saturated application')
                info = self.constructors.get(expr[1], {})
                if (any(contains_tuple(field) or contains_sum(field) for field in info.get('fieldTypes', [])) and
                        info.get('kind', 'boxed') == 'boxed' and primitive_arity != info.get('arity')):
                    self.issue('aggregate-boundary', owner, path, 'Aggregate-field constructor requires a saturated application')
                self.constructor(expr[1], owner, path, True, expr[2], tuple_result or self.expression_rep(expr))
            elif tag == 'prim':
                name = expr[1]
                if name in ('waitRead#', 'waitWrite#'):
                    self.reference('ghc-internal:GHC.Internal.Event.Thread.blockedOnBadFD', owner, path + '/badFD')
                if name == 'atomically#':
                    self.reference('ghc-internal:GHC.Internal.Control.Exception.Base.nestedAtomically', owner,
                                   path + '/nested-atomically')
                self.primitives.setdefault(name, []).append(dict(self.location(owner, path), arity=primitive_arity))
                if name in ARITHMETIC_EXCEPTIONS:
                    self.reference(ARITHMETIC_EXCEPTIONS[name], owner, path + '/implicit-exception')
                if name in ('compactAdd#', 'compactAddWithSharing#'):
                    for payload in ('cannotCompactFunction', 'cannotCompactPinned', 'cannotCompactMutable'):
                        self.reference('ghc-internal:GHC.Internal.IO.Exception.' + payload,
                                       owner, path + '/implicit-compaction-exception')
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
        """Admit the erased IO state transformer and discard its lifted answer."""
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
                # Known saturation returning an unboxed result cannot produce an IO action.
                result = self.known_result(expr[1])
                if original is not None and isinstance(result, dict) and (result.get('aggregate') is not None or result.get('primReps') != ['BoxedRep (Just Lifted)']):
                    return []
            return None
        remaining = remaining_binders(expression)
        # A shared action thunk exposes its input/result proof only after forcing
        # its head; IoMainRoot authenticates that real closure before applying it.
        binding = self.bindings[key]
        if remaining is None and binding.get('rep', {}).get('primReps') == ['BoxedRep (Just Lifted)']:
            return
        binder = remaining[0] if isinstance(remaining, list) and len(remaining) == 1 else None
        state = formals[0] if isinstance(formals, list) and len(formals) == 1 else None
        components = result.get('components') if isinstance(result, dict) and result.get('aggregate') == 'unboxed-tuple' else None
        state_result = components[0] if isinstance(components, list) and len(components) == 2 else None
        answer_result = components[1] if isinstance(components, list) and len(components) == 2 else None
        if (not isinstance(result, dict) or result.get('kind') != 'unknown' or
                result.get('primReps') != ['BoxedRep (Just Lifted)'] or
                not isinstance(binder, dict) or binder.get('type') != 'State# RealWorld' or
                not isinstance(state, dict) or state.get('kind') != 'void' or state.get('primReps') != [] or
                not isinstance(state_result, dict) or state_result.get('kind') != 'void' or state_result.get('primReps') != [] or
                not isinstance(answer_result, dict) or answer_result.get('primReps') != ['BoxedRep (Just Lifted)']):
            self.issue('io-main-boundary', key, '/entry', 'requires State# RealWorld -> (# State#, lifted answer #)')

    def run(self, entries, io_main=False):
        # Executable shutdown is another exact IO () root in the same package
        # closure. Each root receives the same boundary check below.
        roots = []
        for entry in entries:
            candidates = ([entry] if entry in self.bindings else list(self.store.named(entry)) if self.store is not None
                          else [k for k, b in self.bindings.items() if b.get('name') == entry])
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
                    # The public protocol consumes the same retained logical
                    # signature as guest transport, including aliases and PAPs.
                    # Missing legacy evidence is not a license to infer a shape.
                    try:
                        signature = self.known_function_signature(expression)
                    except (IndexError, KeyError, TypeError):
                        signature = None  # walk() diagnoses malformed expressions.
                    if signature is not None:
                        for index, proof in enumerate(signature[0]):
                            if proof is not None:
                                self.representation(proof, key, f'/entry/arguments/{index}')
                        if signature[1] is not None:
                            self.representation(signature[1], key, '/entry/result')
                self._discover(key)
                del expression, formals, result
        # Retention does not call an export. The current backends must still
        # lower its closure body, so unsupported retained bodies remain gaps.
        for key in self.retained_exports:
            self._discover(key)
        reported_archives = self.store.members('reported-archives', '') if self.store is not None else set()
        while self.store.pending_count() if self.store is not None else self.queue:
            key = self.store.pop_pending() if self.store is not None else self.queue.popleft()
            if self.store is None:
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
            if not known_levity_or_boxed_pointer(binding.get('lifted'), binding.get('rep')):
                self.issue('unknown-binder-levity', key, '/binding', key)
            self.walk(binding.get('expr'), {}, key, '/expr', join_prefix=binding.get('joinValueArity', 0))
            del binding
        if self.store is not None:
            return self._stream_report(roots)
        for issue in chain(self.issues, self.unresolved_native_symbols):
            if issue['owner'] in self.predecessors:
                issue['reachableVia'] = self.reachable_via(issue['owner'])
        missing = [dict(id=key, reachableVia=self.reachable_via(uses[0]['owner']) + [key], references=uses)
                   for key, uses in sorted(self.missing.items())]
        return dict(schema=2, audit='syntactic-reachable-core', roots=roots, retainedExports=self.retained_exports,
                    capabilityProfile=self.cap.get('name'), accepted=not self.issues and not missing,
                    summary=dict(suppliedBindings=len(self.bindings), reachableBindings=len(self.reachable),
                                 missingGlobals=len(missing), issues=len(self.issues), unresolvedNativeSymbols=len(self.unresolved_native_symbols)),
                    reachableBindings=[dict(id=k, source=self.sources[k], predecessor=self.predecessors[k]) for k in self.reachable],
                    dependencies=self.edges, missingGlobals=missing,
                    runtimeExternals=[dict(id=key, uses=[edge for edge in self.edges if edge['dependency'] == key])
                                      for key in sorted((set(self.cap.get('externalBindings', [])) - self.bindings.keys()) & {edge['dependency'] for edge in self.edges})],
                    primitives=[dict(name=k, expectedArity=self.cap['primitives'].get(k), uses=v) for k, v in sorted(self.primitives.items())],
                    foreignOwnership=self.foreign_ownership.provenance, foreignCalls=self.foreign_calls,
                    constructors=[dict(id=k, metadata=self.constructors.get(k), uses=v) for k, v in sorted(self.used_constructors.items())],
                    literals=[v for _, v in sorted(self.literals.items())], issues=self.issues,
                    unresolvedNativeSymbols=self.unresolved_native_symbols,
                    limits=['All syntactically reachable branches and local RHSs are audited, including lazy error paths.',
                            'Acceptance checks well-formed loadable Core; unresolvedNativeSymbols must resolve if their expressions execute.',
                            'Acceptance does not establish termination, branch feasibility, native symbol availability, or runtime correctness.'])

    def _stream_report(self, roots):
        store = self.store

        def events(kind, group=_ANY):
            return _StreamArray(lambda: store.events(kind, group=group))

        def issues():
            for issue in store.events('issues'):
                if issue['owner'] in self.predecessors:
                    issue['reachableVia'] = self.reachable_via(issue['owner'])
                yield issue

        def unresolved_native_symbols():
            for item in store.events('unresolved-native-symbols'):
                item['reachableVia'] = self.reachable_via(item['owner'])
                yield item

        def missing():
            for key in store.event_groups('missing'):
                with closing(store.events('missing', group=key)) as uses:
                    first = next(uses)
                yield dict(id=key, reachableVia=self.reachable_via(first['owner']) + [key], references=events('missing', key))

        def runtime_externals():
            for key in sorted(set(self.cap.get('externalBindings', []))):
                if key not in self.bindings and store.event_count('edges', group=key):
                    yield dict(id=key, uses=events('edges', key))

        def literals():
            for key in store.event_groups('literal-uses'):
                yield dict(kind=key, examples=events('literal-examples', key), uses=events('literal-uses', key))

        return _StreamReport(schema=2, audit='syntactic-reachable-core', roots=roots,
            retainedExports=_StreamArray(lambda: iter(self.retained_exports)), capabilityProfile=self.cap.get('name'),
            accepted=not self.issues and not self.missing,
            summary=dict(suppliedBindings=len(self.bindings), reachableBindings=len(self.reachable),
                         missingGlobals=len(self.missing), issues=len(self.issues), unresolvedNativeSymbols=len(self.unresolved_native_symbols)),
            reachableBindings=_StreamArray(lambda: (dict(id=key, source=self.sources[key], predecessor=predecessor)
                for key, predecessor in store.reachable())),
            dependencies=events('edges'), missingGlobals=_StreamArray(missing),
            runtimeExternals=_StreamArray(runtime_externals),
            primitives=_StreamArray(lambda: (dict(name=key, expectedArity=self.cap['primitives'].get(key), uses=events('primitives', key))
                for key in store.event_groups('primitives'))),
            foreignOwnership=self.foreign_ownership.provenance, foreignCalls=events('foreign'),
            constructors=_StreamArray(lambda: (dict(id=key, metadata=self.constructors.get(key), uses=events('constructors', key))
                for key in store.event_groups('constructors'))),
            literals=_StreamArray(literals), issues=_StreamArray(issues),
            unresolvedNativeSymbols=_StreamArray(unresolved_native_symbols),
            limits=['All syntactically reachable branches and local RHSs are audited, including lazy error paths.',
                    'Acceptance checks well-formed loadable Core; unresolvedNativeSymbols must resolve if their expressions execute.',
                            'Acceptance does not establish termination, branch feasibility, native symbol availability, or runtime correctness.'])


def _input_modules(package_manifest, files, store=None, manifest_identity=None):
    if package_manifest:
        with core_package_manifest.open_modules(package_manifest, audit_archives=True) as modules:
            recorded = False
            for source, module in modules:
                if manifest_identity is not None and not recorded:
                    manifest_identity.update(modules.manifest_identity)
                if store is not None and not recorded:
                    store.put_record('input-provenance', 'package-manifest', modules.manifest_identity)
                recorded = True
                yield source, module
                del module
            if store is not None:
                store.put_record('input-completion', 'package-manifest', dict(complete=modules.complete))
    for index, path in enumerate(files):
        data = sys.stdin.buffer.read() if str(path) == '-' else path.read_bytes()
        module = core_package_manifest.inspect_cbd(data)
        if store is not None:
            store.put_record('input-provenance', 'loose:' + str(index),
                dict(path='-' if str(path) == '-' else str(path.resolve()), sha256=hashlib.sha256(data).hexdigest()))
        del data
        yield str(path), module
        del module


def _audit_inputs(package_manifest, files, capabilities, store=None, runtime=None, ownership_command=None):
    # Read the selected unit from the same validated manifest snapshot that
    # supplies the modules. Peeking retains only the current module, never the
    # stream, and keeps manifest diagnostics before module-registration issues.
    identity = {}
    with closing(_input_modules(package_manifest, files, store, identity)) as modules:
        first = next(modules, None)
        bridge_unit = identity.get('manifest', {}).get('foreignExceptionBridgeUnit')
        inputs = chain(() if first is None else (first,), modules)
        del first
        return Audit(inputs, capabilities, bridge_unit, store=store, runtime=runtime, ownership_command=ownership_command)


def _tool_provenance(capabilities, capability_bytes):
    root = Path(__file__).resolve().parent
    paths = [Path(__file__), *sorted(root.glob('core_*.py')),
             root.parent / 'src/main/resources/thc/scalar-primop-signatures.json',
             root.parent / 'src/test/resources/thc/polyglot-abi.json']
    return dict(format='thc-audit-invocation-v1', python=sys.version, sqlite=sqlite3.sqlite_version,
        capabilities=dict(path=str(capabilities.resolve()), sha256=hashlib.sha256(capability_bytes).hexdigest()),
        tools=[dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in paths],
        storage=dict(pageCacheKiB=4096, expressionCacheBytes=8 * 1024 * 1024, expressionCacheEntries=32, batchRows=256))


def _emit_report(report, output, store=None):
    if output is None:
        write_report(report, sys.stdout)
        sys.stdout.flush()
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.' + output.name + '.', suffix='.partial', dir=output.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as stream:
            write_report(report, stream)
        if store is not None: store.checkpoint('report-written')
        os.replace(temporary, output)
    except BaseException:
        print('Incomplete report preserved at ' + temporary, file=sys.stderr)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('modules', nargs='*', help='CBD modules, directories containing *.cbd modules, or - for binary stdin')
    parser.add_argument('--module-list', action='append', type=Path, default=[], help='Read an exact newline-delimited module manifest; relative paths are relative to the manifest')
    parser.add_argument('--package-manifest', type=Path, help='Validate exact GHC-unit Core modules, including ZIP bundles, before combining with any loose consumer modules')
    parser.add_argument('--entry', action='append', required=True, help='Exact global id or unambiguous occurrence name; repeatable')
    parser.add_argument('--io-main', action='store_true', help='Validate the exact IO state-transformer host entry contract instead of the scalar host result')
    parser.add_argument('--capabilities', type=Path, default=Path(__file__).with_name('core-capabilities.json'))
    parser.add_argument('--output', type=Path, help='Atomically publish the complete JSON report here (otherwise stdout)')
    ownership = parser.add_mutually_exclusive_group()
    ownership.add_argument('--ownership-command', type=Path, help='Explicit command JSON prepared by ./gradlew foreignOwnershipCommand (or THC_FOREIGN_OWNERSHIP)')
    ownership.add_argument('--runtime', type=Path, help='Matching built THC launcher for one cold foreign-ownership batch (default THC_RUNTIME or build/install/thc/bin/thc)')
    storage = parser.add_mutually_exclusive_group()
    storage.add_argument('--store', type=Path, help='Fresh SQLite working catalogue; existing paths are never reused')
    storage.add_argument('--eager', action='store_true', help='Use the original in-memory path for small-input equivalence checks')
    args = parser.parse_args()
    try:
        files, lists = [], []
        for supplied in args.modules:
            path = Path(supplied)
            files.extend(sorted(path.glob('*.cbd')) if path.is_dir() else [path])
        for manifest in args.module_list:
            data = manifest.read_bytes()
            text = data.decode('utf-8')
            lists.append(dict(path=str(manifest.resolve()), sha256=hashlib.sha256(data).hexdigest(), text=text))
            for line in text.splitlines():
                if line.strip():
                    path = Path(line.strip())
                    files.append(path if path.is_absolute() else manifest.parent / path)
        files = list(dict.fromkeys(files))
        if not files and not args.package_manifest:
            parser.error('Supply modules or --module-list')
        capability_bytes = args.capabilities.read_bytes()
        capabilities = json.loads(capability_bytes.decode('utf-8'))
        if args.eager:
            report = _audit_inputs(args.package_manifest, files, capabilities, runtime=args.runtime, ownership_command=args.ownership_command).run(args.entry, io_main=args.io_main)
            _emit_report(report, args.output)
        else:
            if args.store is not None:
                store_path = args.store
                store_path.parent.mkdir(parents=True, exist_ok=True)
            else:
                if args.output: args.output.parent.mkdir(parents=True, exist_ok=True)
                directory = tempfile.mkdtemp(prefix='thc-core-audit-', dir=args.output.parent if args.output else None)
                store_path = Path(directory) / 'catalogue.sqlite'
            provenance = _tool_provenance(args.capabilities, capability_bytes)
            provenance.update(entries=args.entry, ioMain=args.io_main, moduleLists=lists,
                packageManifest=str(args.package_manifest) if args.package_manifest else None,
                looseModules=[str(path) for path in files])
            with AuditStore(store_path, provenance) as store:
                print('Audit working catalogue: ' + str(store_path), file=sys.stderr)
                auditor = _audit_inputs(args.package_manifest, files, capabilities, store, args.runtime, args.ownership_command)
                store.checkpoint('walking')
                report = auditor.run(args.entry, io_main=args.io_main)
                store.checkpoint('walked')
                _emit_report(report, args.output, store)
            if args.store is None and report['accepted']:
                shutil.rmtree(directory)
    except (OSError, ValueError, TypeError, sqlite3.Error, AuditStoreError) as error:
        parser.error(str(error))
    print(json.dumps(dict(accepted=report['accepted'], **report['summary'])), file=sys.stderr)
    return 0 if report['accepted'] else 1


def write_report(report, stream):
    # Large application reports must not materialize both the encoder's full
    # chunk list and a second, joined JSON string beside the loaded Core.
    if isinstance(report, _StreamReport):
        _write_stream_json(report, stream)
    else:
        json.dump(report, stream, indent=2)
    stream.write('\n')


def _write_stream_json(value, stream, level=0):
    # Match JSONEncoder(indent=2), including empty containers, while allowing
    # repeatable cursor sections at any array nesting level. Never list() them.
    if isinstance(value, dict):
        stream.write('{')
        first = True
        for key, item in value.items():
            stream.write(('\n' if first else ',\n') + '  ' * (level + 1))
            stream.write(json.dumps(key) + ': ')
            _write_stream_json(item, stream, level + 1)
            first = False
        if not first: stream.write('\n' + '  ' * level)
        stream.write('}')
    elif isinstance(value, (list, tuple, _StreamArray)):
        stream.write('[')
        first = True
        for item in value:
            stream.write(('\n' if first else ',\n') + '  ' * (level + 1))
            _write_stream_json(item, stream, level + 1)
            first = False
        if not first: stream.write('\n' + '  ' * level)
        stream.write(']')
    else:
        json.dump(value, stream)


if __name__ == '__main__':
    sys.exit(main())
