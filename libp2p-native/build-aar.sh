#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building libp2p-native AAR..."

# Clean previous build
rm -f ../app/libs/libp2p-native.aar

# Build for Android (all architectures)
gomobile bind -v \
    -target=android \
    -androidapi 26 \
    -javapkg="com.anyproto.anyfile.p2p.native" \
    -o ../app/libs/libp2p-native.aar \
    .

echo "✓ AAR built successfully: ../app/libs/libp2p-native.aar"
echo "  File size: $(du -h ../app/libs/libp2p-native.aar | cut -f1)"
