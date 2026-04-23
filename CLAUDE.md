# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

智能提词器 (Smart Teleprompter) - An Android app for managing and recording teleprompter scripts with voice-synced scrolling. The app has two main flows:

1. **Script Management Flow**: MainActivity → ScriptEditActivity (create/edit) → ScriptViewActivity (view)
2. **Recording Flow**: MainActivity → VideoRecordActivity (in-app recording with teleprompter overlay)

The teleprompter uses real-time ASR (Automatic Speech Recognition) to sync scrolling with the user's voice.

## Build Commands

```bash
# Gradle wrapper is missing; use the cached binary directly
GRADLE="/c/Users/mnzho/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta/gradle-8.2/bin/gradle"

# Build debug APK
"$GRADLE" assembleDebug

# Build + install + launch (preferred)
bash install.sh

# APK location
app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

### Script Management (New Flow)

| Component | Role |
|-----------|------|
| `MainActivity` | Script list with cards; single-click selects card (shows edit/delete/record actions), double-click opens view page |
| `Script` | Data model with title, content, charCount, estimatedMinutes, preview, createdTimeText |
| `ScriptListAdapter` | RecyclerView adapter; handles selection state and double-click detection (300ms interval) |
| `ScriptEditActivity` | Create/edit scripts; real-time char count and duration estimate (200 chars/min) |
| `ScriptViewActivity` | Pure view mode; maximized content display with minimal header |

### Recording Pipeline

**AudioCapture → DoubaoAsrClient → VoiceSyncEngine → ScrollController**

| Component | Role |
|-----------|------|
| `VideoRecordActivity` | CameraX video recording with embedded teleprompter overlay; zoom controls (0.8x-2.0x) |
| `FloatingService` | Foreground service for system-level overlay mode (future use) |
| `AudioCapture` | `AudioRecord` 16kHz mono PCM; Bluetooth SCO auto-detection |
| `DoubaoAsrClient` | WebSocket client for ByteDance ASR; binary protocol `[4B header][gzip payload]` |
| `VoiceSyncEngine` | Pinyin-based text alignment with Chinese homophone tolerance |
| `ScrollController` | Spring animation for smooth scrolling |

### Key Technical Details

- **Foreground Service**: `foregroundServiceType="microphone"` required for Android 12+ background mic
- **Overlay**: `TYPE_APPLICATION_OVERLAY` (API 26+)
- **ASR Protocol**: Header byte 1 = message type (0x01=request, 0x02=audio, 0x09=response)
- **Zoom**: CameraX supports 0.8x-2.0x digital zoom via `camera.cameraControl.setLinearZoom()`

## Dependencies

- OkHttp 4.12.0 - WebSocket ASR client
- Kotlinx Coroutines 1.7.3 - Async handling
- DynamicAnimation 1.0.0 - Spring scrolling
- Pinyin4j 2.5.1 - Chinese pinyin for text alignment
- CameraX 1.3.1 - Video recording

## UI Design Files

`design/` directory contains HTML mockups:
- `home-ui-design.html` - Script list page design
- `edit-ui-design.html` - Edit page design

These can be opened in browser for visual reference during UI changes.

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