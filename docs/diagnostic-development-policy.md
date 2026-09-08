# Diagnostics-first development policy

Boot Diagnostics is the development and hardware-validation surface for iDeviceRestore. The normal app is reserved for functions that have already been proven on hardware with a defined device scope, bounded failure behavior, and useful logs.

## Development rule

Before adding support for a new Apple device, changing a device-specific boot path, or fixing a hardware-specific behavior, first make the relevant evidence reproducible in Boot Diagnostics. The diagnostic result must distinguish expected success, failure, missing prerequisites, observed-but-unverified evidence, and device-specific non-applicability.

Unknown devices must not inherit M1 assumptions. They should be captured as evidence until their identifiers, USB personality, transport behavior, and safe boundaries are proven.

## Main app promotion criteria

A function may be exposed as a normal app feature only after:

1. Its supported device scope is explicit.
2. Required USB/firmware/signing prerequisites are testable.
3. Hardware success is repeatable.
4. Failure behavior is bounded and logged.
5. Any device mutation is explicitly documented and confirmed by the user.
6. The stop boundary is enforced in code.
7. The corresponding diagnostic evidence remains available for future regressions.

Unverified or presentation-only controls must not appear interactive in the main app.

## Boot Diagnostics test classes

### Read-only functional matrix

Safe observation only. It may inspect USB descriptors, Apple boot personality, structured boot identifiers, Recovery getenv values, console input, and transition history. It must not send boot payloads, mutate iBoot environment variables, issue boot/go/bootx, start restore traffic, or erase data.

### Active hardware development tests

These require explicit confirmation and show their limitations before execution. Current active tests include the hardware-proven M1 CPID 0x8103 Stage-2 boot path and the current cumulative restore-entry boundary test. Their existing protocol stop boundaries remain unchanged.

### Future devices

New device-specific active tests stay in Boot Diagnostics until the promotion criteria above are satisfied. NOT_APPLICABLE and OBSERVED are preferred over false failures when a device does not match a proven profile.
