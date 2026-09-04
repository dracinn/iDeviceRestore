# Branch migration testing

This checklist tracks validation required before the legacy development branches are finalized against the cleaned `master` history.

## Baseline

- [x] Android debug APK assembles from the cleaned `master` tree.
- [x] Existing Android CI workflow completes successfully.
- [x] No upstream non-Android workflows are removed or replaced by migration work.
- [ ] App launches on the Android 14 test host.
- [ ] Apple USB scan remains read-only until an explicit restore action is selected.

Evidence: PR #3 Android CI run 35 completed successfully with `build-debug-apk`, and the post-merge Android Release run 141 completed successfully on `master` commit `16aacdd91135da405e009d15ff6b69183f79a5bc`.

## restore-communication

The legacy branch has no commits unique to the cleaned `master` tip.

- [ ] Confirm candidate branch tree equals `master`.
- [ ] Confirm Recovery device discovery still identifies the attached Apple device.
- [ ] Confirm DFU device discovery still identifies the attached Apple device.
- [ ] Confirm read-only Recovery diagnostics complete without changing device state.
- [ ] Confirm read-only DFU diagnostics complete without changing device state.

## android-ci

The legacy CI branch is superseded by the Android CI workflow already present on `master`.

- [x] Confirm `.github/workflows/android-ci.yml` builds the debug APK.
- [x] Confirm the debug APK artifact is uploaded.
- [x] Confirm legacy branch deletions of upstream `build.yml` and `curl.yml` are not migrated.
- [x] Confirm release workflow remains separate from normal pull-request CI.

## android-download-framework

The downloader framework documented by the legacy branch is already present in the cleaned `master` history. Do not port or overwrite that implementation mechanically. Validate the current implementation first, then migrate only branch-only deltas that are proven necessary after comparison with `master`.

### Build and unit-level checks

- [x] Compare the legacy downloader branch with `master` and document any proven branch-only behavior or files that are still required.
- [x] Confirm the current downloader classes use the active `com.idevicerestore.android` package/layout and integrate with the existing application structure.
- [x] Reconcile only any proven missing dependencies or declarations; do not replace current Gradle or manifest files wholesale.
- [x] Assemble a debug APK with the existing downloader enabled.
- [x] Verify current download job/service declarations in `AndroidManifest.xml`.
- [x] Verify cancellation, retry, resume, and partial-file handling by code inspection.
- [x] Verify filename/path sanitization and per-device firmware directory layout by code inspection.
- [x] Verify existing firmware catalog and M3/M4/M5 support policy remain authoritative by code inspection.

Evidence: the current implementation includes `FirmwareDownloader`, `FirmwareDownloadManager`, `FirmwareDownloadService`, `FirmwareStorage`, `FirmwareIntegrity`, and catalog integration in the active Android package. `AndroidManifest.xml` declares `.FirmwareDownloadService` as a non-exported `dataSync` foreground service and includes the required network/storage/foreground-service permissions. `FirmwareDownloader` implements HTTPS-only transfer, retries, ranged resume, segmented transfer, cancellation, size/SHA-1 verification, and partial cleanup. `FirmwareStorage` sanitizes identifiers/filenames and uses `/storage/emulated/0/iDeviceRestore/Firmware/<identifier>/...`.

### Device/storage checks

- [ ] Create the firmware root directory on first use.
- [ ] Create a device-specific subdirectory for `MacBookAir10,1`.
- [ ] Download a signed firmware file into the expected device directory.
- [ ] Interrupt a download and confirm a subsequent run safely resumes or restarts according to policy.
- [ ] Confirm insufficient-storage and network-failure paths are user-visible and do not corrupt completed downloads.
- [ ] Confirm a completed firmware file can be selected by the existing restore-preparation pipeline.

These checks require execution on the Android test host and must not be marked complete from repository inspection alone.

## Finalization gate

Do not move the original development branch refs until all applicable checks pass. Preserve the dated archive branches until at least one post-migration release has been validated.
