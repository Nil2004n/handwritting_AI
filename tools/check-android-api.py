#!/usr/bin/env python3
"""Checks that compiled classes only call platform APIs available at a given Android minSdk.

Reads every method/field reference to a java.*/javax.* class from the constant pool of the given .class files (or a jar) and looks it up in the
Android SDK's authoritative API database (platforms/android-N/data/api-versions.xml), following the class hierarchy for inherited members.
Exit status 1 if anything needs a newer API than --min-sdk.

invokedynamic bootstrap classes (java/lang/invoke/*, java/lang/runtime/ObjectMethods) are reported separately: D8 desugars them at build time
(lambdas, records) and the engine compiles string concatenation inline (-XDstringConcat=inline).

Usage: check-android-api.py --api-versions PATH/api-versions.xml --min-sdk 24 CLASSES_DIR_OR_JAR [...]
"""
import argparse, io, os, struct, sys, zipfile
import xml.etree.ElementTree as ET


def constants(data):
    """Yield (kind, owner, name, descriptor) for every Fieldref/Methodref/InterfaceMethodref of a class file."""
    p = 8
    n = struct.unpack('>H', data[p:p + 2])[0]; p += 2
    utf, cls, ref, nat, tag = {}, {}, {}, {}, {}
    i = 1
    while i < n:
        t = data[p]; tag[i] = t; p += 1
        if t == 1:
            ln = struct.unpack('>H', data[p:p + 2])[0]; p += 2
            utf[i] = data[p:p + ln].decode('utf-8', 'replace'); p += ln
        elif t in (3, 4): p += 4
        elif t in (5, 6): p += 8; i += 1
        elif t == 7: cls[i] = struct.unpack('>H', data[p:p + 2])[0]; p += 2
        elif t in (8, 16, 19, 20): p += 2
        elif t in (9, 10, 11): ref[i] = struct.unpack('>HH', data[p:p + 4]); p += 4
        elif t == 12: nat[i] = struct.unpack('>HH', data[p:p + 4]); p += 4
        elif t == 15: p += 3
        elif t in (17, 18): p += 4
        else: raise ValueError('bad constant tag %d' % t)
        i += 1
    for i, (c, nt) in ref.items():
        kind = {9: 'field', 10: 'method', 11: 'method'}[tag[i]]
        owner = utf[cls[c]]
        name, desc = utf[nat[nt][0]], utf[nat[nt][1]]
        yield kind, owner, name, desc


def load_db(path):
    classes = {}
    for _, el in ET.iterparse(path, events=('end',)):
        if el.tag != 'class':
            continue
        c = {'since': float(el.get('since', '1')), 'supers': [], 'm': {}, 'f': {}}
        for ch in el:
            if ch.tag in ('extends', 'implements'):
                c['supers'].append(ch.get('name'))
            elif ch.tag in ('method', 'field'):
                c['m' if ch.tag == 'method' else 'f'][ch.get('name')] = float(ch.get('since', c['since']))
        classes[el.get('name')] = c
        el.clear()
    return classes


def lookup(db, owner, key, table):
    seen, stack = set(), [owner]
    while stack:
        o = stack.pop()
        if o in seen or o not in db:
            continue
        seen.add(o)
        if key in db[o][table]:
            return db[o][table][key], o
        stack.extend(db[o]['supers'])
    return None, None


def class_files(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for f in files:
                    if f.endswith('.class'):
                        yield os.path.join(root, f), open(os.path.join(root, f), 'rb').read()
        else:
            with zipfile.ZipFile(p) as z:
                for n in z.namelist():
                    if n.endswith('.class'):
                        yield p + '!' + n, z.read(n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--api-versions', required=True)
    ap.add_argument('--min-sdk', type=int, required=True)
    ap.add_argument('inputs', nargs='+')
    a = ap.parse_args()
    db = load_db(a.api_versions)
    bad, unknown, checked, desugared = {}, {}, 0, set()
    for name, data in class_files(a.inputs):
        for kind, owner, mname, desc in constants(data):
            if owner.startswith('[') or not (owner.startswith('java/') or owner.startswith('javax/')):
                continue
            if owner.startswith('java/lang/invoke/') or owner == 'java/lang/runtime/ObjectMethods' or owner == 'java/lang/Record':
                desugared.add(owner + '.' + mname)
                continue
            checked += 1
            key = mname + desc if kind == 'method' else mname
            since, where = lookup(db, owner, key, 'm' if kind == 'method' else 'f')
            if since is None:
                if mname == '<init>' or owner not in db:
                    since = db.get(owner, {}).get('since')
                if since is None:
                    unknown.setdefault(owner + '.' + key, name)
                    continue
            cs = db.get(owner, {}).get('since', 1)
            need = max(since, cs)
            if need > a.min_sdk:
                bad.setdefault((owner, key, need), os.path.basename(name.split('!')[-1]))
    print('checked %d JDK member references against minSdk %d' % (checked, a.min_sdk))
    if desugared:
        print('bootstrap/desugared by D8 (not checked): ' + ', '.join(sorted(desugared)))
    for k, v in sorted(unknown.items()):
        print('UNKNOWN to the Android API database: %s (first seen in %s)' % (k, v))
    for (owner, key, need), where in sorted(bad.items()):
        print('TOO NEW: %s.%s needs API %g (minSdk %d) in %s' % (owner, key, need, a.min_sdk, where))
    sys.exit(1 if bad or unknown else 0)


if __name__ == '__main__':
    main()
