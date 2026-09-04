# Branch migration testing

This checklist tracks validation required before the legacy development branches are finalized against the cleaned `master` history.

## Baseline

- [ ] Android debug APK assembles from the cleaned `master` tree.
- [ ] Existing Android CI workflow completes successfully.
- [ ] No upstream non-Android workflows are removed or replaced by migration work.
- [ ] App launches on the Android 14 test host.
- [ ] Apple USB scan remains read-only until an explicit restore action is selected.

## restore-communication

The legacy branch has no commits unique to the cleaned `master` tip.

- [ ] Confirm candidate branch tree equals `master`.
- [ ] Confirm Recovery device discovery still identifies the attached Apple device.
- [ ] Confirm DFU device discovery still identifies the attached Apple device.
- [ ] Confirm read-only Recovery diagnostics complete without changing device state.
- [ ] Confirm read-only DFU diagnostics complete without changing device state.

## android-ci

The legacy CI branch is superseded by the Android CI workflow already present on `master`.

- [ ] Confirm `.github/workflows/android-ci.yml` builds the debug APK.
- [ ] Confirm the debug APK artifact is uploaded.
- [ ] Confirm legacy branch deletions of upstream `build.yml` and `curl.yml` are not migrated.
- [ ] Confirm release workflow remains separate from normal pull-request CI.

## android-download-framework

This branch contains unique downloader code and must be reconciled with the current Android package/layout rather than rebased mechanically.

### Build and unit-level checks

- [ ] Port downloader classes into the current `com.idevicerestore.android` package structure or an approved subpackage.
- [ ] Reconcile Gradle dependencies with the current Android application instead of replacing current build files wholesale.
- [ ] Assemble a debug APK with the migrated downloader enabled.
- [ ] Verify download job/service declarations in `AndroidManifest.xml`.
- [ ] Verify cancellation, retry, resume, and partial-file handling.
- [ ] Verify filename/path sanitization and per-device firmware directory layout.
- [ ] Verify existing firmware catalog and M3/M4/M5 support policy remain authoritative.

### Device/storage checks

- [ ] Create the firmware root directory on first use.
- [ ] Create a device-specific subdirectory for `MacBookAir10,1`.
- [ ] Download a signed firmware file into the expected device directory.
- [ ] Interrupt a download and confirm a subsequent run safely resumes or restarts according to policy.
- [ ] Confirm insufficient-storage and network-failure paths are user-visible and do not corrupt completed downloads.
- [ ] Confirm a completed firmware file can be selected by the existing restore-preparation pipeline.

## Finalization gate

Do not move the original development branch refs until all applicable checks pass. Preserve the dated archive branches until at least one post-migration release has been validated.
