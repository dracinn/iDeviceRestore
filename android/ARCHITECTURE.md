# Android source organization

The Android app is being migrated away from a single flat package incrementally so proven restore behavior stays hardware-testable after every refactor.

## Logical areas

| Area | Responsibility | Current examples |
| --- | --- | --- |
| UI / activities | Android lifecycle, screen state, custom controls, user confirmation | `MainActivity`, `BootDiagnosticsActivity`, `*Button`, `*TextView` |
| USB / transport | Apple USB discovery, DFU, Recovery, upload and reservation primitives | `AppleUsb`, `DfuTransport`, `RecoveryTransport`, `UsbOperationReservation` |
| Restore orchestration | Bounded DFU → Stage-1 → Stage-2 → current restore-entry chain | `PrearmedStage1IbecButton`, `AutomatedStage2TestButton`, `Stage2FirmwareBatchTestButton` |
| Firmware | Catalogs, download, storage, IPSW extraction and validation | `FirmwareCatalog`, `Aria2cFirmwareDownloader`, `FirmwareStorage`, `Ipsw*` |
| Signing / Image4 | TSS, plist handling, personalization and ticket stores | `Tss*`, `Image4*`, `RestoreImage4Personalizer` |
| Diagnostics | Read-only probes, boot diagnostics, evidence and log snapshots | `BootDiagnostic*`, `Stage2ReadonlyProbeButton`, `*EvidenceStore` |

## Refactor rules

1. Preserve the current hardware safety boundary during structural changes. Refactors must not introduce restored/usbmux traffic, restore payload delivery, erase, or later restore operations.
2. Keep `Run Current Stage-2 Test` as the single cumulative hardware-test entry point. Diagnostic controls remain independent helpers, never prerequisites.
3. Extract shared Android UI plumbing before moving restore state machines. `AndroidUiBridge` owns hosting-activity lookup and common UI-thread logging/status updates.
4. Prefer small engine/session classes for USB and restore behavior. Custom Views should coordinate user interaction and delegate protocol work rather than accumulating new transport logic.
5. Move source files into subpackages only in cohesive groups with all manifest/XML/import references updated in the same PR. Avoid mass renames that make hardware regressions difficult to isolate.
6. Keep protocol logging explicit at hardware boundaries so a refactor can be compared against previous diagnostic evidence.

## Target package direction

The long-term package layout should converge toward:

```text
com.idevicerestore.android
├── diagnostics
├── firmware
├── restore
├── signing
├── transport
└── ui
```

The migration should be gradual. Existing classes may remain in the root package until their dependencies are sufficiently isolated to move safely.
