# Rate My TV Security Audit

**Date:** 2026-10-25  
**Scope:** `C:\tv\ratemytv` Android TV application and installed APK  
**Method:** Read-only source review, manifest review, APK signature/package inspection, and installed-package inspection over ADB.

## Executive summary

The most urgent issue is an exported broadcast receiver with no caller permission. It accepts internal debug and state-changing commands, including setting the OMDb key, dumping viewing data, clearing history, changing layout/theme, and forcing fake updates.

No code was changed during this audit.

## Findings

### High — exported receiver exposes internal commands

The receiver is exported without a permission:

- `app/AndroidManifest.xml:59`
- `app/src/dev/ratemytv/GlobalButtonReceiver.java:21`

The receiver handles unauthenticated actions such as:

- `dev.ratemytv.SETKEY`
- `dev.ratemytv.DUMP`
- `dev.ratemytv.CLEARHIST`
- `dev.ratemytv.CLEARALIAS`
- `dev.ratemytv.SETALIAS`
- `dev.ratemytv.RENAME`
- `dev.ratemytv.LAYOUT`
- `dev.ratemytv.THEME`
- `dev.ratemytv.FAKEUPDATE`

An installed application may be able to send explicit intents to this component and alter state or trigger data collection.

**Remediation:** Keep only the required `GLOBAL_BUTTON` handling in the exported receiver. Move test/debug actions into a non-exported receiver or a debug-only build. If an exported control surface is unavoidable, protect it with a signature-level permission and validate the caller.

### Medium — backup is enabled for sensitive app data

The manifest does not explicitly define backup behavior:

- `app/AndroidManifest.xml:39`

The installed package reports `ALLOW_BACKUP`. Backups may include the OMDb key, viewing history, aliases, and cached metadata.

**Remediation:** Explicitly disable backup, or add backup/data-extraction rules that exclude API keys and viewing history.

### Medium — API key is stored as plaintext preferences

The OMDb key is written directly to private `SharedPreferences`:

- `app/src/dev/ratemytv/GlobalButtonReceiver.java:27-31`

Private app storage is appropriate, but exposure increases when backup is enabled and the exported receiver can overwrite the value.

**Remediation:** Protect the receiver first, then consider Android Keystore-backed encryption for the key.

### Medium — poster downloads lack URL and resource limits

Poster URLs are opened directly:

- `app/src/dev/ratemytv/OverlayController.java:2217-2227`

The code does not explicitly enforce HTTPS, restrict hosts, set connection/read limits, cap response size, or cap decoded image dimensions.

**Remediation:** Allow only HTTPS, set timeouts, enforce a maximum response size, reject oversized bitmaps, and optionally allowlist known provider hosts.

### Medium — production logs contain viewing metadata

Media URIs, titles, descriptions, artists, and session details are logged:

- `app/src/dev/ratemytv/Watcher.java:180-208`

This can expose viewing history through diagnostic logs on development or privileged TV environments.

**Remediation:** Remove or reduce metadata logging in release builds. Log identifiers or counts instead of titles, descriptions, and media URIs.

### Low/medium — updater is not hash-pinned

The updater trusts the latest GitHub release metadata and downloads the asset URL:

- `app/src/dev/ratemytv/Updater.java:97-105`
- `app/src/dev/ratemytv/Updater.java:123-145`

Android package signature verification prevents installation of an APK signed by a different key, but an untrusted or compromised release can still cause update failures or denial of service.

**Remediation:** Validate HTTPS and the expected GitHub host, publish a trusted SHA-256 for each release, and verify the downloaded APK before opening the installer.

### Medium — release builds use a debug signing key

The build script creates and uses `debug.keystore`:

- `app/build.ps1:22-27`

Anyone obtaining that key could sign a replacement APK accepted as an upgrade.

**Remediation:** Use a protected production keystore outside the repository and keep signing credentials out of the build workspace and release artifacts.

## Positive checks

- APK signature v2 and v3 verification passed.
- Target SDK is 34.
- `ApkProvider` is explicitly non-exported.
- Accessibility and notification services require Android protected binding permissions.
- No embedded API keys, bearer tokens, or passwords were found in source.
- Network endpoints inspected in the app use HTTPS.

## Recommended order

1. Lock down `GlobalButtonReceiver`.
2. Disable or narrowly configure backup.
3. Replace the debug signing key with a protected release key.
4. Harden poster downloads.
5. Reduce sensitive production logging.
6. Add update hash verification.

## Verification performed

The installed APK reported package `dev.ratemytv`, version `1.11.8`, target SDK 34, and backup enabled. APK signature verification passed for v2 and v3 signatures.

