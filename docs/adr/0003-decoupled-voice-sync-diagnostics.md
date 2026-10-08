# Decoupled Voice Sync Diagnostics

Voice sync issues need evidence from real recording sessions, but logging must not affect ASR callbacks, scrolling, or matching decisions. We decided to record voice-sync diagnostics as asynchronous session traces saved on the device, while keeping the matcher responsible only for exposing diagnostic snapshots and not for file I/O.

## Consequences

The logs can be pulled from the device and analyzed later without coupling the matching algorithm to storage. Logged ASR text is useful for diagnosis but may contain spoken content, so the trace is treated as diagnostic data rather than a user-facing feature.
