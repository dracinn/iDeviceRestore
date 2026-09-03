# iDeviceRestore for Android

Android is the active development target for this fork. The app is being built around direct USB-host communication with Apple devices in DFU and Recovery/iBoot modes, with the long-term goal of bringing the iDeviceRestore restore flow to Android.

The first milestone intentionally avoids native `libusb`: Android owns USB permission through `UsbDeviceConnection`, which lets us validate device detection, interface claiming and protocol traffic before adding JNI/native restore components.

## Current functionality

- Detect Apple USB devices (VID `0x05AC`)
- Classify common DFU (`0x1227`), Recovery (`0x1280`, `0x1281`) and WTF (`0x1222`) product IDs
- Request Android USB-host permission
- Enumerate interfaces and endpoints
- Claim a USB interface
- DFU: issue `DFU_GETSTATUS` only
- Recovery/iBoot: send `getenv build-version` and attempt to read the response
- On-device diagnostic logging
- Firmware catalog client for IPSW metadata
- Signed/unsigned firmware filtering by Apple device identifier
- Apple-hosted IPSW URL, version, build, size, release date and SHA-1 metadata
- Resumable HTTPS firmware downloads
- Multi-connection ranged downloads when the server supports byte ranges
- Retry with exponential backoff, cancellation and progress callbacks
- IPSW size and SHA-1 verification before finalizing a download

## Firmware catalog

`FirmwareCatalog.kt` follows the same high-level firmware model used by Mist. It obtains firmware metadata from the IPSW Downloads API (`api.ipsw.me`), which Mist also credits for firmware metadata, then exposes the Apple-hosted IPSW URL and verification fields to the Android restore stack.

Typical use:

```kotlin
val catalog = FirmwareCatalog(logger = ::log)
val latest = catalog.latestSigned("iPhone15,2")
```

The catalog supports:

- listing known Apple device identifiers
- listing all IPSWs for a device
- filtering to currently signed firmware
- finding the newest signed entry
- exporting a device firmware list to JSON for diagnostics/offline caching

Remote metadata must not be treated as trusted restore policy. Before a restore, the app should still verify the target identity against the connected device and obtain the required Apple signing/personalization response.

## Firmware downloader

`FirmwareDownloader.kt` is inspired by aria2's HTTP transfer behavior without embedding an external command-line binary. The downloader is usable by later restore-state code and can also be wrapped by an Android foreground service when background download UX is added.

```kotlin
val firmware = catalog.latestSigned("iPhone15,2") ?: error("No signed firmware")
val request = FirmwareDownloader.Request(
    url = firmware.url,
    destination = File(filesDir, firmware.fileName),
    expectedSize = firmware.fileSize,
    expectedSha1 = firmware.sha1,
    connections = 4
)

val handle = FirmwareDownloader(logger = ::log).start(request) { progress ->
    log("IPSW ${progress.downloadedBytes}/${progress.totalBytes} ${progress.bytesPerSecond} B/s")
}

// handle.cancel() can stop the transfer. Partial segment files are retained for resume.
```

Transfer behavior:

- HTTPS only
- HEAD/range probing before transfer
- up to 16 ranged connections
- partial segment retention and resume
- sequential fallback when ranges are unavailable
- exponential retry backoff
- progress/speed callbacks
- cancellation
- assembled file-size validation
- SHA-1 verification before atomic-ish finalization to the requested destination

The implementation deliberately keeps storage separate from transfer logic. A later UI can download to app-private storage directly or adapt a user-selected Storage Access Framework destination without coupling restore logic to an Activity.

## Phone-only development with GitHub Actions

No local computer is required for compilation.

1. Edit files under `android/` from GitHub on your phone or tablet.
2. Commit changes to the `android-ci` branch.
3. `Android CI` builds a debug APK on a GitHub-hosted runner.
4. Open the successful Actions run and download the `iDeviceRestore-android-debug` artifact.
5. Extract and install the APK on an Android device.

Only Android workflows are kept on the Android development branch. Desktop build workflows from the upstream project are removed there.

## Public releases and Obtainium

Public releases are produced by `.github/workflows/android-release.yml`.

A release tag such as `v0.1.0` builds a signed release APK and publishes a GitHub Release with this stable asset name:

`iDeviceRestore.apk`

That makes the repository directly usable as an Obtainium source. In Obtainium, add the GitHub repository URL and use the GitHub Releases source. New version tags will appear as updates.

### Signing is required once

Android requires every update to be signed with the same private key. Do not use GitHub's temporary debug key for public releases.

Create a release keystore once and add these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded `.jks` keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the original keystore backed up securely. Losing it means existing installations cannot update to APKs signed with a replacement key.

The release workflow reads those secrets only at build time. The private signing key is not committed to the repository or included in release assets.

## Versioning

Use normal Android-style semantic release tags:

- `v0.1.0`
- `v0.1.1`
- `v0.2.0`

The tag becomes `versionName`. GitHub Actions supplies an increasing `versionCode` for install/update compatibility.

## Physical testing

GitHub Actions can compile and package the app, but hosted runners cannot physically connect to an iPhone or iPad. Real DFU/recovery tests require an Android phone/tablet supporting USB Host/OTG and a data-capable USB-C/OTG cable or hub.

### DFU

1. Put the Apple device into DFU mode.
2. Connect it to the Android host.
3. Accept the USB permission prompt.
4. Tap **Probe DFU / Recovery**.
5. Save the interface/endpoint and DFU status log.

### Recovery

1. Put the Apple device into Recovery mode.
2. Connect it to Android.
3. Tap **Probe DFU / Recovery**.
4. The app sends the non-destructive `getenv build-version` command and attempts to read its response.

## Architecture direction

`iDeviceRestore` normally communicates through `libirecovery`, which opens USB using libusb. Android instead grants USB access to an application and exposes the device through `UsbDeviceConnection`.

The Android port will therefore evolve toward an `irecv`-compatible Android transport/JNI layer rather than forcing desktop-style USB ownership into the app.

The firmware layer now has two reusable pieces: catalog discovery and verified/resumable transfer. The next restore milestones can consume those without putting network logic into the USB transports.

## Roadmap

- M1: USB discovery + DFU_GETSTATUS + recovery query
- M2: `irecv`-compatible transport/JNI + ECID/CPID/BDID + hotplug lifecycle
- M3: firmware catalog UI + download manager/foreground-service integration
- M4: controlled iBSS/iBEC extraction and transfer with progress reporting on dedicated test hardware
- M5: restore-mode usbmuxd/mobiledevice transport
- M6: IPSW parsing, TSS, personalization and complete restore state machine
