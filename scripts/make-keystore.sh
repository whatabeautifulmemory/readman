#!/bin/sh
# One-time: create the release signing key. Keep the .jks and passwords OUT of git — losing the key
# means users can never update in place again (Obtainium/Android refuse a signature change).
set -e
cd "$(dirname "$0")/.."
[ -f readman-release.jks ] && { echo "readman-release.jks already exists"; exit 1; }
keytool -genkeypair -v -keystore readman-release.jks -alias readman -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Readman, O=whatabeautifulmemory"
echo
echo "Now: cp keystore.properties.example keystore.properties  and fill in the passwords."
echo "For GitHub Actions secrets:  base64 -i readman-release.jks | pbcopy   -> KEYSTORE_BASE64"
