# Diagnostics-first development policy

Boot Diagnostics is the unrestricted development and hardware-validation surface for iDeviceRestore. The normal app is reserved for functions that have already been proven on hardware with an explicit supported-device scope, repeatable behavior, useful logs, and a clear user-facing contract.

## Development rule

Before adding support for a new Apple device, changing a device-specific boot path, extending restore behavior, or fixing a hardware-specific issue, first make the relevant behavior reproducible in Boot Diagnostics.

Boot Diagnostics is not limited to read-only work. Experimental transport, boot-chain, firmware, environment, restore, mutation, recovery, and device-support code may all be developed and exercised there. Each individual test should accurately state what it currently does, what prerequisites it needs, what it mutates, and what evidence it produces. Those are properties of that test, not global restrictions on the diagnostic surface.

Unknown devices must not inherit assumptions from already-proven hardware. Their identifiers, USB personalities, transport behavior, failures, and successful transitions should be captured as evidence until a device-specific path is established.

## Main app promotion criteria

A function may be exposed as a normal app feature only after:

1. Its supported device scope is explicit.
2. Required USB, firmware, signing, and host prerequisites are testable.
3. Hardware success is repeatable.
4. Failure behavior is understood and logged.
5. Device mutations and user-visible consequences are understood.
6. The intended production behavior is defined and reviewed.
7. The corresponding diagnostic evidence remains available for regressions.

Unverified or presentation-only controls must not appear interactive in the main app.

## Boot Diagnostics test classes

Boot Diagnostics may contain any kind of development test needed to advance support. Current tests happen to fall into the following groups, but these are not product-level restrictions.

### Functional observation tests

The current functional matrix is read-only. It inspects USB descriptors, Apple boot personalities, structured boot identifiers, Recovery getenv values, console input, and transition history. Its read-only nature describes this one test matrix, not Boot Diagnostics as a whole.

### Active hardware development tests

Active tests may send payloads, issue boot commands, modify environment state, exercise restore-entry behavior, or perform other device-affecting operations as development requires. Each test should present its actual scope and prerequisites and log what occurred. Existing M1 Stage-2 and cumulative restore-entry tests retain their currently implemented behavior until intentionally extended by new diagnostic development.

### Future devices and restore development

New device-specific and restore-development tests stay in Boot Diagnostics until they satisfy the promotion criteria above. OBSERVED and NOT_APPLICABLE remain useful classifications when current knowledge does not yet apply to a device.
