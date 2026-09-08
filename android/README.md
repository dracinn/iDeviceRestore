# iDeviceRestore for Android

Android is the active development target for this fork. The app communicates directly with Apple devices over Android USB Host/OTG and is being developed toward upstream-compatible restore and non-destructive revive/update flows without requiring a desktop host.

> **Development status:** The Android implementation is experimental. The hardware-validated path described below is a bounded bring-up sequence on dedicated test hardware, not a completed macOS restore/revive implementation.

## Current functionality

- Apple USB discovery and DFU / WTF / Recovery personality detection
- Android USB permission, interface claiming, endpoint discovery, and hotplug/re-enumeration tracking
- Recovery/iBoot `getenv` probing and console diagnostics
- On-device verbose diagnostic logging with ECID/serial redaction for shared logs
- Firmware catalog discovery, signed-firmware filtering, persistent metadata caching, and manual firmware selection
- Verified/resumable Apple CDN IPSW downloads
- BuildIdentity parsing and device/firmware identity checks
- Apple TSS requests and Image4 personalization
- Apple-silicon LocalPolicy preparation
- Personalized iBSS/iBEC transport
- Manifest-driven Stage-1 and Stage-2 firmware preparation
- Bounded Apple-silicon DFU → Stage-1 → Stage-2 execution
- Bounded Stage-2 RestoreRamDisk upload and `ramdisk` activation
- Prepared RestoreDeviceTree upload + `devicetree` activation test, pending physical hardware validation

## Apple-silicon hardware validation

Physical validation is currently being performed with:

- **Target:** MacBook Air (M1, Late 2020), `MacBookAir10,1`, T8103 / CPID `0x8103`
- **Host:** Android 14 USB Host/OTG device
- **Observed iBoot build:** `mBoot-20457.1.29`

### Hardware-proven restore-entry path

As of September 7, 2026, the following bounded sequence has completed successfully on the physical M1 test Mac:

1. Detect Apple DFU (`05ac:1227`) and verify the connected target/foundation identifiers.
2. Resolve the selected BuildIdentity and obtain the required Apple signing material.
3. Obtain a separate LocalPolicy TSS response and construct the personalized empty `Ap,LocalPolicy` used by the Apple-silicon Stage-1 path.
4. Upload personalized iBSS and observe a fresh Recovery enumeration at `boot-stage=1`.
5. Send `Ap,LocalPolicy` using `lpolrestore`.
6. Send the manifest-declared Stage-1 firmware components using `firmware`. On the tested identity these are:
   - `Ap,RestoreCIO`
   - `Ap,RestoreTMU`
   - `RestoreANS`
   - `RestoreDCP`
7. Upload personalized iBEC, observe the upstream Apple-silicon settle interval, and send `go`.
8. Observe the Stage-1 USB device disappear and a fresh Recovery enumeration appear at `boot-stage=2` with the expected build.
9. Send all 12 tested non-Stage1 `IsLoadedByiBoot` firmware components with `firmware`:
   - `ANE`
   - `AOP`
   - `AVE`
   - `Ap,RestoreDCP2`
   - `Ap,RestoreSecurePageTableMonitor`
   - `Ap,RestoreTrustedExecutionMonitor`
   - `GFX`
   - `ISP`
   - `PMP`
   - `RestoreTrustCache`
   - `SIO`
   - `iBootData`
10. Upload the personalized `RestoreRamDisk` (220,212,130 bytes on the tested build).
11. Re-verify `boot-stage=2`, the expected build, and `auto-boot=true` after the upload.
12. Send `ramdisk`, wait the upstream two-second settle interval, and successfully query the Stage-2 environment again.

The successful post-activation observation reported:

```text
boot-stage=2
build-version=mBoot-20457.1.29
auto-boot=true
ramdisk-size=0x20000000
```

This establishes the current hardware-proven milestone as:

**DFU → iBSS → Stage-1 → LocalPolicy + Stage-1 firmware → iBEC → fresh Stage-2 → non-Stage1 iBoot firmware → RestoreRamDisk upload → `ramdisk` activation.**

### `ramdisk-delay` behavior

The tested M1/iBoot path returns an Android control-transfer failure for `getenv ramdisk-delay`. Current upstream behavior does not require that query to succeed before sending `ramdisk`, so the Android diagnostic path treats it as best-effort. Hardware testing confirmed that the subsequent `ramdisk` command succeeds and the Stage-2 command interface remains responsive.

### Next bounded hardware test

The current code advances one operation beyond the proven boundary:

