# Hardware test convention

All cumulative Apple-silicon restore-chain hardware tests must use a single user-facing entry point: **Run Current Stage-2 Test**.

The cumulative test is responsible for automatically executing every previously proven prerequisite from DFU through the current approved boundary. New milestones are folded into this same automated path rather than exposed as additional sequential test buttons.

`Boot Stage 2 Recovery` is diagnostics-only. It must not be documented or presented as a prerequisite for the cumulative test.

The cumulative path must preserve the active safety boundary: do not advance into restored/usbmux traffic, restore payload delivery, erase, or later restore operations unless that boundary is deliberately advanced in a future change.
