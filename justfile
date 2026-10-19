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

server-start:
    bin/start-uc-server

ui-image:
    docker build -f ui/Dockerfile -t unitycatalog-ui:local ui

ui-image-smoke:
    bun ui/scripts/smoke-image.ts
