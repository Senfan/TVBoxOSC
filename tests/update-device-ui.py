#!/usr/bin/env python3
"""Drive an existing emulator through its visible UI, without app-private update shortcuts."""
import argparse
from pathlib import Path
import re
import shlex
import time
import subprocess
import uuid
import xml.etree.ElementTree as ET


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--directory', type=Path, required=True)
parser.add_argument('--tap', help='Visible resource-id suffix, such as updateCheck')
parser.add_argument('--keys', nargs='*', default=[])
parser.add_argument('--text', help='ASCII fixture text for adb input; never use a password')
parser.add_argument('--capture', help='New screenshot name, without extension')
parser.add_argument('--no-dump', action='store_true', help='Capture a busy screen without waiting for UI idle')
args = parser.parse_args()
args.directory.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]


def adb(*command):
    result = subprocess.run(base + list(command), stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=60)
    if result.returncode:
        raise SystemExit(result.stdout.decode(errors='replace'))
    return result.stdout


def dump():
    remote = '/sdcard/tvbox-update-ui-' + uuid.uuid4().hex + '.xml'
    result = adb('shell', 'uiautomator', 'dump', remote)
    if b'UI hierchary dumped to:' not in result:
        raise SystemExit('No fresh UI tree (screen may be busy): ' + result.decode(errors='replace'))
    target = args.directory / 'current-ui.xml'
    try:
        adb('pull', remote, str(target))
        return ET.parse(target).getroot()
    finally:
        adb('shell', 'rm', remote)


if args.tap:
    root = dump()
    nodes = [node for node in root.iter('node') if node.get('resource-id', '').split('/')[-1] == args.tap]
    if len(nodes) != 1 or nodes[0].get('enabled') != 'true':
        raise SystemExit(f'Expected exactly one enabled visible element: {args.tap}')
    numbers = list(map(int, re.findall(r'\d+', nodes[0].get('bounds', ''))))
    if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
        raise SystemExit('Element has no visible bounds; navigate with the remote first')
    adb('shell', 'input', 'tap', str((numbers[0] + numbers[2]) // 2), str((numbers[1] + numbers[3]) // 2))
if args.keys:
    for key in args.keys:
        if not re.fullmatch(r'KEYCODE_[A-Z0-9_]+', key):
            raise SystemExit('Use named Android KEYCODE values')
        adb('shell', 'input', 'keyevent', key)
        time.sleep(0.35)  # Allow IME/dialog focus transitions between remote presses.
if args.text is not None:
    if not args.text.isascii() or re.search(r'[^A-Za-z0-9:/._?=&%-]', args.text):
        raise SystemExit('Only safe ASCII fixture URL text is accepted')
    adb('shell', 'input', 'text', shlex.quote(args.text))
if not args.no_dump:
    root = dump()
    for node in root.iter('node'):
        if node.get('text') or node.get('focusable') == 'true':
            print(node.get('resource-id', '').split('/')[-1], repr(node.get('text', '')),
                  'enabled=' + node.get('enabled', ''), 'focused=' + node.get('focused', ''), node.get('bounds'))
if args.capture:
    file = args.directory / (args.capture + '.png')
    with file.open('xb') as output:
        subprocess.run(base + ['exec-out', 'screencap', '-p'], stdout=output, check=True)
    subprocess.run(['sips', '-Z', '1280', str(file), '--out',
                    str(args.directory / (args.capture + '-preview.png'))], check=True, stdout=subprocess.DEVNULL)
    print('Captured:', file)
