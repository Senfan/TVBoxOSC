#!/usr/bin/env python3
"""Read-only debug-device checks; persist hashes, never content-source URLs or keys."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--compare', action='store_true')
args = parser.parse_args()
archive = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                   'com.github.tvbox.osc.tk', 'tar', '-cf', '-',
                                   'shared_prefs', 'databases'])
values = {}
with tarfile.open(fileobj=io.BytesIO(archive)) as files:
    for item in files:
        if not item.isfile():
            continue
        data = files.extractfile(item).read()
        if item.name.endswith('/Hawk2.xml'):
            for child in ET.fromstring(data):
                name = child.get('name')
                if name not in ('debug_open', 'tvbox_update_manifest_url'):
                    values['hawk/' + name] = hashlib.sha256(ET.tostring(child)).hexdigest()
        elif item.name.startswith('databases/') and item.name.endswith('.db'):
            values[item.name] = hashlib.sha256(data).hexdigest()
        elif item.name.endswith('/crypto.KEY_256.xml'):
            values['encryption-key'] = hashlib.sha256(data).hexdigest()
if not any(key.startswith('hawk/') for key in values):
    raise SystemExit('Expected application preferences were not found')
print('Persisted content-source key present:', 'hawk/api_url' in values)
print('Existing history database present:', any(key.startswith('databases/') for key in values))
if args.compare:
    baseline = json.loads(args.baseline.read_text())
    changed = [key for key, digest in baseline.items() if values.get(key) != digest]
    print(json.dumps({'checked': len(baseline), 'changed': changed, 'new_keys': sorted(set(values) - set(baseline))}))
    if changed:
        raise SystemExit(1)
else:
    with args.baseline.open('x') as output:
        json.dump(values, output, indent=2)
    print(f'Saved {len(values)} private-data fingerprints; no raw settings saved')
