#!/usr/bin/env python3
"""Snapshot declared dependencies and upstream stable-version candidates, without installing them."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
import json
from pathlib import Path
import re
import tempfile
import tomllib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

MAX_METADATA_BYTES = 2 * 1024 * 1024
UNSTABLE = re.compile(r"(?i)(alpha|beta|snapshot|preview|canary|eap|dev|(?:^|[.\-])rc\d*|(?:^|[.\-])m\d+)")


def version_key(version):
    match = re.match(r'^v?(\d+(?:\.\d+)*)(.*)$', version)
    if not match:
        return ((), 0, ())
    return (tuple(int(x) for x in match[1].split('.')),
            0 if UNSTABLE.search(version) else 1,
            tuple(int(x) for x in re.findall(r'\d+', match[2])))


def declared(root):
    coordinates = set()
    for path in (root / "build.gradle", root / "app/build.gradle"):
        for line in path.read_text().splitlines():
            if line.lstrip().startswith("//"):
                continue
            for group, artifact, version in re.findall(r"[\"']([\w.\-]+):([\w.\-]+):([^\"']+)[\"']", line):
                if ":" in version or "$" in version:
                    continue
                coordinates.add((group, artifact, version))
    catalog = root / "gradle/libs.versions.toml"
    if catalog.exists():
        with catalog.open('rb') as stream:
            values = tomllib.load(stream)
        for library in values.get('libraries', {}).values():
            group, artifact = library['module'].split(':')
            version = library['version']
            if isinstance(version, dict):
                version = values['versions'][version['ref']]
            coordinates.add((group, artifact, version))
    kotlin = re.search(r'kotlin_version\s*=\s*[\"\']([^\"\']+)', (root / "build.gradle").read_text())
    if kotlin:
        coordinates.add(("org.jetbrains.kotlin", "kotlin-gradle-plugin", kotlin[1]))
        coordinates.add(("org.jetbrains.kotlin", "kotlin-stdlib", kotlin[1]))
    return sorted(coordinates)


def locked_coordinates(path, configuration):
    coordinates = set()
    for line in path.read_text().splitlines():
        if not line or line.startswith('#') or '=' not in line:
            continue
        coordinate, configurations = line.split('=', 1)
        if configuration not in configurations.split(',') or coordinate == 'empty':
            continue
        parts = tuple(coordinate.split(':'))
        if len(parts) != 3 or not all(parts):
            raise ValueError(f'Invalid locked coordinate: {coordinate}')
        coordinates.add(parts)
    return sorted(coordinates)


def repositories(group):
    if group.startswith("androidx.") or group.startswith("com.android.") or group == "com.google.android.material":
        return ["https://dl.google.com/dl/android/maven2"]
    if group.startswith("com.github."):
        return ["https://repo.maven.apache.org/maven2", "https://jitpack.io"]
    if group.startswith(("com.aliyun.", "com.alivc.")):
        return ["https://repo.maven.apache.org/maven2", "https://maven.aliyun.com/repository/releases"]
    if group.startswith("com.chaquo."):
        return ["https://chaquo.com/maven"]
    return ["https://repo.maven.apache.org/maven2"]


def snapshot(coordinate, destination, cutoff):
    group, artifact, current = coordinate
    result = dict(group=group, artifact=artifact, current=current,
                  status="unresolved", compatibility="not_tested")
    errors = []
    for repository in repositories(group):
        url = f"{repository}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "TVBox-dependency-audit/1"})
            with urllib.request.urlopen(request, timeout=25) as response:
                data = response.read(MAX_METADATA_BYTES + 1)
            if len(data) > MAX_METADATA_BYTES or b"<!DOCTYPE" in data.upper():
                raise ValueError("metadata size or document type rejected")
            # Every downloaded document has its own new directory. Never import or execute it.
            directory = Path(tempfile.mkdtemp(prefix="metadata-", dir=destination))
            (directory / "maven-metadata.xml").write_bytes(data)
            tree = ET.fromstring(data)
            versions = [node.text for node in tree.findall("./versioning/versions/version")
                        if node.text and re.match(r"^v?\d", node.text) and not UNSTABLE.search(node.text)]
            # Preserve vendor feature variants; a '-part' package cannot replace '-full'.
            if current.endswith(('-full', '-part')):
                suffix = '-full' if current.endswith('-full') else '-part'
                versions = [version for version in versions if version.endswith(suffix)]
            versions.sort(key=version_key, reverse=True)
            result.update(metadataUrl=url, metadataSha256=hashlib.sha256(data).hexdigest(),
                          metadataPath=str(directory.relative_to(destination) / "maven-metadata.xml"))
            if not versions:
                raise ValueError("no stable version found in metadata")
            result["latestMetadataStable"] = versions[0]
            if version_key(versions[0]) < version_key(current):
                result["status"] = "metadata_older_than_current"
                errors.append(f"{url}: metadata is older than the current dependency; no downgrade proposed")
                continue
            # A repository artifact timestamp is evidence, not a substitute for release notes.
            result["status"] = "no_date_eligible_candidate"
            for candidate in versions[:8]:
                if version_key(candidate) < version_key(current):
                    errors.append(f"{url}: older date-eligible versions cannot replace {current}")
                    break
                pom_url = f"{repository}/{group.replace('.', '/')}/{artifact}/{candidate}/{artifact}-{candidate}.pom"
                try:
                    with urllib.request.urlopen(urllib.request.Request(pom_url, method="HEAD"), timeout=20) as response:
                        modified = response.headers.get("Last-Modified")
                    if not modified:
                        result.update(candidate=candidate, status="candidate_date_unverified", pomUrl=pom_url)
                        break
                    date = parsedate_to_datetime(modified).astimezone(timezone.utc)
                    if date.date() <= cutoff.date():
                        result.update(candidate=candidate, status="candidate", pomUrl=pom_url,
                                      artifactLastModified=date.isoformat())
                        break
                except (OSError, ValueError) as error:
                    errors.append(f"{pom_url}: {error}")
            else:
                result["status"] = "no_date_eligible_candidate"
            break
        except (OSError, ValueError, ET.ParseError) as error:
            errors.append(f"{url}: {error}")
    if errors:
        result["lookupNotes"] = errors
    return result


def native_inventory(root):
    files = list((root / "app/libs").glob("*"))
    for module in ("app", "quickjs"):
        files.extend((root / module / "src/main/jniLibs").rglob("*.so"))
    return [dict(path=str(path.relative_to(root)), size=path.stat().st_size,
                 sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                 sourceStatus="unverified", latestStatus="unverified")
            for path in sorted(files) if path.is_file() and path.suffix in (".jar", ".aar", ".so")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--downloads", type=Path, required=True)
    parser.add_argument("--cutoff", default="2026-11-05")
    parser.add_argument("--lockfile", type=Path)
    parser.add_argument("--configuration", default="arm64GenericNormalReleaseRuntimeClasspath")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output exists; use a new snapshot path")
    args.downloads.mkdir(parents=True, exist_ok=True)
    cutoff = datetime.fromisoformat(args.cutoff).replace(tzinfo=timezone.utc)
    coordinates = (locked_coordinates(args.lockfile, args.configuration)
                   if args.lockfile else declared(args.root))
    with ThreadPoolExecutor(max_workers=6) as pool:
        records = list(pool.map(lambda c: snapshot(c, args.downloads, cutoff), coordinates))
    document = dict(cutoff=args.cutoff, meaning="Upstream candidates, not a resolved or Sony-verified dependency graph",
                    dependencies=records, localBinaries=native_inventory(args.root))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n")
    for item in records:
        print(f"{item['group']}:{item['artifact']}: {item['current']} -> {item.get('candidate', '?')} [{item['status']}]")
    print(f"Local binaries: {len(document['localBinaries'])}; no source/latest claims made")


if __name__ == "__main__":
    main()
