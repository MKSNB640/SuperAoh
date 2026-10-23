#!/bin/bash
# SuperAoh 服务器 Android SDK 安装脚本
set -e

SDK=/opt/android-sdk
mkdir -p "$SDK/cmdline-tools"
cd "$SDK/cmdline-tools"

if [ ! -d latest ]; then
  echo "== 下载 commandline-tools =="
  wget -q -O t.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o t.zip
  mv cmdline-tools latest
  rm -f t.zip
fi

chmod +x latest/bin/* 2>/dev/null || true

export ANDROID_HOME="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$PATH"

echo "== 接受许可 =="
yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true

echo "== 安装组件 =="
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 | tail -6

echo "== 结果 =="
ls "$SDK/build-tools/" 2>/dev/null || true
ls "$SDK/platforms/" 2>/dev/null || true
echo SETUP_DONE