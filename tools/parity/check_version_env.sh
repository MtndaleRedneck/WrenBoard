#!/usr/bin/env bash
# Fails a gradle-invoking step unless the app version env is set: VERSION_CODE a positive integer and
# VERSION_NAME exactly parity-<7 hex>. The app's build.gradle otherwise falls back to git (versionCode -1
# on a shallow checkout), which failed configuration in the first parity run.
set -u
if ! [[ "${VERSION_CODE:-}" =~ ^[1-9][0-9]{0,8}$ ]]; then
  echo "::error::VERSION_CODE must be a positive integer (got '${VERSION_CODE:-}')"; exit 1
fi
if ! [[ "${VERSION_NAME:-}" =~ ^parity-[0-9a-f]{7}$ ]]; then
  echo "::error::VERSION_NAME must be parity-<short app SHA> (got '${VERSION_NAME:-}')"; exit 1
fi
echo "app version env: VERSION_CODE=$VERSION_CODE VERSION_NAME=$VERSION_NAME"
