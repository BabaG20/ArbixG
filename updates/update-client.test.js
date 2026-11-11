'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { validateManifest, evaluateUpdate } = require('./update-client');

const manifest = {
  packageName: 'com.arbitri.app',
  versionName: '1.4.0',
  versionCode: 5,
  minSupportedVersionCode: 4,
  apkUrl: 'https://downloads.example.com/arbixg-1.4.0.apk',
  sha256: 'a'.repeat(64),
  releaseNotes: 'Stability and scanner improvements'
};
const installed = { packageName: 'com.arbitri.app', versionCode: 4 };

test('accepts a well-formed HTTPS update manifest', () => {
  assert.deepEqual(validateManifest(manifest), { valid: true });
});

test('rejects a package name mismatch', () => {
  assert.equal(validateManifest({ ...manifest, packageName: 'other.app' }).reason, 'package_mismatch');
});

test('rejects non-HTTPS APK URLs', () => {
  assert.equal(validateManifest({ ...manifest, apkUrl: 'http://downloads.example.com/app.apk' }).reason, 'apk_url_must_use_https');
});

test('rejects malformed checksums', () => {
  assert.equal(validateManifest({ ...manifest, sha256: 'not-a-hash' }).reason, 'invalid_sha256');
});

test('offers an update only when versionCode is newer', () => {
  assert.equal(evaluateUpdate(manifest, installed).status, 'update_available');
  assert.equal(evaluateUpdate({ ...manifest, versionCode: 4 }, installed).status, 'current');
});

test('blocks builds below the publisher supported minimum', () => {
  assert.equal(evaluateUpdate({ ...manifest, minSupportedVersionCode: 5 }, installed).status, 'blocked');
});
