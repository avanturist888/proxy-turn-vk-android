#!/usr/bin/env bash
set -euo pipefail
export HOME=/tmp/wdtt-home
export TMPDIR=/tmp/wdtt-tmp
export XDG_CONFIG_HOME=/tmp/wdtt-config
export XDG_CACHE_HOME=/tmp/wdtt-cache
export GRADLE_USER_HOME=/cache/gradle
export GOCACHE=/cache/go-build
export GOPATH=/cache/go
export GOMODCACHE=/cache/go/pkg/mod
mkdir -p "$HOME" "$TMPDIR" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$GRADLE_USER_HOME" "$GOCACHE" "$GOMODCACHE"
test ! -e local.properties
test ! -e .keys
test -f app/src/main/AndroidManifest.xml
go version
java -version
(cd go_client && go mod tidy && go test ./...)
bash gradlew :app:testDebugUnitTest :app:assembleRelease -PtargetAbis=arm64-v8a --no-daemon --max-workers=4
sha256sum app/build/outputs/apk/release/*arm64*apk
