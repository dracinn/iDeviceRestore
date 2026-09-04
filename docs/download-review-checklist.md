# Download review checklist

- [x] Recompute resume offset on every retry.
- [x] Reject mismatched HTTP Content-Range responses.
- [x] Recognize complete `.part` files before another network request.
- [x] Base speed on current-session bytes during resume.
- [x] Validate segmented parts before progress accounting.
- [x] Prevent range-probe cleanup from masking the original failure.
- [ ] Persist typed download state across activity/process recreation.
- [ ] Add smoothed speed and ETA.
- [ ] Skip full-file SHA-1 when no expected digest exists.
- [ ] Add notification tap-through to the active firmware screen.
- [ ] Separate transfer-complete and integrity-verification UI states.
