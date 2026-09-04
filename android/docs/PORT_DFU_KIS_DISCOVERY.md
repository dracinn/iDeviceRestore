# Port DFU and KIS discovery

iDeviceRestore recognizes two additional Apple USB personalities used by current libirecovery:

- Port DFU: Apple VID `0x05ac`, PID `0xf014`
- KIS / Apple Debug USB: Apple VID `0x05ac`, PID `0x1881`

These identities are deliberately separate from the existing operational `DFU`, `Recovery`, and `WTF` modes. Recognition does not grant restore capability and does not bypass `DeviceSupportPolicy` or `DeviceSupportMatrix`.

## Current scope

This first implementation is read-only/discovery-oriented:

- label Port DFU and KIS during USB scans;
- log their interface and endpoint descriptors;
- allow safe interface selection for explicit diagnostics;
- parse `PREV` from Apple boot identifier strings when present;
- expose Image4-awareness from the `IBFL` flag;
- provide mode-aware Port DFU AP nonce parsing, including the upstream byte-order reversal.

KIS protocol portal reads/writes are not implemented here. Port DFU is not routed through the classic DFU transport. Automatic restore commands, uploads, resets, environment mutation, and mode transitions remain unchanged.

## Hardware validation

Before any state-changing Port DFU or KIS operation is added, hardware testing should capture:

1. VID/PID and Android USB descriptor/interface layout;
2. accessible string descriptors and boot identifiers;
3. Port DFU nonce availability and size without logging nonce bytes;
4. attach/detach/re-enumeration behavior;
5. whether the same physical device can be correlated across Port DFU, KIS, and Recovery using stable identifiers.

M3/M4/M5 restore operations remain blocked by the existing support policy even when these USB personalities are detected.
