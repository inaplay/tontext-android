# TonText - Voice-to-Text Android Keyboard

## Overview

TonText is an Android Input Method Editor (IME) that replaces the traditional keyboard with a single microphone button. Press-and-hold records audio, release triggers local on-device transcription via whisper.cpp, and the transcribed text is committed to whatever text field is focused. The project is a mono-repo containing the Android app, a download website, a Python backend for download tracking, and CI/CD configuration.

---

## Mono-Repo Structure

```
tontext-android/
├── android/                    # Android app (Gradle project root)
│   ├── app/                    # Main application module
│   │   ├── src/main/
│   │   │   ├── java/com/tontext/app/
│   │   │   │   ├── TonTextIMEService.kt        # Core IME service
│   │   │   │   ├── SetupActivity.kt           # Onboarding + permissions
│   │   │   │   ├── PermissionRequestActivity.kt # Transparent permission helper
│   │   │   │   ├── ui/
│   │   │   │   │   ├── WaveformView.kt        # Canvas-based audio visualizer
│   │   │   │   │   └── KeyboardView.kt        # Mic button + status UI
│   │   │   │   ├── audio/
│   │   │   │   │   └── AudioRecorder.kt       # AudioRecord wrapper (16kHz mono)
│   │   │   │   └── whisper/
│   │   │   │       └── WhisperTranscriber.kt  # Wrapper around whisper lib
│   │   │   ├── res/
│   │   │   │   ├── layout/keyboard_view.xml
│   │   │   │   ├── layout/activity_setup.xml
│   │   │   │   ├── xml/method.xml              # IME descriptor
│   │   │   │   ├── drawable/                   # Icons (mic, stop, loader)
│   │   │   │   └── values/
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   ├── whisper/                # whisper.cpp JNI library module
│   │   ├── src/main/
│   │   │   ├── java/com/whispercpp/whisper/
│   │   │   │   ├── WhisperContext.kt
│   │   │   │   └── WhisperCpuConfig.kt
│   │   │   └── jni/whisper/
│   │   │       ├── jni.c
│   │   │       └── CMakeLists.txt
│   │   └── build.gradle.kts
│   ├── build.gradle.kts        # Root build file
│   ├── settings.gradle.kts
│   └── gradle/
│       └── wrapper/
├── web/                        # Static download website
│   ├── index.html              # Single-page download site
│   ├── style.css
│   └── favicon.ico
├── backend/                    # Python backend
│   ├── app.py                  # FastAPI application
│   ├── requirements.txt
│   ├── Dockerfile
│   └── templates/
│       └── admin.html          # Admin stats dashboard
├── deploy/                     # Deployment configuration
│   ├── docker-compose.yml      # Backend + web serving
│   └── nginx.conf              # Reverse proxy config for tontext subdomain
├── .github/
│   └── workflows/
│       └── build-and-deploy.yml  # CI/CD pipeline
├── .gitignore
└── PLAN.md
```

---

## Phase 1: Android App - Core IME

### 1.1 Project Scaffolding
- Initialize Gradle project under `android/` with Kotlin DSL
- Configure `settings.gradle.kts` with two modules: `:app` and `:whisper`
- Set minSdk 26, targetSdk 34, compileSdk 34
- Add `.gitignore` for Gradle, Android build artifacts

### 1.2 Whisper Module (`:whisper`)
- Vendor whisper.cpp as a git submodule under `android/whisper/src/main/jni/whisper/`
- Adapt the official `examples/whisper.android/lib` module:
  - `WhisperContext.kt` - Kotlin API: `createContextFromAsset()`, `transcribeData()`, `release()`
  - `WhisperCpuConfig.kt` - ARM big.LITTLE core detection for optimal threading
  - `jni.c` - JNI bridge: `initContext`, `fullTranscribe`, `getTextSegment`, `freeContext`
  - `CMakeLists.txt` - Build with NEON/FP16 optimizations for ARM
- NDK version: 26.1.10909125 (or latest stable)
- ABI filters: `arm64-v8a`, `armeabi-v7a`
- Ship with `ggml-tiny.bin` model (75 MiB, multilingual, ~99 languages)

**Model delivery:** Download on first launch from Netcup server with progress indicator in SetupActivity. Store in app internal storage. This keeps the APK under 10 MiB. The model file is hosted at `https://tontext.{domain}/api/model/ggml-tiny.bin`.

### 1.3 IME Service (`TonTextIMEService.kt`)
- Extends `InputMethodService`
- `onCreateInputView()` inflates the keyboard layout
- **Three UI states:**
  1. **Idle** - Large microphone button centered, waveform area empty
  2. **Recording** - Mic button visually pressed/active, waveform shows live audio amplitude bars
  3. **Transcribing** - Stop button replaces mic, "Transcribing..." label below, waveform frozen or cleared

