#!/usr/bin/env python3
"""Verify a local Sony candidate's identity, signing and APK contents, not device compatibility."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

CERTIFICATE = 'c9d4035688a13f886e6661edc3631f8da38bf84e8676c2c4ade87c00f66bd821'
PACKAGE = 'com.github.tvbox.osc.tk'


def command(*args):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True, timeout=120)
    if result.returncode:
        raise ValueError(result.stderr or result.stdout)
    return result.stdout


def verify(apk, sdk):
    tools = sdk / 'build-tools/36.0.0'
    badging = command(tools / 'aapt', 'dump', 'badging', apk)
    for text in (f"package: name='{PACKAGE}' versionCode='7' versionName='1.1.1'",
                 "sdkVersion:'21'", "targetSdkVersion:'34'", "native-code: 'arm64-v8a'"):
        if text not in badging:
            raise ValueError('Unexpected APK identity: missing ' + text)
    if 'application-debuggable' in badging:
        raise ValueError('Candidate must not be debuggable')
    signing = command(tools / 'apksigner', 'verify', '--verbose', '--print-certs', '--min-sdk-version', '21', apk)
    for scheme in (1, 2):
        if not re.search(rf'Verified using v{scheme} scheme .*: true', signing):
            raise ValueError(f'Missing verified v{scheme} signature')
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', signing)
    if certificates != [CERTIFICATE]:
        raise ValueError('Production certificate mismatch')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        unexpected = [name for name in names if re.search(r'(?i)(chaquopy|pyramid|libpython|\.py[co]?$)', name)]
        if unexpected:
            raise ValueError('Python content present: ' + repr(unexpected))
        identity = f'assets/{PACKAGE}.andserver'
        if archive.read(identity).decode('utf-8') != f'{PACKAGE}.andserver':
            raise ValueError('AndServer discovery asset mismatch')
        libraries = []
        for name in names:
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            if not name.startswith('lib/arm64-v8a/'):
                raise ValueError('Unexpected native ABI: ' + name)
            data = archive.read(name)
            if data[:4] != b'\x7fELF' or data[4:6] != b'\x02\x01' or int.from_bytes(data[18:20], 'little') != 183:
                raise ValueError('Not ARM64 ELF: ' + name)
            libraries.append({'path': name, 'sizeBytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        if not libraries:
            raise ValueError('No native libraries')
    return dict(applicationId=PACKAGE, versionCode=7, versionName='1.1.1', minSdk=21, targetSdk=34,
                abi='arm64-v8a', debuggable=False, certificateSha256=CERTIFICATE,
                v1Verified=True, v2Verified=True, sizeBytes=apk.stat().st_size,
                sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), pythonContentPresent=False,
                andServerDiscoveryAssetVerified=True, nativeLibraries=libraries,
                scope='Static APK verification only; not proof of native linking, Sony compatibility or all dependencies latest'), badging, signing


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if any(path.exists() for path in (args.output, args.output.with_suffix('.badging.txt'), args.output.with_suffix('.signing.txt'))):
        parser.error('Refusing to overwrite an existing verification report')
    result, badging, signing = verify(args.apk, args.sdk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    args.output.with_suffix('.badging.txt').write_text(badging)
    args.output.with_suffix('.signing.txt').write_text(signing)
    print(json.dumps({k: v for k, v in result.items() if k != 'nativeLibraries'}, ensure_ascii=False, indent=2))
    print('ARM64 ELF libraries:', len(result['nativeLibraries']))
