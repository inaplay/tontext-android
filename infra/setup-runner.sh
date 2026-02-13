#!/bin/bash
set -euo pipefail

# ============================================================
# Self-hosted GitHub Actions Runner Setup for Android Builds
# Registers at the ORGANIZATION level so all repos can use it.
#
# Usage:
#   1. Get a runner token from:
#      https://github.com/organizations/inaplay/settings/actions/runners/new
#   2. Run: sudo bash setup-runner.sh <RUNNER_TOKEN>
# ============================================================

RUNNER_TOKEN="${1:-}"
GITHUB_ORG="inaplay"
RUNNER_USER="github-runner"
RUNNER_DIR="/opt/github-runner"
ANDROID_HOME="/opt/android-sdk"
NDK_VERSION="27.1.12297006"

if [ -z "$RUNNER_TOKEN" ]; then
  echo "Usage: sudo bash setup-runner.sh <RUNNER_TOKEN>"
  echo ""
  echo "Get your token from:"
  echo "  https://github.com/organizations/$GITHUB_ORG/settings/actions/runners/new"
  exit 1
fi

echo "==> Creating runner user"
if ! id "$RUNNER_USER" &>/dev/null; then
  useradd -m -s /bin/bash "$RUNNER_USER"
fi

echo "==> Installing system dependencies"
apt-get update
apt-get install -y \
  curl unzip git wget \
  openjdk-17-jdk-headless \
  build-essential cmake \
  libicu-dev

echo "==> Installing Android SDK"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
  CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdline-tools.zip
  unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-tmp
  mv /tmp/cmdline-tools-tmp/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-tmp
fi

export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> Accepting Android SDK licenses"
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "==> Installing Android SDK components"
sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "ndk;$NDK_VERSION"

echo "==> Setting up Android SDK permissions"
chown -R "$RUNNER_USER:$RUNNER_USER" "$ANDROID_HOME"

echo "==> Installing GitHub Actions Runner"
mkdir -p "$RUNNER_DIR"
cd "$RUNNER_DIR"

RUNNER_VERSION=$(curl -s https://api.github.com/repos/actions/runner/releases/latest | grep '"tag_name"' | sed 's/.*"v\(.*\)".*/\1/')
RUNNER_ARCH="x64"
RUNNER_FILE="actions-runner-linux-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz"

if [ ! -f "$RUNNER_DIR/.runner" ]; then
  curl -sL "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${RUNNER_FILE}" -o "/tmp/${RUNNER_FILE}"
  tar xzf "/tmp/${RUNNER_FILE}" -C "$RUNNER_DIR"
  rm "/tmp/${RUNNER_FILE}"
fi

chown -R "$RUNNER_USER:$RUNNER_USER" "$RUNNER_DIR"

echo "==> Configuring runner (organization-level for all $GITHUB_ORG repos)"
sudo -u "$RUNNER_USER" bash -c "
  cd $RUNNER_DIR
  ./config.sh \
    --url https://github.com/$GITHUB_ORG \
    --token $RUNNER_TOKEN \
    --name netcup-android-builder \
    --labels self-hosted,linux,android \
    --work _work \
    --unattended \
    --replace
"

echo "==> Creating systemd service"
cat > /etc/systemd/system/github-runner.service << 'UNIT'
[Unit]
Description=GitHub Actions Runner
After=network.target

[Service]
Type=simple
User=github-runner
WorkingDirectory=/opt/github-runner
Environment="ANDROID_HOME=/opt/android-sdk"
Environment="ANDROID_SDK_ROOT=/opt/android-sdk"
Environment="JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64"
Environment="PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/lib/jvm/java-17-openjdk-amd64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
ExecStart=/opt/github-runner/run.sh
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable github-runner
systemctl start github-runner

echo ""
echo "============================================"
echo "  Runner setup complete!"
echo "============================================"
echo ""
echo "  Runner name:  netcup-android-builder"
echo "  Scope:        All repos in $GITHUB_ORG"
echo "  Labels:       self-hosted, linux, android"
echo "  Android SDK:  $ANDROID_HOME"
echo "  NDK:          $NDK_VERSION"
echo ""
echo "  Check status: systemctl status github-runner"
echo "  View logs:    journalctl -u github-runner -f"
echo ""
