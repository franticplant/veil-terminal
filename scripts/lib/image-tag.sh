#!/bin/sh
# Prints the local build image's name: localhost/veil-build:<tag>, where the
# tag hashes everything the image is built from (build/Containerfile,
# toolchain.lock, install-cargo-tools.sh). Used by scripts/podman-run,
# scripts/sign and private signing set-ups, so all agree on the image.
#   image-tag.sh REPO
set -eu
cd "$1/build"
tag=$(cat Containerfile toolchain.lock install-cargo-tools.sh | sha256sum | cut -c1-16)
echo "localhost/veil-build:$tag"
