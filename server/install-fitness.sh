#!/usr/bin/env bash
# התקנה או עדכון של יומן הכושר בשרת הביתי. הנתונים נשמרים ב-~/apps/fitness/data ולא נדרסים.
set -euo pipefail
trap 'echo "FAILED (line $LINENO): $BASH_COMMAND" >&2' ERR
base="$HOME/apps/fitness"
zip="$HOME/fitness-update.zip"
mkdir -p "$base/releases" "$base/data"
rel="$base/releases/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$rel"
python3 - "$zip" "$rel" <<'PY'
import sys, zipfile, pathlib
z = zipfile.ZipFile(sys.argv[1]); out = pathlib.Path(sys.argv[2])
names = z.namelist()
if 'FitnessWeb/web_app.py' not in names:
    raise SystemExit('The package is not a fitness server package')
keep = []
for n in names:
    p = pathlib.PurePosixPath(n)
    if p.is_absolute() or '..' in p.parts or '\\' in n:
        raise SystemExit('Unsafe path in package')
    if len(p.parts) > 1 and p.parts[1] == 'data':
        continue  # never ship data inside an update
    keep.append(n)
z.extractall(out, keep)
print('Package OK')
PY
mkdir -p "$rel/FitnessWeb/data"
cat > "$base/Dockerfile" <<'DOCKER'
FROM python:3.12-alpine
RUN pip install --no-cache-dir flask==3.0.3 waitress==3.0.0
WORKDIR /app
CMD ["waitress-serve","--host=0.0.0.0","--port=5050","--threads=8","web_app:app"]
DOCKER
# One time: let this user run docker and crontab without typing the password again.
if [ "$(id -u)" != 0 ] && ! sudo -n "$(command -v docker)" version >/dev/null 2>&1; then
  echo 'One-time permission (server password):'
  cmds="$(command -v docker)"
  if command -v crontab >/dev/null 2>&1; then cmds="$cmds, $(command -v crontab)"; fi
  echo "$(id -un) ALL=(root) NOPASSWD: $cmds" | sudo tee /etc/sudoers.d/fitness-home >/dev/null
  sudo chmod 440 /etc/sudoers.d/fitness-home
  if ! sudo visudo -cf /etc/sudoers.d/fitness-home >/dev/null; then sudo rm -f /etc/sudoers.d/fitness-home; echo 'Permission rule skipped.'; fi
fi
if ! sudo docker image inspect fitness-app:1 >/dev/null 2>&1; then
  echo 'Building the app image (first time only, about a minute)...'
  sudo docker build -q -t fitness-app:1 "$base" >/dev/null
fi
old=$(readlink -f "$base/current" 2>/dev/null || true)
start() {
  sudo docker rm -f fitness-app >/dev/null 2>&1 || true
  sudo docker run -d --name fitness-app --restart=unless-stopped \
    -p 127.0.0.1:5050:5050 -p 192.168.50.83:5050:5050 \
    -v "$1:/app:ro" -v "$base/data:/app/data" fitness-app:1 >/dev/null
}
start "$rel/FitnessWeb"
ok=false
for i in $(seq 1 20); do
  if curl -fsS --max-time 3 http://127.0.0.1:5050/api/version >/dev/null 2>&1; then ok=true; break; fi
  sleep 1
done
if [ "$ok" != true ]; then
  echo 'The new version did not start.' >&2
  if [ -n "$old" ] && [ -d "$old" ]; then start "$old"; echo 'Previous version restored.' >&2; fi
  exit 1
fi
ln -sfn "$rel/FitnessWeb" "$base/current"
# keep the last 5 releases
ls -1dt "$base"/releases/* | tail -n +6 | xargs -r rm -rf 2>/dev/null || true
rm -f "$zip"

# ---- automatic updates: the server checks GitHub every hour and installs new versions by itself ----
self="$(readlink -f "$0")"
[ "$self" != "$base/install-fitness.sh" ] && cp "$self" "$base/install-fitness.sh" 2>/dev/null || true
cat > "$base/auto-update.sh" <<'AUTO'
#!/usr/bin/env bash
# Runs every hour (root cron). Installs a newer fitness version from GitHub, if there is one.
set -uo pipefail
base=/home/eli/apps/fitness
RAW=https://raw.githubusercontent.com/perezeli1-gif/fitness-android/main/server
remote=$(curl -fsS --max-time 20 "$RAW/version.txt" | tr -d '\r\n ')
[ -n "$remote" ] || exit 0
current=$(curl -fsS --max-time 5 http://127.0.0.1:5050/api/version | python3 -c 'import json,sys;print(json.load(sys.stdin)["version"])' 2>/dev/null || echo 0)
newer=$(python3 -c 'import sys;f=lambda v:[int(x) for x in v.split(".")];print(1 if f(sys.argv[1])>f(sys.argv[2]) else 0)' "$remote" "$current" 2>/dev/null || echo 0)
date +%s > "$base/auto-update.last"
# Phone app: built on this server when GitHub has a newer app version
if [ -f /home/eli/apps/fitness-build/build-apk.sh ]; then
  sudo -u eli bash /home/eli/apps/fitness-build/build-apk.sh >> /home/eli/apps/fitness-build/build.log 2>&1 || echo "$(date '+%F %T') app build failed" >> /home/eli/apps/fitness-build/build.log
fi
[ "$newer" = 1 ] || exit 0
echo "$(date '+%F %T') update $current -> $remote"
curl -fsSL --max-time 180 -o /home/eli/fitness-update.zip "$RAW/FitnessWeb.zip" || exit 1
HOME=/home/eli bash "$base/install-fitness.sh"
AUTO
chmod +x "$base/auto-update.sh"
if command -v crontab >/dev/null 2>&1; then
  if ! (sudo crontab -l 2>/dev/null || true) | grep -q fitness-auto; then
    { sudo crontab -l 2>/dev/null || true; echo "17 * * * * bash /home/eli/apps/fitness/auto-update.sh >> /home/eli/apps/fitness/auto-update.log 2>&1 # fitness-auto"; } | sudo crontab - && echo 'Automatic updates: ON (every hour)' || echo 'Could not turn on automatic updates.'
  fi
else
  echo 'cron is not installed, automatic updates are off. Fix: sudo apt install -y cron'
fi
echo "SUCCESS $(curl -fsS http://127.0.0.1:5050/api/version)"
