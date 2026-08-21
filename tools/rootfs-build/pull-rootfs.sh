#!/bin/bash
set -e
cd /tmp/zfold-build
TOKEN=$(curl -s "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull" | jq -r .token)
MANIFEST=$(curl -s -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json" \
  "https://registry-1.docker.io/v2/library/debian/manifests/bookworm")
ARM_DIGEST=$(echo "$MANIFEST" | jq -r '.manifests[]? | select(.platform.architecture=="arm64" and .platform.os=="linux") | .digest' | head -1)
echo "arm64 manifest: $ARM_DIGEST"
ARM_MANIFEST=$(curl -sL -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json" \
  "https://registry-1.docker.io/v2/library/debian/manifests/$ARM_DIGEST")
LAYER=$(echo "$ARM_MANIFEST" | jq -r '.layers[0].digest')
echo "layer: $LAYER"
curl -sL -H "Authorization: Bearer $TOKEN" "https://registry-1.docker.io/v2/library/debian/blobs/$LAYER" -o debian-arm64-rootfs.tar.gz
ls -la debian-arm64-rootfs.tar.gz
