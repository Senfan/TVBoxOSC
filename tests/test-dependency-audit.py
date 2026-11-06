#!/usr/bin/env python3
"""Offline audit/catalog checks. These do not execute Gradle or prove device compatibility."""
import importlib.util
import io
from datetime import datetime, timezone
from unittest.mock import patch
from pathlib import Path
import re
import tempfile
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('tvbox_dependency_audit', ROOT / 'tools/audit-dependencies.py')
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class AuditTest(unittest.TestCase):
    def test_unstable_versions(self):
        for version in ('1.2.0-alpha01', '2.0.0-beta2', '3.0-rc1', '4.0-SNAPSHOT', '2.1-M1', '2.1-preview'):
            self.assertIsNotNone(AUDIT.UNSTABLE.search(version), version)
        for version in ('1.11.1', 'v0.9', '7.19.0-full', '1.2.0-final', '2.9.49-androidx'):
            self.assertIsNone(AUDIT.UNSTABLE.search(version), version)

    def test_numeric_order(self):
        self.assertGreater(AUDIT.version_key('1.11.1'), AUDIT.version_key('1.3.1'))
        self.assertGreater(AUDIT.version_key('0.9.25'), AUDIT.version_key('0.3.8'))
        self.assertGreater(AUDIT.version_key('1.1.0'), AUDIT.version_key('1.1.0-beta02'))
        self.assertGreater(AUDIT.version_key('1.11.1-0.17.0'), AUDIT.version_key('1.11.1-0.16.0'))

    def test_multiple_versions_are_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'app').mkdir()
            (root / 'build.gradle').write_text('')
            (root / 'app/build.gradle').write_text("implementation 'g:a:1.0'\nimplementation 'g:a:2.0'\n")
            self.assertEqual(AUDIT.declared(root), [('g', 'a', '1.0'), ('g', 'a', '2.0')])

    def test_date_filter_does_not_propose_downgrade(self):
        metadata = b'<metadata><versioning><versions><version>2.0</version><version>1.0</version></versions></versioning></metadata>'
        def fetch(request, timeout):
            response = io.BytesIO(metadata if request.get_method() == 'GET' else b'')
            response.headers = {'Last-Modified': 'Fri, 06 Nov 2026 00:00:00 GMT'}
            return response
        with tempfile.TemporaryDirectory() as temporary, patch.object(AUDIT.urllib.request, 'urlopen', side_effect=fetch) as mocked:
            record = AUDIT.snapshot(('g', 'a', '1.5'), Path(temporary), datetime(2026, 11, 5, tzinfo=timezone.utc))
        self.assertEqual(record['status'], 'no_date_eligible_candidate')
        self.assertNotIn('candidate', record)
        self.assertEqual(mocked.call_count, 2)

    def test_vendor_feature_variant_is_preserved(self):
        metadata = b'<metadata><versioning><versions><version>8.0-part</version><version>7.0-full</version></versions></versioning></metadata>'
        def fetch(request, timeout):
            response = io.BytesIO(metadata if request.get_method() == 'GET' else b'')
            response.headers = {'Last-Modified': 'Wed, 04 Nov 2026 00:00:00 GMT'}
            return response
        with tempfile.TemporaryDirectory() as temporary, patch.object(AUDIT.urllib.request, 'urlopen', side_effect=fetch):
            record = AUDIT.snapshot(('com.aliyun.test', 'a', '6.0-full'), Path(temporary), datetime(2026, 11, 5, tzinfo=timezone.utc))
        self.assertEqual(record['candidate'], '7.0-full')

    def test_runtime_lock_configuration_is_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            lock = Path(temporary) / 'gradle.lockfile'
            lock.write_text('# lock\ng:a:2.0=compile,runtime\ng:b:1.0=runtimeTest\nempty=other\n')
            self.assertEqual(AUDIT.locked_coordinates(lock, 'runtime'), [('g', 'a', '2.0')])

    def test_every_app_alias_exists(self):
        catalog = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())
        aliases = {key.replace('-', '.') for key in catalog['libraries']}
        used = set(re.findall(r'\blibs\.([a-zA-Z0-9.]+)', (ROOT / 'app/build.gradle').read_text()))
        self.assertEqual(used - aliases, set())
        self.assertEqual(aliases - used, set())

    def test_family_versions_match(self):
        catalog = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())
        for family in ('room', 'media3', 'glide', 'andserver'):
            entries = [value for key, value in catalog['libraries'].items() if key == family or key.startswith(family + '-')]
            self.assertTrue(entries)
            self.assertTrue(all(value['version']['ref'] == family for value in entries))

    def test_catalog_participates_in_inventory(self):
        values = AUDIT.declared(ROOT)
        self.assertIn(('org.nanohttpd', 'nanohttpd', '2.3.1'), values)
        self.assertIn(('org.nanohttpd', 'nanohttpd-websocket', '2.3.1'), values)
        self.assertIn(('androidx.constraintlayout', 'constraintlayout', '2.2.2'), values)
        self.assertIn(('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21'), values)

    def test_toolchain_has_no_independent_r8_or_legacy_andserver_plugin(self):
        root = (ROOT / 'build.gradle').read_text()
        app = (ROOT / 'app/build.gradle').read_text()
        self.assertNotIn('com.android.tools:r8:', root)
        self.assertNotIn("apply plugin: 'com.yanzhenjie.andserver'", app)
        self.assertNotIn('applicationVariants', app)
        self.assertIn('com.android.legacy-kapt', app)

    def test_generated_identity_contract_is_retained(self):
        generator = (ROOT / 'gradle/andserver.gradle').read_text()
        self.assertIn('@com.yanzhenjie.andserver.annotation.AppInfo', generator)
        self.assertIn("id + '.andserver'", generator)
        self.assertIn('addGeneratedSourceDirectory', generator)
        self.assertNotIn('registerJavaGeneratingTask', generator)

    def test_python_not_configured_by_default(self):
        settings = (ROOT / 'settings.gradle').read_text()
        self.assertIn("providers.gradleProperty('enablePython')", settings)
        app = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("variant.enable = false", app)


if __name__ == '__main__':
    unittest.main()
