#!/usr/bin/env python3
"""Inspect a signed APK and generate a TVBox update feed without uploading it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse


MAX_APK_BYTES = 200 * 1024 * 1024


def run(tool, *args):
    result = subprocess.run([str(tool), *map(str, args)], capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise ValueError(f"{tool.name} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def tools_for(sdk):
    directory = sdk / "build-tools"
    candidates = sorted(directory.iterdir(), key=lambda p: tuple(map(int, re.findall(r"\d+", p.name))), reverse=True)
    for candidate in candidates:
        if all((candidate / name).is_file() and os.access(candidate / name, os.X_OK)
               for name in ("aapt", "apksigner")):
            return candidate / "aapt", candidate / "apksigner"
    raise ValueError("No executable aapt/apksigner in SDK build-tools")


def metadata(xml):
    values = {}
    name = None
    for line in xml.splitlines():
        if "E: meta-data" in line:
            name = None
        match = re.search(r'android:(name|value)\([^)]*\)="([^"]*)"', line)
        if not match:
            continue
        if match[1] == "name":
            name = match[2]
        elif name and name.startswith("tvbox.update."):
            values[name] = match[2]
            name = None
    return values


def inspect(apk, sdk, url, notes, allow_debug_local=False):
    if len(url) > 4096 or len(notes) > 8192 or url != url.strip():
        raise ValueError("APK URL or notes exceed the manifest limits")
    parsed = urlparse(url)
    local = parsed.hostname in ("127.0.0.1", "localhost", "::1", "10.0.2.2")
    if (not parsed.hostname or parsed.username is not None or parsed.fragment
            or (parsed.scheme != "https" and not (allow_debug_local and local and parsed.scheme == "http"))):
        raise ValueError("Use a complete HTTPS APK URL; local HTTP is for debug fixtures only")
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError("Invalid APK URL port")
    size = apk.stat().st_size
    if not 0 < size <= MAX_APK_BYTES:
        raise ValueError("APK must be between 1 byte and 200 MiB")
    aapt, apksigner = tools_for(sdk)
    badging = run(aapt, "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.M)
    native = re.search(r"^native-code: (.+)$", badging, re.M)
    if not package or not minimum or not native:
        raise ValueError("APK is missing package, SDK or native ABI information")
    if len(package[3]) > 100 or not package[3].strip():
        raise ValueError("APK versionName is empty or too long")
    version_code, min_sdk = int(package[2]), int(minimum[1])
    if not 0 < version_code <= 2100000000 or min_sdk < 21:
        raise ValueError("Invalid application version or unsupported minSdk")
    signing = run(apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", min_sdk, apk)
    if min_sdk < 24 and not re.search(r"Verified using v1 scheme .*: true", signing):
        raise ValueError("APK supporting Android 5/6 requires a verified v1 signature")
    if "Signer #1 certificate SHA-256 digest:" not in signing:
        raise ValueError("APK must have a verified signing certificate")
    identity = metadata(run(aapt, "dump", "xmltree", apk, "AndroidManifest.xml"))
    brand = identity.get("tvbox.update.brand")
    mode = identity.get("tvbox.update.mode")
    abi = identity.get("tvbox.update.abi")
    packages = {"generic": "com.github.tvbox.osc.tk", "hisense": "com.github.hisense.osc.tk"}
    if brand not in packages or package[1] != packages[brand] or mode not in ("normal", "python"):
        raise ValueError("APK package/brand/mode metadata mismatch")
    if abi not in ("arm64-v8a", "armeabi-v7a") or re.findall(r"'([^']+)'", native[1]) != [abi]:
        raise ValueError("APK native ABI does not match its update channel")
    if allow_debug_local and "application-debuggable" not in badging:
        raise ValueError("Local HTTP metadata may only be generated for a debuggable APK")
    with apk.open("rb") as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    artifact = dict(applicationId=package[1], brand=brand, mode=mode, abi=abi,
                    versionCode=version_code, versionName=package[3], minSdk=min_sdk,
                    apkUrl=url, sha256=digest.hexdigest(), sizeBytes=size, notes=notes)
    return {"schemaVersion": 1, "artifacts": [artifact]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--notes", default="")
    parser.add_argument("--allow-debug-local", action="store_true")
    parser.add_argument("--replace", action="store_true", help="Explicitly replace an existing output file")
    args = parser.parse_args()
    try:
        if args.sdk is None:
            raise ValueError("Set ANDROID_HOME or pass --sdk")
        if args.output.exists() and not args.replace:
            raise ValueError("Output exists; inspect it first and use --replace if intended")
        document = inspect(args.apk.resolve(strict=True), args.sdk, args.url, args.notes, args.allow_debug_local)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Generated {args.output}; no files were published")
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"Error: {error}\n")


if __name__ == "__main__":
    main()
