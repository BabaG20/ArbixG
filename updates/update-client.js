'use strict';

const SHA256_RE = /^[a-f0-9]{64}$/i;

/**
 * Validate an update manifest before the Android layer downloads anything.
 * This module deliberately performs no network access and never installs APKs.
 */
function validateManifest(manifest, expectedPackage = 'com.arbitri.app') {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    return { valid: false, reason: 'manifest_not_object' };
  }
  const required = ['packageName', 'versionName', 'versionCode', 'minSupportedVersionCode', 'apkUrl', 'sha256'];
  for (const key of required) {
    if (!(key in manifest)) return { valid: false, reason: `missing_${key}` };
  }
  if (manifest.packageName !== expectedPackage) return { valid: false, reason: 'package_mismatch' };
  if (typeof manifest.versionName !== 'string' || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(manifest.versionName)) {
    return { valid: false, reason: 'invalid_version_name' };
  }
  if (!Number.isSafeInteger(manifest.versionCode) || manifest.versionCode < 1) {
    return { valid: false, reason: 'invalid_version_code' };
  }
  if (!Number.isSafeInteger(manifest.minSupportedVersionCode) || manifest.minSupportedVersionCode < 1) {
    return { valid: false, reason: 'invalid_min_supported_version_code' };
  }
  let url;
  try { url = new URL(manifest.apkUrl); } catch { return { valid: false, reason: 'invalid_apk_url' }; }
  if (url.protocol !== 'https:') return { valid: false, reason: 'apk_url_must_use_https' };
  if (typeof manifest.sha256 !== 'string' || !SHA256_RE.test(manifest.sha256)) {
    return { valid: false, reason: 'invalid_sha256' };
  }
  if (manifest.releaseNotes !== undefined && typeof manifest.releaseNotes !== 'string') {
    return { valid: false, reason: 'invalid_release_notes' };
  }
  return { valid: true };
}

/**
 * Decide whether an update is available or the installed build is below the
 * publisher's supported floor. The caller must obtain manifest over HTTPS.
 */
function evaluateUpdate(manifest, installed, expectedPackage = 'com.arbitri.app') {
  const validation = validateManifest(manifest, expectedPackage);
  if (!validation.valid) return { status: 'invalid', reason: validation.reason };
  if (!installed || installed.packageName !== expectedPackage ||
      !Number.isSafeInteger(installed.versionCode) || installed.versionCode < 1) {
    return { status: 'invalid', reason: 'invalid_installed_app' };
  }
  if (installed.versionCode < manifest.minSupportedVersionCode) {
    return { status: 'blocked', reason: 'installed_version_below_minimum', latestVersion: manifest.versionName };
  }
  if (manifest.versionCode <= installed.versionCode) return { status: 'current' };
  return {
    status: 'update_available',
    versionName: manifest.versionName,
    versionCode: manifest.versionCode,
    apkUrl: manifest.apkUrl,
    sha256: manifest.sha256.toLowerCase(),
    releaseNotes: manifest.releaseNotes || ''
  };
}

module.exports = { validateManifest, evaluateUpdate };
