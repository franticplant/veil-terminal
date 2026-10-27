#!/usr/bin/env bash
# Checks that APKs carry none of the development-only features, so a prod or
# release build can't ship them by mistake. Adapted from Tailmesh's checker.
#
# Usage: scripts/check-release-apk.sh [--expect-dev] APK...
#   --expect-dev  the opposite for the checks that can tell: each must find its
#                 dev-only piece. CI runs it on dev builds, so a check that stops
#                 seeing what it looks for fails there instead of passing
#                 silently.
#
# Per APK:
#   both ways    BuildConfig.DEV_TOOLS (kept by proguard-rules.pro) and the
#                "-dev" versionName suffix
#                classes under io.github.franticplant.veil.dev (the dev source
#                set and the dev API; in a prod build they must be absent, not
#                just unreachable), and the DevHooks service and its DEV_HOOKS
#                permission in the manifest
#   prod only    none of the dev-only permissions below
#   always       not debuggable; libveil_jni.so and the engine host packaged
#                for arm64-v8a; third-party notices present; if the APK is
#                signed, the signer is the pinned Veil certificate
#
# Runs in the build container (scripts/podman-run --offline ...), which has
# aapt2, dexdump and apksigner on PATH.
set -euo pipefail

expect_dev=false
if [[ ${1:-} == --expect-dev ]]; then
	expect_dev=true
	shift
fi
[[ $# -gt 0 ]] || { echo "usage: $0 [--expect-dev] APK..." >&2; exit 2; }

repo=$(cd "$(dirname "$0")/.." && pwd)
pinned=$(tr -d '[:space:]' < "$repo/dev/signing/veil-release-cert.sha256")
# Permissions only the dev control plane may hold.
dev_permissions=(
	android.permission.REQUEST_INSTALL_PACKAGES
	android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION
)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

fail=0
ok() { echo "  ok    $1"; }
bad() {
	echo "  FAIL  $1"
	fail=1
}
# found NAME yes|no: NAME is a dev-only piece; whether it is expected depends on the mode.
found() {
	local name=$1 got=$2
	if $expect_dev; then
		[[ $got == yes ]] && ok "$name (present, as expected in a dev build)" ||
			bad "$name: not found in a dev build; the check no longer sees it"
	else
		[[ $got == no ]] && ok "no $name" || bad "$name is present"
	fi
}
yesno() { if "$@"; then echo yes; else echo no; fi; }

for apk in "$@"; do
	echo "$apk"
	badging=$(aapt2 dump badging "$apk")

	found "-dev version name" "$(yesno grep -qE "^package: .* versionName='[^']*-dev'" <<< "$badging")"

	rm -rf "${work:?}"/*
	unzip -q -o "$apk" 'classes*.dex' -d "$work"
	dump=$work/dump.txt
	for dex in "$work"/classes*.dex; do
		dexdump -d "$dex" 2> /dev/null >> "$dump"
	done
	value=$(grep -A4 "name          : 'DEV_TOOLS'" "$dump" | grep -m1 -oE 'value +: (true|false)' | awk '{print $NF}' || true)
	if [[ -z $value ]]; then
		bad "BuildConfig.DEV_TOOLS not found in the dex (proguard-rules.pro keeps it)"
	else
		found "BuildConfig.DEV_TOOLS=true" "$([[ $value == true ]] && echo yes || echo no)"
	fi

	found "classes from the dev source set (io.github.franticplant.veil.dev)" \
		"$(yesno grep -qE "Class descriptor *: 'Lio/github/franticplant/veil/dev/" "$dump")"

	manifest=$(aapt2 dump xmltree --file AndroidManifest.xml "$apk")
	found "DevHooks service" "$(yesno grep -q 'io.github.franticplant.veil.dev.DevHooksService' <<< "$manifest")"
	found "DEV_HOOKS permission" \
		"$(yesno grep -q 'io.github.franticplant.veil.permission.DEV_HOOKS' <<< "$manifest")"

	if ! $expect_dev; then
		for p in "${dev_permissions[@]}"; do
			if grep -q "uses-permission: name='$p'" <<< "$badging"; then bad "$p is requested"; else ok "no $p"; fi
		done
	fi

	if grep -q "application-debuggable" <<< "$badging"; then bad "debuggable"; else ok "not debuggable"; fi
	# Listed into a variable first: `unzip | grep -q` fails under pipefail when
	# grep exits early and unzip gets SIGPIPE.
	listing=$(unzip -l "$apk")
	if grep -q 'lib/arm64-v8a/libveil_jni.so' <<< "$listing"; then
		ok "libveil_jni.so for arm64-v8a"
	else
		bad "libveil_jni.so for arm64-v8a is missing"
	fi
	if grep -q 'assets/third_party_notices.txt' <<< "$listing"; then
		ok "third-party notices"
	else
		bad "third-party notices (assets/third_party_notices.txt) are missing"
	fi
	if grep -q 'lib/arm64-v8a/libveil_engine_host.so' <<< "$listing"; then
		ok "engine host for arm64-v8a"
	else
		bad "the engine host (libveil_engine_host.so) for arm64-v8a is missing"
	fi
	# Every ABI the APK has native code for must include the engine, or the
	# app would install on a device where it cannot start.
	for abi in $(grep -oE 'lib/[^/]+/' <<< "$listing" | sort -u | cut -d/ -f2); do
		grep -q "lib/$abi/libveil_jni.so" <<< "$listing" ||
			bad "native code for $abi without libveil_jni.so"
	done

	if certs=$(apksigner verify --print-certs "$apk" 2> /dev/null); then
		got=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$certs")
		[[ $got == "$pinned" ]] && ok "signed with the pinned Veil certificate" ||
			bad "signed by $got, not the pinned $pinned"
	else
		echo "  --    unsigned; signer not checked"
	fi
done

if [[ $fail != 0 ]]; then
	if $expect_dev; then
		echo "the release check no longer recognises a dev build; update $0" >&2
	else
		echo "development-only pieces found; prod builds must use the prod flavor" >&2
	fi
	exit 1
fi
