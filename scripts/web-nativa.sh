#!/usr/bin/env bash
# Copia la app web a www/, que Capacitor empaqueta dentro del APK.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf www && mkdir -p www
cp -r index.html app.css manifest.webmanifest js icons img www/
rm -rf www/icons/fuente