**Interaction flow:**
1. User presses and holds mic button → start `AudioRecord` at 16kHz mono PCM
2. While held: feed amplitude data to `WaveformView` for live visualization
3. User releases → stop recording, switch UI to "Transcribing" state with stop button
4. Launch coroutine on `Dispatchers.IO`: convert audio to float array, call `whisperContext.transcribeData()`
5. If user presses stop button during transcription → cancel coroutine, revert to idle state
6. On transcription complete → `currentInputConnection?.commitText(result, 1)`, revert to idle

### 1.4 Keyboard Layout (`keyboard_view.xml`)
- `FrameLayout` container, dark background (#1A1A2E or similar)
- Height: ~200dp (compact, doesn't take too much screen)
- Top area: `WaveformView` custom view (~80dp)
- Center: Large circular `ImageButton` (mic/stop icon, ~80dp diameter)
- Bottom: `TextView` for status text ("Transcribing..." / empty)
- Optional: small globe/keyboard-switch button in corner for switching back to regular keyboard

### 1.5 Waveform Visualizer (`WaveformView.kt`)
- Custom `View` with `onDraw()` override
- Draws vertical bars representing audio amplitude
- Rolling buffer of ~100 most recent amplitude samples
- Single row of bars growing upward from bottom of the waveform area
- Color: accent color (e.g., #E94560)
- `addAmplitude(value: Float)` called from recording thread via handler

### 1.6 Audio Recorder (`AudioRecorder.kt`)
- Wraps `AudioRecord` with 16kHz sample rate, mono, PCM_16BIT
- Runs recording loop on background thread
- Provides amplitude callback for waveform visualization
- Accumulates raw audio data in memory
- On stop: returns `FloatArray` (converted from 16-bit PCM, normalized to [-1, 1])
- Thread-safe start/stop

### 1.7 Setup Activity (`SetupActivity.kt`)
- Launched from app icon
- Guides user through:
  1. Download Whisper model (if not present) - progress bar
  2. Grant microphone permission
  3. Enable TonText IME in system settings (link to `Settings.ACTION_INPUT_METHOD_SETTINGS`)
  4. Select TonText as active keyboard (trigger `InputMethodManager.showInputMethodPicker()`)
- Simple step-by-step UI, each step has a status indicator (done/pending)

### 1.8 Permissions
- `RECORD_AUDIO` (dangerous, runtime)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` (for Android 14+)
- `INTERNET` (for model download on first launch)
- `PermissionRequestActivity` - transparent Activity launched from IME service when permission not yet granted

### 1.9 App Versioning
- Version code and version name managed in `app/build.gradle.kts`
- Format: `versionName = "1.0.0"`, `versionCode = 1`
- Version name displayed in SetupActivity

---

## Phase 2: Download Website

### 2.1 Static Site (`web/`)
- Single `index.html` page
- Content:
  - App name "TonText" with a small icon/logo
  - 1-2 sentence description: "TonText is an Android keyboard that converts your voice to text using on-device AI. No internet required for transcription."
  - Download button that links to `/api/download/latest` (served by backend)
  - Current version number displayed below button
  - Small footer with version info
- Minimal CSS, dark theme to match the app aesthetic
- No JavaScript framework, just vanilla HTML/CSS
- Download button triggers a GET to the backend which serves the APK and increments the counter

### 2.2 Styling
- Dark background, clean typography
- Single centered card with download CTA
- Mobile-responsive (users will likely visit from Android)

---

## Phase 3: Python Backend

### 3.1 Technology
- **FastAPI** (lightweight, async, easy to deploy)
- **SQLite** database (simple, no external DB needed, file-based)
- Single `app.py` file (keep it simple)

### 3.2 Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/download/latest` | Serve latest APK, increment download counter for that version |
| GET | `/api/version` | Return JSON with current version info |
| GET | `/admin` | Admin dashboard (HTML page with stats) |
| GET | `/api/stats` | JSON stats endpoint (used by admin page) |

### 3.3 Database Schema (SQLite)

```sql
CREATE TABLE downloads (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version TEXT NOT NULL,          -- e.g., "1.0.0"
    downloaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ip_hash TEXT,                   -- SHA256 of IP (privacy-respecting)
    user_agent TEXT
);
```

### 3.4 Admin Dashboard (`/admin`)
- Simple server-rendered HTML page (Jinja2 template)
- Protected by basic auth (username/password from environment variables)
- Shows:
  - Total downloads (all versions)
  - Downloads per version (table)
  - Downloads over time (last 30 days, simple bar chart using CSS or a minimal JS chart lib)
  - Today's downloads
- No JavaScript framework needed; keep it minimal

### 3.5 APK Storage
- APKs stored in a `releases/` directory on the server
- Naming convention: `tontext-v{version}.apk`
- Backend reads directory to determine latest version
- A `latest.json` file or symlink points to the current version

---

## Phase 4: CI/CD Pipeline

### 4.1 GitHub Actions Workflow (`.github/workflows/build-and-deploy.yml`)

**Trigger:** Push to `main` branch (or tags matching `v*`)

**Steps:**
1. **Build Android APK**
   - Set up JDK 17, Android SDK, NDK
   - Cache Gradle dependencies
   - Run `./gradlew assembleRelease` in `android/`
   - Sign APK (signing key stored as GitHub secret)
   - Extract version from `build.gradle.kts`
   - Upload APK as build artifact

2. **Deploy to Server**
   - SSH into Netcup server (credentials as GitHub secrets)
   - Upload APK to `releases/` directory with version-stamped name
   - Update `latest.json` or symlink
   - Copy updated web files to serving directory
   - Restart backend service if `backend/` files changed (or rely on auto-reload)

### 4.2 Versioning in CI
- Version extracted from `android/app/build.gradle.kts` (single source of truth)
- APK named `tontext-v{versionName}.apk`
- Backend serves whatever is the highest version in `releases/`
- Website auto-displays current version (fetched from `/api/version`)

---

## Phase 5: Deployment on Netcup Server

### 5.1 Docker Compose (`deploy/docker-compose.yml`)
- **Service: `backend`** - Python FastAPI app, exposes port 8000 internally
- **Service: `web`** - Static file serving (could be same container or nginx)
- Volumes: `./releases:/app/releases`, `./data:/app/data` (SQLite DB)

### 5.2 Nginx Configuration
- Subdomain: `tontext.{your-domain}` (separate from existing project)
- Reverse proxy config for the tontext subdomain:
  - `/` → static web files (index.html, style.css)
  - `/api/` → FastAPI backend (download, version, stats, model hosting)
  - `/admin` → FastAPI admin page
- SSL via Let's Encrypt (certbot) for the subdomain
- Existing project on the Netcup server remains untouched on its own domain/subdomain

### 5.3 Environment Variables
```
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<secure-password>
DATABASE_PATH=/app/data/tontext.db
RELEASES_DIR=/app/releases
```

---

## Phase 6: Admin Interface

- Served at `/admin` by FastAPI
- Basic auth protection
- Server-rendered HTML (Jinja2)
- Stats displayed:
  - Total downloads (all time)
  - Downloads per version (table: version | count | first download | last download)
  - Daily downloads for last 30 days (simple table or CSS bar chart)
  - Unique downloaders estimate (based on IP hash count)
- Refresh button or auto-refresh every 60s

---

## Implementation Order

1. **Android project scaffolding** - Gradle setup, module structure, gitignore
2. **Whisper module** - Integrate whisper.cpp, JNI bindings, model loading
3. **IME Service** - Core keyboard service with mic button, three states
4. **Audio recording** - AudioRecord wrapper with amplitude callback
5. **Waveform visualizer** - Custom view for live audio feedback
6. **Transcription integration** - Connect recorder → whisper → commitText
7. **Setup Activity** - Onboarding flow, model download, permissions
8. **Website** - Static HTML download page
9. **Backend** - FastAPI with download tracking, admin stats
10. **Deployment config** - Docker, nginx, compose
11. **CI/CD** - GitHub Actions for build + deploy
12. **Testing & polish** - End-to-end test on device, UI refinements

---

## Key Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Whisper library | whisper.cpp via JNI | Most mature Android integration, official example available |
| Model | ggml-tiny.bin (75 MiB, multilingual) | Best speed/size for mobile, supports ~99 languages |
| Model delivery | Download on first launch | Keeps APK small (<10 MiB vs ~85 MiB) |
| UI framework | Android Views (XML) | Simpler for IME, no Compose lifecycle complexity |
| Backend | FastAPI + SQLite | Lightweight, single-file, no external DB dependency |
| Web | Static HTML/CSS | No build step, instant load, trivial to maintain |
| Deployment | Docker Compose + Nginx | Consistent with existing Netcup server Docker setup |
| CI/CD | GitHub Actions | Standard, free for public repos |

---

## Resolved Decisions

1. **Language support**: Multilingual (`ggml-tiny.bin`) — supports ~99 languages
2. **Domain/routing**: Subdomain (`tontext.{domain}`)
3. **APK signing**: Generate new keystore during project setup
4. **Server**: Netcup root server, Docker-based deployment (alongside existing Docker project)
5. **Model hosting**: Served from the Netcup server itself
