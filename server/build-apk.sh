#!/usr/bin/env bash
# בונה את אפליקציית הטלפון בשרת הביתי, כשיש ב-GitHub גרסה חדשה של האפליקציה.
# ערכת הפיתוח של Samsung Health נשמרת רק כאן, בתיקייה sdk, ולא עולה לאינטרנט.
# הפעלה ידנית: bash ~/apps/fitness-build/build-apk.sh [--force]
set -euo pipefail
B=/home/eli/apps/fitness-build
OUT=/home/eli/apps/fitness/data/apk
exec 9>"$B/.lock"; flock -n 9 || { echo "build already running"; exit 0; }
mkdir -p "$OUT" "$B/cache"
cd "$B"
if [ -d src/.git ]; then git -C src fetch -q origin main && git -C src reset -q --hard origin/main
else git clone -q https://github.com/perezeli1-gif/fitness-android.git src; fi
cp src/server/build-apk.sh "$B/build-apk.sh" 2>/dev/null || true
V=$(grep -oP 'versionName = "\K[^"]+' src/app/build.gradle.kts)
if [ "${1:-}" != "--force" ] && [ -f "$OUT/version.txt" ] && [ "$(cat "$OUT/version.txt")" = "$V" ]; then exit 0; fi
ls sdk/*.aar >/dev/null 2>&1 || { echo "missing Samsung SDK in $B/sdk"; exit 1; }
if ! sudo docker image inspect fitness-build:1 >/dev/null 2>&1; then
  sudo docker build -q -t fitness-build:1 "$B" >/dev/null
fi
echo "$(date '+%F %T') building app $V"
mkdir -p src/app/libs && cp sdk/*.aar src/app/libs/
sudo docker run --rm --user "$(id -u eli):$(id -g eli)" -e HOME=/tmp -e GRADLE_USER_HOME=/cache \
  -v "$B/src:/src" -v "$B/cache:/cache" fitness-build:1 gradle :app:assembleRelease --no-daemon -q --console=plain
APK=src/app/build/outputs/apk/release/app-release.apk
[ -s "$APK" ] || { echo "build failed"; exit 1; }
cp "$APK" "$OUT/fitness-sync.apk.new" && mv -f "$OUT/fitness-sync.apk.new" "$OUT/fitness-sync.apk"
echo "$V" > "$OUT/version.txt"
echo "$(date '+%F %T') app $V ready"
