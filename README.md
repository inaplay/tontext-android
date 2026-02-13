# Tontext

A privacy-focused Android voice keyboard. Press and hold the mic button, speak, release — your speech is transcribed locally on-device using [whisper.cpp](https://github.com/ggerganov/whisper.cpp). No internet required for transcription, no data leaves your phone.

Supports ~99 languages. The AI model (~57 MB) is downloaded once on first launch to keep the APK small (<10 MB).

## System Components

This is a mono-repo with four components:

| Component | Path | Tech | Purpose |
|-----------|------|------|---------|
| **Android App** | `android/` | Kotlin, C++ (JNI), whisper.cpp | The keyboard IME itself |
| **Backend** | `backend/` | Python, FastAPI, SQLite | Serves APK downloads, tracks stats, admin dashboard |
| **Website** | `web/` | Vanilla HTML/CSS | Landing page with download button |
| **Infrastructure** | `deploy/`, `infra/` | Docker, Nginx, GitHub Actions | Deployment configs and CI/CD |

### Android App (`android/`)

Two Gradle modules:

- **`:app`** — The IME service, setup activity, keyboard UI with waveform visualizer, audio recorder
- **`:whisper`** — JNI wrapper around whisper.cpp (git submodule), builds ARM-optimized native libraries (NEON, FP16)

Key classes:
- `TontextIMEService` — Core keyboard service
- `SetupActivity` — Onboarding flow (enable IME, permissions, model download)
- `KeyboardView` — Mic button, backspace, keyboard switch
- `WaveformView` — Real-time audio amplitude visualization
- `AudioRecorder` — 16kHz mono PCM recording
- `WhisperTranscriber` / `WhisperContext` — Bridge to native whisper.cpp

### Backend (`backend/`)

FastAPI application that:
- Serves the latest APK via `/api/download/latest`
- Serves Whisper model files via `/api/model/{filename}`
- Tracks download statistics (privacy-respecting — only stores IP hashes)
- Provides an admin dashboard at `/admin` (Basic Auth)

### Website (`web/`)

Static single-page download site. Dark theme, fetches current version from the backend API.

### Infrastructure

- **`deploy/docker-compose.yml`** — Runs the backend in Docker, mounts volumes for SQLite DB and APK releases
- **`deploy/nginx.conf`** — Reverse proxy with SSL (Let's Encrypt), serves the static site and proxies API requests
- **`infra/setup-runner.sh`** — Provisions a self-hosted GitHub Actions runner with Android SDK/NDK for building APKs
- **`.github/workflows/build-and-release.yml`** — CI pipeline: builds release APK on push to `main`, creates a GitHub Release

## Development Setup

### Prerequisites

- **macOS or Linux** (macOS recommended for development)
- **Android Studio** or at minimum:
  - JDK 17
  - Android SDK (API 34)
  - Android NDK 27.1.12297006
  - CMake (for native build)
- **Python 3.12+** (for backend)
- **Docker** (for deployment)

### Getting Started

```bash
# Clone with submodules (whisper.cpp)
git clone --recursive https://github.com/inaplay/tontext-android.git
cd tontext-android
```

If you already cloned without `--recursive`:
```bash
git submodule update --init --recursive
```

### Building the Android App

Open `android/` in Android Studio, or build from the command line:

```bash
cd android

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

The APK will be at `android/app/build/outputs/apk/release/app-release.apk`.

To test on a device, install the APK and follow the setup flow:
1. Enable Tontext as an input method in system settings
2. Select Tontext as the active keyboard
3. Grant microphone permission
4. Download the Whisper model (happens automatically on first launch)

### Running the Backend Locally

```bash
cd backend
pip install -r requirements.txt

# Set admin credentials
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD=secret

uvicorn app:app --reload --port 8000
```

The admin dashboard is then available at `http://localhost:8000/admin`.

### Running the Website Locally

The website is static HTML — open `web/index.html` in a browser, or serve it:

```bash
python -m http.server 8080 -d web
```

Note: The version badge requires the backend to be running.

## Deployment

### CI/CD Pipeline

On every push to `main`, GitHub Actions:
1. Builds a release APK on the self-hosted runner
2. Creates a GitHub Release tagged `v{version}-build.{number}`
3. Attaches the APK as a release asset

### Server Deployment

The backend and website are deployed to a Netcup VPS via Docker Compose:

```bash
cd deploy

# Set environment variables
export ADMIN_USERNAME=...
export ADMIN_PASSWORD=...

docker compose up -d
```

Nginx serves the static website and proxies `/api/*` and `/admin` to the backend container.

## Project Documentation

- **`PLAN.md`** — Full product specification and implementation phases
- **`UI_STATES.md`** — UI state diagrams and wireframes
