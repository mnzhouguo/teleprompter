#!/usr/bin/env bash
set -e

GRADLE="/c/Users/mnzho/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta/gradle-8.2/bin/gradle"
ADB="/c/Users/mnzho/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.example.teleprompter"
ACTIVITY=".MainActivity"

cd "$(dirname "$0")"

echo "==> 检查设备..."
DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "$DEVICE" ]; then
    echo "错误: 未检测到已连接的 Android 设备，请检查 USB 连接并启用开发者调试。"
    exit 1
fi
echo "    设备: $DEVICE"

echo "==> 构建 Debug APK..."
"$GRADLE" assembleDebug --quiet

echo "==> 安装到设备..."
"$ADB" -s "$DEVICE" install -r "$APK"

echo "==> 授予悬浮窗权限（绕过 MIUI 的 ignore 状态）..."
"$ADB" -s "$DEVICE" shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow

echo "==> 启动 App..."
"$ADB" -s "$DEVICE" shell am start -n "${PACKAGE}/${PACKAGE}${ACTIVITY}"

echo "==> 完成！"
