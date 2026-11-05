#!/usr/bin/env python3
"""Prepare or activate loopback-only negative device fixtures, never publish them."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import os

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory', type=Path, required=True)
parser.add_argument('--prepare', action='store_true')
parser.add_argument('--activate', choices=['valid', 'bad-hash', 'wrong-package', 'wrong-abi', 'wrong-signer', 'same-version'])
parser.add_argument('--wrong-package-apk', type=Path)
parser.add_argument('--wrong-abi-apk', type=Path)
args = parser.parse_args()
root = args.directory
if args.prepare:
    source = json.loads((root / 'update.json').read_text())
    assert len(source['artifacts']) == 1
    assert source['artifacts'][0]['apkUrl'].startswith('http://10.0.2.2:19979/')
    variants = {'valid': source}
    for case in ('bad-hash', 'same-version', 'wrong-package', 'wrong-abi'):
        variant = json.loads(json.dumps(source))
        item = variant['artifacts'][0]
        if case == 'bad-hash':
            item['sha256'] = '0' * 64
            item['apkUrl'] = item['apkUrl'].replace('/slow/', '/')
        elif case == 'same-version':
            item['versionCode'] = 2
        else:
            apk = args.wrong_package_apk if case == 'wrong-package' else args.wrong_abi_apk
            if apk is None:
                raise SystemExit('Alternate actual APKs are required')
            destination = root / ('fixture-' + case + '.apk')
            with destination.open('xb') as out, apk.open('rb') as inp:
                shutil.copyfileobj(inp, out)
            digest = hashlib.sha256()
            with destination.open('rb') as inp:
                for block in iter(lambda: inp.read(65536), b''):
                    digest.update(block)
            item['sha256'] = digest.hexdigest()
            item['sizeBytes'] = destination.stat().st_size
            # Deliberately lie in the test manifest: APK validation must catch this.
            item['apkUrl'] = 'http://10.0.2.2:19979/' + destination.name
        item['notes'] = '本机负面验收：' + case
        variants[case] = variant
    for case, variant in variants.items():
        with (root / ('fixture-' + case + '.json')).open('x') as out:
            json.dump(variant, out, ensure_ascii=False, indent=2)
if args.activate:
    source = root / ('fixture-' + args.activate + '.json')
    data = json.loads(source.read_text())
    assert all(item['apkUrl'].startswith('http://10.0.2.2:19979/') for item in data['artifacts'])
    # Inspect the existing test target before atomically replacing it.
    old = json.loads((root / 'update.json').read_text())
    assert old['schemaVersion'] == 1
    with tempfile.NamedTemporaryFile(mode='w', dir=root, delete=False) as out:
        json.dump(data, out, ensure_ascii=False, indent=2)
        temporary = out.name
    os.replace(temporary, root / 'update.json')
    print('Activated local fixture:', args.activate)
