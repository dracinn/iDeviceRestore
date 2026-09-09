# Four full-app Android design iterations

Source of truth: `idevicerestore_android_final_mockups_fixed.zip` supplied in the iDeviceRestore project conversation.

These preview builds do not display the supplied screen PNGs as application UIs. They use the ZIP's Android vector assets and its design tokens as real Android resources while preserving the existing MainActivity and functional classes.

Iterations:
- Reference: closest to the supplied token set and original Android concept.
- Compact: tighter radii, reduced type and control heights for diagnostic-heavy use.
- Comfort: larger cards, controls and typography for touch-first use.
- Minimal: flatter cards and reduced decoration while keeping the same information architecture.

All four variants contain the same complete app features and intentionally use the production package ID `com.idevicerestore.android` so package-dependent startup and USB-routing behavior matches the normal app. Install and compare the preview variants one at a time rather than side by side.
