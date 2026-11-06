#!/usr/bin/env python3
"""Download pinned network binaries and check JVM member linkage without Gradle.

Run with Python -I. Each download and extracted classes.jar gets a new empty
folder. No downloaded scripts/builds are executed. The emitted paths.json is
input to tests/run-network-compat-tests.py. This checks symbolic linkage, not
all behavioral/Android compatibility; the separate regression runner covers
requests and the app's adapters.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import urllib.request
import zipfile

MAVEN = "https://repo.maven.apache.org/maven2/"
ARTIFACTS = {
    "okhttp": MAVEN + "com/squareup/okhttp3/okhttp-jvm/5.5.1/okhttp-jvm-5.5.1.jar",
    "okio": MAVEN + "com/squareup/okio/okio-jvm/3.18.2/okio-jvm-3.18.2.jar",
    "kotlin": MAVEN + "org/jetbrains/kotlin/kotlin-stdlib/2.1.21/kotlin-stdlib-2.1.21.jar",
    "sardine": "https://jitpack.io/com/github/thegrizzlylabs/sardine-android/v0.9/sardine-android-v0.9.aar",
    "simplexml": MAVEN + "org/simpleframework/simple-xml/2.7.1/simple-xml-2.7.1.jar",
    "xpp3": MAVEN + "xpp3/xpp3/1.1.4c/xpp3-1.1.4c.jar",
    "json": MAVEN + "org/json/json/20240303/json-20240303.jar",
}
NETWORK = ("okhttp3/", "okio/")


class ClassFile:
    """Read class hierarchy, declarations and symbolic references only."""
    def __init__(self, data):
        self.data, self.pos = data, 0
        if self.take(4) != b"\xca\xfe\xba\xbe":
            raise ValueError("Not a class file")
        self.u2()
        self.major = self.u2()
        count = self.u2()
        cp = self.cp = [None] * count
        i = 1
        while i < count:
            tag = self.u1()
            if tag == 1:
                value = self.take(self.u2()).decode("utf-8", errors="replace")
            elif tag in (3, 4):
                value = self.take(4)
            elif tag in (5, 6):
                value = self.take(8)
            elif tag in (7, 8, 16, 19, 20):
                value = self.u2()
            elif tag in (9, 10, 11, 12, 17, 18):
                value = (self.u2(), self.u2())
            elif tag == 15:
                value = (self.u1(), self.u2())
            else:
                raise ValueError(f"Unknown constant pool tag {tag}")
            cp[i] = (tag, value)
            i += 2 if tag in (5, 6) else 1
        self.access = self.u2()
        self.name = self.class_name(self.u2())
        parent = self.u2()
        self.parents = [self.class_name(parent)] if parent else []
        self.parents += [self.class_name(self.u2()) for _ in range(self.u2())]
        self.members = {}
        for kind in (9, 10):
            for _ in range(self.u2()):
                access, name, desc = self.u2(), self.utf(self.u2()), self.utf(self.u2())
                self.members[(kind, name, desc)] = access
                self.skip_attributes()
        self.refs = set()
        self.classes = set()
        for entry in cp[1:]:
            if not entry:
                continue
            tag, value = entry
            if tag == 7:
                self.classes.add(self.utf(value))
            if tag in (9, 10, 11):
                owner, nt = value
                name, desc = cp[nt][1]
                self.refs.add((9 if tag == 9 else 10, self.class_name(owner), self.utf(name), self.utf(desc)))

    def take(self, size):
        data = self.data[self.pos:self.pos + size]
        if len(data) != size:
            raise ValueError("Truncated class file")
        self.pos += size
        return data

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]

    def utf(self, index):
        return self.cp[index][1]

    def class_name(self, index):
        return self.utf(self.cp[index][1])

    def skip_attributes(self):
        for _ in range(self.u2()):
            self.u2()
            self.take(self.u4())


def classes(path):
    with zipfile.ZipFile(path) as jar:
        return {c.name: c for name in jar.namelist()
                if name.endswith(".class") and not name.startswith("META-INF/")
                for c in [ClassFile(jar.read(name))]}


def resolve(universe, owner, key, seen=None):
    seen = set() if seen is None else seen
    if owner in seen or owner not in universe:
        return None
    seen.add(owner)
    cls = universe[owner]
    if key in cls.members:
        return cls.members[key]
    if key[1] == "<init>":
        return None
    for parent in cls.parents:
        result = resolve(universe, parent, key, seen)
        if result is not None:
            return result
    return None


def inspect(paths):
    universe = {}
    for name in ("okhttp", "okio"):
        universe.update(classes(paths[name]))
    failures = []
    for label in ("okgo", "sardine"):
        binary = classes(paths[label])
        refs, private_refs = set(), set()
        for cls in binary.values():
            for owner in cls.classes:
                if owner.startswith(NETWORK) and owner not in universe:
                    failures.append(f"{label}: missing class {owner} used by {cls.name}")
            for kind, owner, name, desc in cls.refs:
                if not owner.startswith(NETWORK):
                    continue
                ref = (owner, name, desc)
                refs.add(ref)
                if "/internal/" in owner:
                    private_refs.add(ref)
                access = resolve(universe, owner, (kind, name, desc))
                if access is None:
                    # Object methods can be invoked through any class owner.
                    if (name, desc) not in {("toString", "()Ljava/lang/String;"),
                                           ("equals", "(Ljava/lang/Object;)Z"),
                                           ("hashCode", "()I")}:
                        failures.append(f"{label}: missing {owner}.{name}{desc} used by {cls.name}")
                elif not (access & 0x0001 or access & 0x0004):
                    failures.append(f"{label}: inaccessible {owner}.{name}{desc}")
        print(f"{label}: {len(binary)} classes, {len(refs)} distinct network member references, "
              f"{len(private_refs)} internal references")
        for owner, name, desc in sorted(private_refs):
            print(f"  internal: {owner}.{name}{desc}")
    if failures:
        raise SystemExit("\n".join(sorted(set(failures))))
    print("PASS: all OkGo 3.0.4 / Sardine v0.9 network symbols resolve in OkHttp 5.5.1 / Okio 3.18.2")


def download(okgo_aar):
    root = Path(tempfile.mkdtemp(prefix="tvbox-network-compat-"))
    print(f"Download directory: {root}", flush=True)
    paths, records = {}, {}
    artifacts = dict(ARTIFACTS, okgo=okgo_aar.resolve().as_uri())
    for name, url in artifacts.items():
        folder = Path(tempfile.mkdtemp(prefix=name + "-", dir=root))
        path = folder / url.rsplit("/", 1)[-1]
        with urllib.request.urlopen(url, timeout=90) as response:
            data = response.read(20 * 1024 * 1024 + 1)
        if len(data) > 20 * 1024 * 1024:
            raise ValueError(f"Oversized artifact: {url}")
        path.write_bytes(data)
        records[name] = {"url": url, "sha256": hashlib.sha256(data).hexdigest()}
        if path.suffix == ".aar":
            target = Path(tempfile.mkdtemp(prefix=name + "-classes-", dir=root)) / "classes.jar"
            with zipfile.ZipFile(io.BytesIO(data)) as aar:
                target.write_bytes(aar.read("classes.jar"))
            path = target
        paths[name] = str(path)
    (root / "paths.json").write_text(json.dumps(paths, indent=2) + "\n")
    (root / "artifacts.json").write_text(json.dumps(records, indent=2) + "\n")
    print(f"Artifacts: {root}", flush=True)
    return paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths", type=Path, help="Reuse a previous isolated paths.json instead of downloading")
    parser.add_argument("--okgo-aar", type=Path, help="Existing resolved OkGo 3.0.4 AAR (legacy JCenter artifact)")
    args = parser.parse_args()
    if not args.paths and (not args.okgo_aar or not args.okgo_aar.is_file()):
        parser.error("Provide --okgo-aar from the project's existing Gradle cache, or --paths to reuse downloads")
    inspect(json.loads(args.paths.read_text()) if args.paths else download(args.okgo_aar))
