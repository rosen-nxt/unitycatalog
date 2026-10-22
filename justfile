# Use JDK 17 via JAVA_HOME and the bundled sbt launcher.
default:
    @just --list

server-auth-test:
    build/sbt "server/testOnly io.unitycatalog.server.service.AuthServiceTest io.unitycatalog.server.service.AuthServiceAllowlistTest io.unitycatalog.server.service.AuthServiceClientPrincipalTest io.unitycatalog.server.service.TokenExchangeSubjectTokenHandlerTest io.unitycatalog.server.utils.JwksOperationsTest io.unitycatalog.server.utils.ServerPropertiesTest io.unitycatalog.server.utils.WildcardAllowlistTest"

server-build:
    build/sbt server/compile

format-java:
    build/sbt javafmtAll

ui-install:
    cd ui && bun install --frozen-lockfile

ui-test:
    cd ui && CI=true bun run test --watchAll=false

ui-build:
    cd ui && bun run build

ui-format-check:
    cd ui && bun run test:format

chart-test:
    helm lint helm
    bun test helm/tests

ui-start:
    cd ui && bun run start

# Start the UI from a fresh checkout, using the dependency versions in bun.lock.
start-ui: ui-install
    cd ui && bun run start

server-package:
    build/sbt server/package

# Run with isolated etc/conf and etc/db underneath an absolute directory.
server-start run_dir=".":
    cd "{{ run_dir }}" && "{{ justfile_directory() }}/bin/start-uc-server"

# Start Compose with a shared absolute data path and the host user's UID/GID.
[private]
compose-up service="":
    #!/usr/bin/env bash
    set -euo pipefail
    export UC_SHARED_DATA_DIR="${UC_SHARED_DATA_DIR:-{{ justfile_directory() }}/.compose/data}"
    case "$UC_SHARED_DATA_DIR" in
      /*) ;;
      *) echo 'UC_SHARED_DATA_DIR must be an absolute path' >&2; exit 1 ;;
    esac
    export UC_HOST_UID="$(id -u)" UC_HOST_GID="$(id -g)"
    if [ "$UC_HOST_UID" -eq 0 ] || [ "$UC_HOST_GID" -eq 0 ]; then
      echo 'Run Compose as a non-root user with a non-root primary group' >&2
      exit 1
    fi
    mkdir -p "$UC_SHARED_DATA_DIR"
    docker compose up --build --detach {{ service }}

# Build the server image from this checkout and start its Compose service.
start-server:
    just compose-up server

# Stop the Compose server service while preserving its state and shared data.
stop-server:
    UC_SHARED_DATA_DIR="${UC_SHARED_DATA_DIR:-{{ justfile_directory() }}/.compose/data}" docker compose stop server

# Build the local server image and start the server and UI Compose services.
up:
    just compose-up

# Stop and remove the containers without deleting the database, keys or shared data.
down:
    UC_SHARED_DATA_DIR="${UC_SHARED_DATA_DIR:-{{ justfile_directory() }}/.compose/data}" docker compose down --remove-orphans

# Print the running Compose server's bootstrap admin token. Treat its output as a secret.
admin-token:
    @UC_SHARED_DATA_DIR="${UC_SHARED_DATA_DIR:-{{ justfile_directory() }}/.compose/data}" docker compose exec -T server cat /home/unitycatalog/etc/conf/token.txt

# Check Compose's shared file-URL and persistence mounts without starting Docker.
compose-test:
    python3 dev/test-compose-config.py

server-test:
    build/sbt "server/test"

server-image:
    docker build -t unitycatalog:local .

server-image-smoke:
    bash dev/smoke-server-image.sh

ui-image:
    docker build -f ui/Dockerfile -t unitycatalog-ui:local ui

ui-image-smoke:
    bun ui/scripts/smoke-image.ts
