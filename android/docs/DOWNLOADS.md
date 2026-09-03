# Android firmware download framework

The Android download layer is intentionally separate from restore execution. A completed IPSW becomes an input to metadata inspection; it does not automatically enable restore, revive, erase, reset, reboot, or firmware upload.

## Scheduling

- Android 14 / API 34 and newer: `JobScheduler` user-initiated data-transfer (UIDT) job.
- Android 8–13 / API 26–33: foreground `WorkManager` worker.

Both backends call the same `FirmwareDownloadEngine` and emit the same `FirmwareDownloadEvents` broadcasts so UI code does not need to care which scheduler is active.

## Transfer behavior

`FirmwareDownloadEngine`:

- accepts HTTPS URLs only;
- keeps normal platform TLS certificate and hostname verification enabled;
- writes to app-scoped external Downloads storage when available;
- downloads into `<name>.ipsw.part` and only renames to `<name>.ipsw` after verification;
- stores ETag / Last-Modified metadata beside partial downloads;
- resumes with `Range` and `If-Range` only when a validator is available;
- restarts cleanly when a server cannot resume a partial transfer;
- checks available storage when the expected remaining size is known;
- supports an expected byte count;
- supports trusted SHA-256 or SHA-1 digests supplied by the future firmware catalog layer;
- computes SHA-256 when no trusted digest is supplied;
- persists metadata for completed downloads so an existing IPSW is not trusted solely because a filename exists;
- preserves valid partial data after interruption so the next explicit user request can resume.

The upstream `src/download.c` helper is not used as the Android transfer backend. Android must not inherit its disabled TLS peer-verification behavior.

## Integration boundary

The intended flow is:

1. Device/session layer identifies the attached Apple hardware.
2. Firmware catalog layer resolves compatible signed firmware and produces a `FirmwareDownloadRequest`.
3. `FirmwareDownloadCoordinator` schedules the transfer.
4. UI observes `FirmwareDownloadEvents` for progress, completion, cancellation, or failure.
5. Only the finalized `.ipsw` path is handed to the IPSW inspection layer.
6. Restore/revive remains disabled until inspection and the existing safety gates approve the requested operation.

## Remaining work

- signed-firmware catalog/resolver and device-to-firmware matching;
- Compose download UI that creates requests and exposes cancel/retry actions;
- completed-download persistence/history beyond per-file verification metadata;
- SAF export/copy for users who want the IPSW outside app-scoped storage;
- test fixtures for HTTP range resume, validator changes, checksum mismatch, low storage, cancellation, and process restart;
- handoff from a verified download into read-only IPSW metadata/variant inspection.
