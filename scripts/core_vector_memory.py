"""Exact local Int32X4 ByteArray intrinsics; no vector aggregate transport.

The pinned exporter places its sole physical vector annotation on the logical
State/vector tuple too. Only an immediate, exactly checked read case accepts it.
"""
from core_vectors import VECTOR32_REP, signature_matches

STATE = dict(kind='void', primReps=[], evaluated=True)
ARRAY = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
INDEX = dict(kind='long', primReps=['IntRep'], evaluated=True)
READS = {'readInt32X4Array#', 'readInt32ArrayAsInt32X4#'}
INDICES = {'indexInt32X4Array#', 'indexInt32ArrayAsInt32X4#'}
WRITES = {'writeInt32X4Array#', 'writeInt32ArrayAsInt32X4#'}
OPERATIONS = READS | INDICES | WRITES


def require(condition, detail):
    if not condition:
        raise ValueError('Invalid local vector memory intrinsic: ' + detail)


def representation(expr):
    index = {'var': 2, 'lit': 3, 'app': 6, 'lam': 3, 'let': 4,
             'case': 4, 'con': 3, 'prim': 2, 'void': 1}.get(expr[0])
    metadata = expr[index] if index is not None and len(expr) > index else None
    return metadata.get('rep') if isinstance(metadata, dict) else None


def exact(expected, actual):
    return (isinstance(actual, dict) and type(actual.get('evaluated')) is bool and
            signature_matches(expected, actual) and
            (expected.get('kind') != 'vector' or
             type(actual.get('vector', {}).get('lanes')) is int))


def validate_arguments(name, arguments, flags):
    expected = [ARRAY, INDEX] + ([STATE] if name in READS else [VECTOR32_REP, STATE] if name in WRITES else [])
    require(isinstance(arguments, list) and len(arguments) == len(expected), 'argument arity')
    require(isinstance(flags, list) and len(flags) == len(expected) and all(f is False for f in flags), 'unlifted flags')
    for argument, wanted in zip(arguments, expected):
        require(isinstance(argument, list) and argument, 'argument expression')
        require(exact(wanted, representation(argument)), 'argument representation')


def validate_direct(name, arguments, flags, result):
    require(name in INDICES | WRITES, 'read requires an immediate exact case')
    validate_arguments(name, arguments, flags)
    require(exact(STATE if name in WRITES else VECTOR32_REP, result), 'result representation')


def read_result(proof, binder=False):
    require(isinstance(proof, dict), 'missing read result proof')
    shape = proof.get('vector')
    require(isinstance(shape, dict) and type(shape.get('lanes')) is int and
            shape == VECTOR32_REP['vector'], 'aggregate vector annotation')
    require(proof.get('kind') == 'unknown' and proof.get('aggregate') == 'unboxed-tuple' and
            proof.get('primReps') == VECTOR32_REP['primReps'] and
            type(proof.get('evaluated')) is bool and (not binder or proof['evaluated']), 'read result shape')
    fields = proof.get('components')
    require(isinstance(fields, list) and len(fields) == 2 and
            exact(STATE, fields[0]) and exact(VECTOR32_REP, fields[1]), 'read result components')


def term_uses(value, identity):
    if isinstance(value, list):
        return ((len(value) > 1 and value[0] == 'var' and value[1] == identity) or
                any(term_uses(child, identity) for child in value))
    if isinstance(value, dict):
        return any(term_uses(child, identity) for child in value.values())
    return False


def read_case(expr, constructors):
    """None for other syntax; recognized-invalid read cases always raise."""
    if not isinstance(expr, list) or not expr or expr[0] != 'case':
        return None
    app = expr[1] if len(expr) > 1 else None
    if not isinstance(app, list) or len(app) < 2 or app[0] != 'app':
        return None
    function = app[1]
    if (not isinstance(function, list) or len(function) < 2 or function[0] != 'prim' or
            function[1] not in READS):
        return None
    require(len(app) > 6 and len(expr) > 4, 'missing application/case fields')
    name = function[1]
    validate_arguments(name, app[2], app[3])
    read_result(representation(app))
    whole = expr[2]
    require(isinstance(whole, str), 'case binder id')
    metadata = expr[4]
    require(isinstance(metadata, dict), 'case metadata')
    binder = metadata.get('binder')
    require(isinstance(binder, dict) and binder.get('id') == whole and binder.get('lifted') is False and
            'joinValueArity' not in binder, 'whole-tuple binder identity/levity')
    read_result(binder.get('rep'), True)
    alternatives = expr[3]
    require(isinstance(alternatives, list) and len(alternatives) == 1, 'requires one tuple alternative')
    alternative = alternatives[0]
    require(isinstance(alternative, list) and len(alternative) > 4, 'alternative metadata')
    require(alternative[0] == 'data' and isinstance(alternative[1], str), 'requires a tuple data alternative')
    constructor = constructors.get(alternative[1])
    require(isinstance(constructor, dict) and constructor.get('kind') == 'unboxed-tuple' and
            type(constructor.get('arity')) is int and constructor['arity'] == 2, 'requires a registered tuple2 constructor')
    ids = alternative[2]
    require(isinstance(ids, list) and len(ids) == 2 and all(isinstance(i, str) for i in ids) and
            len(set(ids)) == 2 and whole not in ids, 'pattern binder identities')
    records = alternative[4].get('binders') if isinstance(alternative[4], dict) else None
    require(isinstance(records, list) and len(records) == 2, 'ordered pattern metadata')
    for identity, wanted, record in zip(ids, [STATE, VECTOR32_REP], records):
        require(isinstance(record, dict) and record.get('id') == identity and record.get('lifted') is False and
                'joinValueArity' not in record and exact(wanted, record.get('rep')) and
                record['rep']['evaluated'] is True, 'pattern binder representation')
    body = alternative[3]
    require(isinstance(body, list) and body, 'continuation')
    require(not term_uses(body, whole), 'whole tuple binder escapes')
    return dict(operation=name, arguments=app[2], stateBinder=ids[0], vectorBinder=ids[1], body=body)
