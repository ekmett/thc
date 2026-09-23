#!/usr/bin/env python3
"""Compare executable Core after lexical alpha renaming, ignoring source/debug text.

Accept module JSON, a directory of modules, or a modules.txt manifest. Runtime
representation/WHNF/speculation/strict-field/join certificates remain included.
This is a structural check, not a general proof of equivalence of different Core.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('before', type=Path)
parser.add_argument('after', type=Path)
parser.add_argument('--output', type=Path)
parser.add_argument('--entry', help='compare only globals syntactically reachable from this unambiguous occurrence')
parser.add_argument('--strip-snapshot-prefix', action='store_true', help='ignore numeric NN- archive prefixes on module filenames')
parser.add_argument('--exclude', action='append', default=[], help='explicit module filename to omit from both sides')
args = parser.parse_args()


def load(path):
    if path.is_dir():
        files = sorted(path.glob('*.json'))
    elif path.suffix == '.json':
        files = [path]
    else:
        files = [Path(s) for s in path.read_text().splitlines() if s]
    def key(file):
        return re.sub(r'^\d+-', '', file.name) if args.strip_snapshot_prefix else file.name
    included = [file for file in files if key(file) not in args.exclude]
    assert len({key(file) for file in included}) == len(included), 'ambiguous module filenames'
    return {key(file): json.loads(file.read_text()) for file in included}


def canonical(modules):
    globals = {}
    for filename, module in sorted(modules.items()):
        names = {}
        for binding in module['bindings']:
            name = binding['name']
            index = names.get(name, 0)
            names[name] = index + 1
            globals[binding['id']] = ('global', filename, name, index)
    def binder(b):
        return {k: b[k] for k in ['lifted', 'coercion', 'rep'] if k in b}
    def metadata(raw):
        return {k: raw[k] for k in ['rep', 'resultRep'] if k in raw}
    def binding_info(b):
        return {k: b[k] for k in ['lifted', 'arity', 'rep', 'joinValueArity', 'joinResultRep'] if k in b}
    def binding(b, env):
        return [binding_info(b), expression(b['expr'], env)]
    def extend(env, ids):
        extended = dict(env)
        for key in ids:
            extended[key] = ('local', len(extended))
        return extended
    def expression(e, env):
        tag = e[0]
        result = [tag]
        meta = e[-1] if isinstance(e[-1], dict) else {}
        if tag == 'var':
            result += [env.get(e[1], globals.get(e[1], e[1]))]
        elif tag in ['prim', 'con', 'lit', 'unsupported']:
            result += e[1:-1] if isinstance(e[-1], dict) else e[1:]
        elif tag == 'app':
            result += [expression(e[1], env), [expression(a, env) for a in e[2]], e[3:6]]
        elif tag == 'lam':
            inner = extend(env, [p['id'] for p in e[1]])
            result += [[binder(p) for p in e[1]], expression(e[2], inner)]
        elif tag == 'let':
            inner = extend(env, [b['id'] for b in e[2]])
            result += [e[1], [binding(b, inner if e[1] else env) for b in e[2]], expression(e[3], inner)]
        elif tag == 'case':
            inner = extend(env, [e[2]])
            alts = []
            for alt in e[3]:
                altmeta = alt[4] if len(alt) > 4 else {}
                alts.append([alt[0], alt[1], len(alt[2]), expression(alt[3], extend(inner, alt[2])),
                             [binder(b) for b in altmeta.get('binders', [])]])
            result += [expression(e[1], env), binder(meta.get('binder', {})), alts]
        elif tag != 'void':
            raise ValueError('Unhandled Core tag: ' + tag)
        return result + [metadata(meta)]
    result = {}
    for filename, module in sorted(modules.items()):
        result[filename] = {
            'bindings': [(globals[b['id']], binding(b, {})) for b in module['bindings']],
            'constructors': [{k: c[k] for k in ['id', 'name', 'arity', 'tag', 'kind', 'strictFields', 'fieldLifted', 'fieldReps'] if k in c}
                             for c in module['constructors']],
            'groups': [[g['recursive'], [globals.get(key, key) for key in g['ids']]] for g in module['groups']],
        }
    return result

def reachable(modules, entry):
    bindings = {tuple(key): value for module in modules.values() for key, value in module['bindings']}
    roots = [key for key in bindings if key[2] == entry]
    if len(roots) != 1:
        raise ValueError('Entry occurrence must be unique: ' + entry)
    def references(value):
        if isinstance(value, tuple) and len(value) == 4 and value[0] == 'global':
            yield value
        elif isinstance(value, (list, tuple)):
            for child in value:
                yield from references(child)
        elif isinstance(value, dict):
            for child in value.values():
                yield from references(child)
    seen = set()
    pending = list(roots)
    while pending:
        key = pending.pop()
        if key in seen:
            continue
        seen.add(key)
        pending.extend(references(bindings[key]))
    result = {}
    for filename, module in modules.items():
        result[filename] = dict(module,
            bindings=[b for b in module['bindings'] if tuple(b[0]) in seen],
            groups=[[recursive, [key for key in keys if tuple(key) in seen]] for recursive, keys in module['groups']
                    if any(tuple(key) in seen for key in keys)])
    return result, len(seen)

before, after = canonical(load(args.before)), canonical(load(args.after))
reachable_counts = None
if args.entry:
    before, old_count = reachable(before, args.entry)
    after, new_count = reachable(after, args.entry)
    reachable_counts = [old_count, new_count]
changed = sorted(k for k in before.keys() | after.keys() if before.get(k) != after.get(k))
hash_value = lambda value: hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
summary = {'equal': not changed, 'excludedModules': args.exclude, 'entry': args.entry, 'reachableBindings': reachable_counts, 'changedModules': changed, 'beforeSha256': hash_value(before), 'afterSha256': hash_value(after)}
if changed:
    changes = {}
    for name in changed:
        if name not in before or name not in after:
            changes[name] = 'module added/removed'
        else:
            old = {tuple(b[0]): b[1] for b in before[name]['bindings']}
            new = {tuple(b[0]): b[1] for b in after[name]['bindings']}
            changes[name] = [list(key) for key in old.keys() | new.keys() if old.get(key) != new.get(key)]
    summary['changedBindings'] = changes
print(json.dumps(summary, indent=2))
if args.output:
    args.output.write_text(json.dumps(summary, indent=2) + '\n')
raise SystemExit(bool(changed))