1. Re-run the proven Stage-2 firmware + RestoreRamDisk path.
2. Validate the prepared personalized `RestoreDeviceTree` before USB I/O.
3. Upload `RestoreDeviceTree`.
4. Re-verify Stage-2/build/auto-boot.
5. Send `devicetree`, matching the current upstream ordering.
6. Observe the resulting environment.
7. **Stop before RestoreSEP / `rsepfirmware`.**

RestoreDeviceTree activation is therefore **implemented for the bounded diagnostic test but not yet hardware-proven**.

## Safety boundary

The diagnostic progression intentionally advances one isolated restore-entry operation at a time. Hardware-proven operations are kept as regression checks before the next boundary is exercised.

The current DeviceTree test still does **not** send:

- `setenv auto-boot false`
- `saveenv`
- `RestoreSEP` / `rsepfirmware`
- `RestoreKernelCache`
- restore boot arguments
- `bootx`
- restore or erase operations

The tested environment has continued to report `auto-boot=true`; persistent iBoot environment mutation has not been introduced into these bounded tests.

Apple-silicon generation support gates are separate from these diagnostic STOP boundaries. Advancing a bounded M1 test does not imply support for unvalidated M3/M4/M5 hardware.

## Restore-entry progression

| Stage | Status |
| --- | --- |
| Android DFU/Recovery USB transport | Hardware proven |
| Apple TSS + Image4 personalization | Hardware proven on tested M1 identity |
| Apple-silicon LocalPolicy | Hardware proven |
| iBSS → Stage-1 | Hardware proven |
| Stage-1 firmware prerequisites | Hardware proven |
| iBEC + `go` → fresh Stage-2 | Hardware proven |
| Stage-2 non-Stage1 iBoot firmware batch | Hardware proven (12 components on tested identity) |
| RestoreRamDisk upload | Hardware proven |
| `ramdisk` activation | Hardware proven |
| RestoreDeviceTree upload + `devicetree` | Implemented; hardware validation next |
| RestoreSEP + `rsepfirmware` | Not yet enabled in bounded test |
| RestoreKernelCache + restore boot arguments + `bootx` | Not yet enabled in bounded test |
| Full restore/revive state machine | In development |

## Firmware catalog and downloads

The Android firmware layer supports catalog discovery, persistent metadata caching, signed/unsigned filtering, manual firmware selection, Apple-hosted IPSW metadata, resumable downloads, range requests where supported, retry/backoff, progress reporting, and final size/hash verification.

Remote metadata is not treated as restore authorization. Before hardware operations, the app re-verifies the selected firmware/BuildIdentity against the connected device and obtains current Apple signing material as required.

## Building the Android app

The Android application lives under `android/`. GitHub Actions builds the debug APK for Android-impacting changes. The upstream desktop workflows are retained only as non-executable references outside `.github/workflows/` and are not run as this fork's Android CI.

For a local build, open the `android/` directory in a compatible Android Studio installation, or use the configured Gradle toolchain from a development environment with the Android SDK installed.

A successful debug build produces:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Public releases and signing

Public Android releases use the repository's Android release workflow. Android updates must continue to use the same release signing key; losing or replacing that key prevents existing installations from accepting an update signed with a different key.

## Physical testing

GitHub-hosted CI can compile the application but cannot validate Apple USB transitions. Physical DFU/Recovery testing requires a dedicated Apple target, an Android device with USB Host/OTG support, and a data-capable cable/hub.

The verbose diagnostic log is the authoritative evidence for bounded hardware milestones. Shared logs redact ECID and Apple serial information.

## Architecture direction

The retained desktop `idevicerestore` code normally reaches Apple recovery devices through `libirecovery`/libusb. Android instead owns USB permission and exposes devices through `UsbDeviceConnection`, so the Android implementation uses an Android-native transport while matching upstream protocol ordering and semantics where applicable.

The restore path is being brought up incrementally: preparation and personalization first, then individual Recovery payload/command transitions, then later the full restore/revive state machine. Persistent or destructive operations are deliberately separated from read-only and volatile boot-environment validation.

## Roadmap

Near-term progression after DeviceTree hardware validation:

1. Isolate and validate RestoreSEP + `rsepfirmware`.
2. Isolate RestoreKernelCache, restore boot arguments, and `bootx` behavior.
3. Determine the correct failure/recovery handling for persistent `auto-boot` changes before enabling them in a real restore/revive flow.
4. Bring up restore-mode transport and the remaining upstream restore protocol.
5. Separate user-facing restore and non-destructive revive/update policy from the low-level transport/state machine.
6. Expand hardware support only when the required signing, personalization, and boot-chain behavior is understood and physically validated for those generations.
