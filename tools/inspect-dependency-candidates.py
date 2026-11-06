#!/usr/bin/env python3
"""Read candidate AAR manifests/ABIs without installing or executing downloaded content."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ANDROID = '{http://schemas.android.com/apk/res/android}'
MAX_ARTIFACT_BYTES = 128 * 1024 * 1024


def inspect(record, downloads):
    downloads = downloads.resolve()
    result = dict(group=record['group'], artifact=record['artifact'], candidate=record.get('candidate'),
                  status='uninspected')
    if not result['candidate']:
        return result
    base = record['metadataUrl'].removesuffix('/maven-metadata.xml')
    candidate = result['candidate']
    url = f"{base}/{candidate}/{record['artifact']}-{candidate}.aar"
    directory = Path(tempfile.mkdtemp(prefix='aar-', dir=downloads))
    file = directory / 'candidate.aar'
    try:
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(url, timeout=40) as response, file.open('xb') as output:
            for block in iter(lambda: response.read(65536), b''):
                size += len(block)
                if size > MAX_ARTIFACT_BYTES:
                    raise ValueError('artifact exceeds inspection limit')
                digest.update(block)
                output.write(block)
        with zipfile.ZipFile(file) as archive:
            info = archive.getinfo('AndroidManifest.xml')
            if info.file_size > 256 * 1024:
                raise ValueError('manifest too large')
            manifest = archive.read(info)
            if b'<!DOCTYPE' in manifest.upper():
                raise ValueError('document type rejected')
            tree = ET.fromstring(manifest)
            sdk = tree.find('uses-sdk')
            result.update(status='inspected', url=url, sha256=digest.hexdigest(), sizeBytes=size,
                          minSdk=sdk.get(ANDROID + 'minSdkVersion') if sdk is not None else None,
                          nativeAbis=sorted({name.split('/')[1] for name in archive.namelist()
                                             if name.startswith('jni/') and name.endswith('.so')}))
            if 'META-INF/com/android/build/gradle/aar-metadata.properties' in archive.namelist():
                result['aarMetadata'] = archive.read('META-INF/com/android/build/gradle/aar-metadata.properties').decode('utf-8')
        result['downloadPath'] = str(directory.relative_to(downloads) / file.name)
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        result.update(status='unresolved', error=str(error))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--downloads', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--artifact', action='append', help='Inspect only these artifact names (repeatable)')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('use a new output path')
    args.downloads.mkdir(parents=True, exist_ok=True)
    snapshot = json.loads(args.snapshot.read_text())
    artifacts = args.artifact or ('appcompat', 'constraintlayout', 'recyclerview', 'room-runtime',
                                  'media3-exoplayer', 'glide', 'XXPermissions', 'sardine-android',
                                  'conscrypt-android', 'AliyunPlayer', 'AlivcConan')
    records = [record for record in snapshot['dependencies']
               if record['artifact'] in artifacts and record.get('candidate')]
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda record: inspect(record, args.downloads), records))
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    for record in results:
        print(f"{record['group']}:{record['artifact']}:{record['candidate']}: {record['status']}, minSdk={record.get('minSdk')}, ABI={record.get('nativeAbis')}, {record.get('error', '')}")


if __name__ == '__main__':
    main()
