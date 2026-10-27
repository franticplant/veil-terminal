#!/bin/sh
# Aligns and signs one APK with the keystore at $VEIL_KEYSTORE_PATH (default
# /signing/veil-release.p12, where the network-less build container mounts
# it), using $VEIL_KEYSTORE_PASSWORD.
#   sign-apk.sh IN.apk OUT.apk
# Writes OUT.apk, OUT.apk.idsig (v4), OUT.apk.certs (apksigner --print-certs)
# and OUT.apk.badging (first line of aapt2 badging).
set -eu
in=$1 out=$2
zipalign -P 16 -f 4 "$in" "$out.aligned"
apksigner sign \
	--ks "${VEIL_KEYSTORE_PATH:-/signing/veil-release.p12}" --ks-key-alias veil \
	--ks-pass env:VEIL_KEYSTORE_PASSWORD \
	--v1-signing-enabled false --v2-signing-enabled true \
	--v3-signing-enabled true --v4-signing-enabled true \
	--out "$out" "$out.aligned"
rm -f "$out.aligned"
apksigner verify --print-certs "$out" > "$out.certs"
aapt2 dump badging "$out" | head -1 > "$out.badging"
