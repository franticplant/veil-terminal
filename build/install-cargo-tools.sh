#!/bin/sh
# Installs the cargo tools pinned in toolchain.lock (cargo-ndk, cargo-deny,
# cargo-about) into DIR, from their release binaries, after checking each
# download's SHA-256. Used by build/Containerfile and the CI setup action.
#   install-cargo-tools.sh LOCKFILE DIR
set -eu
lock=$1 dir=$2
# shellcheck disable=SC1090
. "$lock"
mkdir -p "$dir"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
install_one() { # NAME URL SHA256
	curl -fsSL --retry 5 --retry-all-errors -o "$tmp/$1.tgz" "$2"
	echo "$3  $tmp/$1.tgz" | sha256sum -c - > /dev/null
	mkdir "$tmp/$1"
	tar -xzf "$tmp/$1.tgz" -C "$tmp/$1"
	# Every binary in the archive: cargo-ndk ships helpers next to cargo-ndk.
	find "$tmp/$1" -type f -name "$1*" -perm -u+x -exec install -m 755 {} "$dir/" \;
	[ -x "$dir/$1" ] || { echo "no $1 binary in $2" >&2; exit 1; }
}
install_one cargo-ndk "$CARGO_NDK_URL" "$CARGO_NDK_SHA256"
install_one cargo-deny "$CARGO_DENY_URL" "$CARGO_DENY_SHA256"
install_one cargo-about "$CARGO_ABOUT_URL" "$CARGO_ABOUT_SHA256"
