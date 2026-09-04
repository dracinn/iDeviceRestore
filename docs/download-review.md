# Firmware download review

The Android firmware download path is intentionally single-stream in production to avoid segmented assembly using roughly twice the temporary storage for very large IPSWs.

## Reliability findings

- Sequential retries must resume from the current `.part` length, not the offset that existed when the download first started. Reusing the original offset after a mid-stream failure can append duplicate bytes.
- HTTP 206 responses used for resume must begin at the requested byte offset. A mismatched `Content-Range` must be rejected rather than appended.
- A `.part` file that already equals the expected payload size should proceed directly to verification instead of issuing an invalid range beyond EOF.
- Progress speed for resumed downloads should measure bytes transferred in the current session, not divide all previously downloaded bytes by the current session duration.
- Existing segmented parts must be size-validated before they contribute to progress accounting.
- Range-probe cleanup should not mask the original HTTP failure.

## Follow-up review items

- Persist a small download-state record so UI state survives activity recreation and process death more cleanly.
- Collapse foreground-service states into a typed state model shared with the activity.
- Add smoothed transfer rate and ETA reporting.
- Avoid an expensive full-file SHA-1 pass when no expected digest exists; a calculated digest without a reference value is informational, not verification.
- Add notification tap-through to the active firmware view.
- Add explicit completion verification state so transfer completion and integrity verification are visually distinct.
