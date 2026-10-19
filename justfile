# Use JDK 17 via JAVA_HOME and the bundled sbt launcher.
default:
    @just --list

server-auth-test:
    build/sbt "server/testOnly io.unitycatalog.server.service.AuthServiceTest io.unitycatalog.server.service.AuthServiceAllowlistTest io.unitycatalog.server.service.AuthServiceClientPrincipalTest io.unitycatalog.server.service.TokenExchangeSubjectTokenHandlerTest io.unitycatalog.server.utils.JwksOperationsTest io.unitycatalog.server.utils.ServerPropertiesTest io.unitycatalog.server.utils.WildcardAllowlistTest"

server-build:
    build/sbt server/compile

format-java:
    build/sbt javafmtAll
