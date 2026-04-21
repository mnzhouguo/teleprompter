# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

智能提词器 (Smart Teleprompter) - An Android app that displays a floating teleprompter overlay synced to the user's voice via real-time ASR (Automatic Speech Recognition). Two modes:
1. **Floating mode**: System-level overlay floats over any app (camera, video apps)
2. **Video record mode**: In-app camera preview with embedded teleprompter for recording videos directly

## Build Commands

```bash
# Gradle wrapper is missing; use the cached binary directly
GRADLE="/c/Users/mnzho/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta/gradle-8.2/bin/gradle"
ADB="/c/Users/mnzho/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# Build debug APK only
"$GRADLE" assembleDebug

# Build + install + launch on connected device (preferred)
bash install.sh

# APK output location
app/build/outputs/apk/debug/app-debug.apk
```

## Deploy to Device

After every code change, run `bash install.sh` to build, install, and launch on the connected Android device.
The script: builds the APK → installs via ADB → grants SYSTEM_ALERT_WINDOW → launches MainActivity.

Device tested: `19be32d` (connected via USB, ADB debugging enabled).

## Architecture

The app follows a pipeline architecture: **AudioCapture → DoubaoAsrClient → VoiceSyncEngine → ScrollController**

### Core Components

| Component | Role |
|-----------|------|
| `MainActivity` | Entry point; handles permissions (RECORD_AUDIO, SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS); launches FloatingService or VideoRecordActivity |
| `FloatingService` | Foreground service (`foregroundServiceType="microphone"`); displays system-level floating window; orchestrates the full pipeline |
| `VideoRecordActivity` | CameraX-based video recording with embedded teleprompter overlay |
| `AudioCapture` | `AudioRecord`-based audio capture (16kHz mono PCM); auto-detects Bluetooth SCO headset with fallback to phone mic; delivers 100ms chunks |
| `DoubaoAsrClient` | WebSocket client for ByteDance/Doubao ASR; uses custom binary protocol (4-byte header + gzip payload) |
| `VoiceSyncEngine` | Pinyin-based text alignment; matches ASR output to script position with Chinese homophone tolerance |
| `ScrollController` | Spring animation (`DynamicAnimation`) for smooth scrolling; keeps current line at 1/3 viewport height |

### Key Technical Details

- **Foreground Service Required**: Android 12+ blocks background mic access; `FloatingService` must be foreground with `foregroundServiceType="microphone"` declared in manifest
- **Overlay Type**: Uses `TYPE_APPLICATION_OVERLAY` (API 26+ only)
- **ASR Protocol**: ByteDance binary frame format - `[4B header][4B payload size][gzip payload]`; header byte 1 encodes message type (0x01=full request, 0x02=audio, 0x09=response, 0x0F=error)
- **Bluetooth SCO**: Connects via `AudioManager.startBluetoothSco()` with 4-second timeout; uses `VOICE_COMMUNICATION` audio source when connected

## Dependencies

- OkHttp 4.12.0 - WebSocket ASR client
- Kotlinx Coroutines 1.7.3 - Async audio/network handling
- DynamicAnimation 1.0.0 - Spring-based smooth scrolling
- Pinyin4j 2.5.1 - Chinese pinyin conversion for text alignment
- CameraX 1.3.1 - Video recording with camera preview

## ASR Configuration

The app uses ByteDance/Doubao cloud ASR. Credentials are stored in `MainActivity`:
- `doubaoAppId` - Application ID from ByteDance console
- `doubaoAccessToken` - Access token

For production, these should be externalized (e.g., secured config or injected at build time).

## Behavioral guidelines
Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

Tradeoff: These guidelines bias toward caution over speed. For trivial tasks, use judgment.

1. Think Before Coding
Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:

State your assumptions explicitly. If uncertain, ask.
If multiple interpretations exist, present them - don't pick silently.
If a simpler approach exists, say so. Push back when warranted.
If something is unclear, stop. Name what's confusing. Ask.
2. Simplicity First
Minimum code that solves the problem. Nothing speculative.

No features beyond what was asked.
No abstractions for single-use code.
No "flexibility" or "configurability" that wasn't requested.
No error handling for impossible scenarios.
If you write 200 lines and it could be 50, rewrite it.
Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

3. Surgical Changes
Touch only what you must. Clean up only your own mess.

When editing existing code:

Don't "improve" adjacent code, comments, or formatting.
Don't refactor things that aren't broken.
Match existing style, even if you'd do it differently.
If you notice unrelated dead code, mention it - don't delete it.
When your changes create orphans:

Remove imports/variables/functions that YOUR changes made unused.
Don't remove pre-existing dead code unless asked.
The test: Every changed line should trace directly to the user's request.

4. Goal-Driven Execution
Define success criteria. Loop until verified.

Transform tasks into verifiable goals:

"Add validation" → "Write tests for invalid inputs, then make them pass"
"Fix the bug" → "Write a test that reproduces it, then make it pass"
"Refactor X" → "Ensure tests pass before and after"
For multi-step tasks, state a brief plan:

1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

These guidelines are working if: fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.