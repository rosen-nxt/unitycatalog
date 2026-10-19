#!/usr/bin/env bash
set -euo pipefail

image="${1:-unitycatalog:local}"
name="uc-server-smoke-$$"

# Test before startup: the runtime must generate its own auth material.
docker run --rm --entrypoint sh "$image" -c '
  for file in private_key.der private_key.pem public_key.der key_id.txt certs.json token.txt; do
    test ! -e "etc/conf/$file" || { echo "Unexpected baked authentication artifact: $file"; exit 1; }
  done
'

cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --name "$name" -p 127.0.0.1::8080 "$image" >/dev/null
port="$(docker port "$name" 8080/tcp | cut -d: -f2)"
for attempt in $(seq 1 90); do
  if curl --silent --fail --output /dev/null "http://127.0.0.1:$port/api/2.1/unity-catalog/metastore_summary"; then
    docker exec "$name" sh -c 'test -s etc/conf/private_key.der && test -s etc/conf/token.txt'
    echo 'Server image startup/API and runtime-generated authentication material: passed'
    exit 0
  fi
  sleep 1
done
docker logs "$name"
echo 'Server image did not become ready' >&2
exit 1
