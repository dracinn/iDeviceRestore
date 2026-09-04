# Device support matrix

iDeviceRestore separates device detection from restore support. A device may be identifiable and useful for diagnostics without being considered safe for an end-to-end restore.

## Status definitions

- **Supported** — a complete restore has been demonstrated with the relevant iDeviceRestore restore path.
- **Experimental** — identification and one or more restore stages are expected to work, but end-to-end restore success is not sufficiently validated.
- **Blocked** — restore operations are intentionally refused.
- **Unknown** — the device can be detected, but there is not yet enough evidence for a restore-support claim.

## Capability stages

The matrix tracks these stages independently:

1. Identification
2. Recovery communication
3. DFU communication
4. Firmware preparation
5. Full restore

A device is not promoted to **Supported** until Full restore has been demonstrated.

## Current Mac policy

| Device family | Status | Current capability claim |
| --- | --- | --- |
| M1 Macs | Experimental | Identification, Recovery, DFU, firmware preparation |
| M2 Macs | Experimental | Identification, Recovery, DFU, firmware preparation |
| M3 Macs | Blocked | Identification only; restore operations refused |
| M4 Macs | Blocked | Identification only; restore operations refused |
| M5 Macs | Blocked | Identification only; restore operations refused |
| Other Macs | Unknown | Identification only until evidence is recorded |

M1/M2 classification is intentionally conservative. Existing restore access is not reduced, but the application must not describe these Macs as fully supported until an end-to-end restore has been validated on hardware.

M3/M4/M5 blocking preserves the existing iDeviceRestore safety policy. Detection and diagnostics may still identify the device, but restore entry points must continue to reject it.

## Promotion rule

When hardware testing succeeds, record the model identifier, firmware version/build, host version, restore result, and relevant diagnostic log. Promote only the tested model/family supported by that evidence; do not infer support for newer generations from protocol similarity alone.
