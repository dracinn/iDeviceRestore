# Upstream desktop CI reference

This directory keeps the upstream `libimobiledevice/idevicerestore` desktop CI definitions separate from this fork's active GitHub Actions workflows.

Files in this directory are **reference-only**. GitHub only executes workflow files stored directly under `.github/workflows`, so these desktop definitions do not appear as runnable Actions for this fork and do not participate in the Android required check.

Active fork workflows remain:

- `.github/workflows/android-ci.yml`
- `.github/workflows/android-release.yml`

The reference copies should track upstream when source synchronization work needs to inspect or preserve desktop CI behavior. Do not move them into `.github/workflows` unless desktop CI is intentionally being enabled for this fork.

Upstream snapshot recorded here: `libimobiledevice/idevicerestore@60192e97f87d1bbab5c493684e0a245b0966363f`.
