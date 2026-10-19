# Docker Compose

To build Unity Catalog from the current checkout and start it in Docker Compose,
install the latest version of [Docker Desktop](https://www.docker.com/products/docker-desktop/)
and run the following from the project's root directory:

```sh
just up
```

This starts the Unity Catalog server and UI. You can access the UI at
`http://localhost:3000` and the server at `http://localhost:8080`. Clients like
DuckDB or Spark running on the host machine will be able to interact on those
ports with the containers running Unity Catalog.

To stop and remove the services while retaining persistent catalog data, run:

```sh
just down
```

To use the Unity Catalog CLI, attach to a shell in the Unity Catalog server
container:

```sh
docker exec -it unitycatalog-server-1 /bin/bash
```

Use the Unity Catalog CLI from the attached shell to interact with the server:

```sh
bin/uc table list --catalog unity --schema default
```

To remove the containers and persistent volumes, `exit` the attached shell and
run the following from the host machine:

```sh
docker compose down --volumes --remove-orphans
```

Refer to the main [Quickstart](quickstart.md) for more examples of how to
interact with the catalog.

## Configurations

Docker Compose is configured in the `./compose.yaml` file. It builds the local
server image with the checked-out `etc/conf` defaults. Local OIDC overrides are
passed at runtime through the ignored `etc/conf/server.properties.local` file;
see [the OIDC guide](server/oidc-auth.md).

The configuration will also create a persistent, named volume to store the
server's data. This will persist between restarts of the container. See the
[deployment](./server/deployment.md) page for more details on how to configure other
databases like Postgres.
