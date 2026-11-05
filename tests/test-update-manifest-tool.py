#!/usr/bin/env python3
"""Generator validation tests; signature enforcement uses a stub, not a real signing test."""
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch


module = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'tools/generate-update-manifest.py'))
inspect = module['inspect']
metadata = module['metadata']
BADGING = """package: name='com.github.tvbox.osc.tk' versionCode='3' versionName='1.1.0-debug'
sdkVersion:'21'
native-code: 'arm64-v8a'
application-debuggable
"""
XML = '\n'.join(f'E: meta-data\n  A: android:name(0x01010003)="tvbox.update.{key}"\n  A: android:value(0x01010024)="{value}"'
                for key, value in [('brand', 'generic'), ('mode', 'normal'), ('abi', 'arm64-v8a')])
SIGNING = 'Verified using v1 scheme (JAR signing): true\nSigner #1 certificate SHA-256 digest: TEST_ONLY\n'


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.apk = Path(self.temp.name) / 'test.apk'
        self.apk.write_bytes(b'test fixture, not an actual APK')
        self.badging, self.xml, self.signing = BADGING, XML, SIGNING
        self.patch = patch.dict(inspect.__globals__, {
            'tools_for': lambda sdk: (Path('aapt'), Path('apksigner')),
            'run': lambda tool, *args: self.signing if tool.name == 'apksigner'
                    else self.xml if 'xmltree' in args else self.badging})
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.temp.cleanup()

    def generate(self, url='https://example.com/update.apk', **kwargs):
        return inspect(self.apk, Path(self.temp.name), url, '', **kwargs)['artifacts'][0]

    def test_metadata_and_actual_fields(self):
        self.assertEqual(metadata(XML)['tvbox.update.abi'], 'arm64-v8a')
        item = self.generate()
        self.assertEqual(item['versionCode'], 3)
        self.assertEqual(item['versionName'], '1.1.0-debug')
        self.assertEqual(item['sizeBytes'], self.apk.stat().st_size)
        self.assertEqual(len(item['sha256']), 64)

    def test_missing_v1(self):
        self.signing = SIGNING.replace('true', 'false')
        with self.assertRaises(ValueError): self.generate()

    def test_missing_verified_certificate(self):
        self.signing = 'Verified using v1 scheme (JAR signing): true'
        with self.assertRaises(ValueError): self.generate()

    def test_wrong_package(self):
        self.badging = BADGING.replace('com.github.tvbox.osc.tk', 'other.package')
        with self.assertRaises(ValueError): self.generate()

    def test_wrong_native_abi(self):
        self.badging = BADGING.replace("native-code: 'arm64-v8a'", "native-code: 'armeabi-v7a'")
        with self.assertRaises(ValueError): self.generate()

    def test_wrong_mode(self):
        self.xml = XML.replace('"normal"', '"unknown"')
        with self.assertRaises(ValueError): self.generate()

    def test_http_default_rejected(self):
        with self.assertRaises(ValueError): self.generate('http://127.0.0.1/update.apk')

    def test_debug_http(self):
        self.assertEqual(self.generate('http://10.0.2.2/update.apk', allow_debug_local=True)['versionCode'], 3)
        with self.assertRaises(ValueError): self.generate('http://192.168.1.2/update.apk', allow_debug_local=True)
        self.badging = BADGING.replace('application-debuggable', '')
        with self.assertRaises(ValueError): self.generate('http://10.0.2.2/update.apk', allow_debug_local=True)

    def test_credentials_and_fragment_rejected(self):
        for url in ('https://name:password@example.com/file', 'https://example.com/file#hash'):
            with self.assertRaises(ValueError): self.generate(url)

    def test_large_and_empty_apk(self):
        self.apk.write_bytes(b'')
        with self.assertRaises(ValueError): self.generate()
        with self.apk.open('wb') as stream: stream.truncate(200 * 1024 * 1024 + 1)
        with self.assertRaises(ValueError): self.generate()


if __name__ == '__main__':
    unittest.main()
