# iDeviceRestore Android Lab (Milestone 1)

A minimal Android USB-host app for validating direct communication with Apple devices in DFU and Recovery/iBoot modes. It is intentionally **not** a restore/flashing app yet.

The protocol structure is based on this `dracinn/iDeviceRestore` repository and its `libirecovery` dependency. The first milestone avoids native `libusb` so Android can grant and own the USB file descriptor normally.

## What works in this scaffold

- Detect Apple USB devices (VID `0x05AC`)
- Classify common DFU (`0x1227`), Recovery (`0x1280`, `0x1281`) and WTF (`0x1222`) product IDs
- Request Android USB permission
- Enumerate interfaces/endpoints
- Claim a USB interface
- DFU: issue `DFU_GETSTATUS` only (non-flashing probe)
- Recovery/iBoot: send `getenv build-version` and attempt to read console data from bulk IN
- Log all results on screen

## Develop without a computer

The `Android CI` GitHub Actions workflow builds the app entirely on GitHub-hosted runners.

1. Edit files under `android/` using GitHub's web editor from a phone or tablet.
2. Commit changes to the `android-ci` branch.
3. Open the repository's **Actions** tab and select **Android CI**.
4. Open the latest successful run and download the `iDeviceRestore-android-debug` artifact.
5. Extract the ZIP on Android and install `app-debug.apk` after allowing installs from your browser/file manager.

The workflow can also be started manually with **Run workflow**. It uses JDK 17 and Gradle 8.9 because Android Gradle Plugin 8.7.x requires those versions.

## Why the first version is Kotlin USB instead of compiling iDeviceRestore directly

`iDeviceRestore` uses `libirecovery`, which normally opens USB through libusb. Android requires app-level USB permission and hands the app a `UsbDeviceConnection`. Starting with Android's USB Host API proves the actual DFU/recovery request shapes and device/interface behavior before adding JNI/native libraries.

Milestone 2 should add a native bridge and either:

1. modify/fork `libirecovery` to accept a pre-opened Android USB file descriptor, or
2. keep an Android-specific USB backend behind a small `irecv`-compatible adapter.

The second option is usually cleaner for Android because the app remains owner of permission, attach/detach and lifecycle.

## Physical test hardware

GitHub Actions can compile and package the app, but hosted runners cannot connect to your iPhone/iPad. For real DFU/recovery communication you need a physical Android phone/tablet that supports USB Host/OTG. Connect the Apple device to the Android device with a data-capable OTG/USB-C cable or hub.

## First test sequence

### DFU

1. Put an iPhone/iPad into DFU mode.
2. Connect it to the Android host.
3. Accept the USB permission prompt.
4. Tap **Probe DFU / Recovery**.
5. Record the complete on-screen log, including interface and endpoint information.

### Recovery

1. Put the device into Recovery mode.
2. Connect it to Android.
3. Tap **Probe DFU / Recovery**.
4. The app sends only `getenv build-version` and attempts to read the response.

If command write succeeds but no response is read, record the interface/endpoint dump from the app. The next step is to match libirecovery's exact recovery interface/alt-setting behavior for that device generation.

## Safety scope

Milestone 1 deliberately excludes firmware upload, erase, TSS signing, iBSS/iBEC transfer, restore mode services and baseband operations.

## Roadmap

- M1: USB discovery + DFU_GETSTATUS + recovery query
- M2: JNI `irecv` adapter + ECID/CPID/BDID parsing + hotplug lifecycle
- M3: iBSS/iBEC upload with progress callbacks on dedicated test hardware
- M4: restore-mode usbmuxd/mobiledevice transport
- M5: IPSW parsing, TSS, personalization and complete restore state machine
