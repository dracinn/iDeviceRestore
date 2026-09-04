# Android UI review

The main screen should communicate the restore workflow as a sequence of states rather than expose every control and raw diagnostic stream with equal visual weight.

## Current findings

- Device state, restore preparation, firmware selection/download, settings, and two verbose logs currently share one undifferentiated vertical flow.
- Raw logs dominate the screen even when the user only needs device and firmware status.
- Primary and secondary actions use nearly identical button weight.
- The restore-preparation state exists but is visually similar to ordinary informational text.
- Diagnostics are important for development, but they should be presented as a dedicated section rather than as the continuation of the restore workflow.

## First-stage changes

- Group the screen into Connected device, Restore preparation, Firmware, and Diagnostics cards.
- Keep existing view IDs and staged hidden restore controls so MainActivity behavior is unchanged.
- Make firmware selection/download the primary action group and cancellation a lower-emphasis action.
- Rename the share action to “Share diagnostic report” to match the structured logging direction.
- Give raw activity/probe logs their own diagnostic surfaces.

## Follow-up review items

- Add collapsible diagnostics so logs are hidden by default during ordinary use.
- Add structured log category filters once AppLogger is integrated.
- Replace free-form restore text with a small typed workflow/state model that drives a stage indicator.
- Add download ETA, transfer speed, verification state, and resume information without requiring log inspection.
- Move rarely used settings and development diagnostics away from the main restore path where practical.
- Review accessibility: content descriptions, minimum touch targets, dynamic text scaling, contrast, and screen-reader state announcements.
