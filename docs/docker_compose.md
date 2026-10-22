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

### Shared local table data

By default, `just up` creates `.compose/data` in this checkout and mounts its
**absolute path at the same path** in the server container. The server uses
`file:<absolute-shared-data-path>/tables` for the default managed table root
(and `models` for models). Iceberg file URLs returned by the server therefore
work unchanged for host clients. To place data elsewhere, choose an absolute
path **before the first start**:

```sh
export UC_SHARED_DATA_DIR=/path/accessible/to/your/apps/uc-data
just up
```

Other containers must mount `$UC_SHARED_DATA_DIR` at **the same absolute path**
to use those file URLs, for example with:

```sh
docker run -v "$UC_SHARED_DATA_DIR:$UC_SHARED_DATA_DIR" ...
```

The Compose server image is built
with your host UID/GID so you and the server can both write the shared directory;
other applications also need filesystem permission to write there. Keep this
directory limited to trusted local applications: clients that can modify managed
storage can modify or replace the server's files. Catalogs or schemas with their
own managed storage root still use that root instead of this default. Changing
`UC_SHARED_DATA_DIR` does **not** relocate tables already recorded in the catalog.

The H2 database (`etc/db`) and signing keys (`etc/conf`) live together in the
`unitycatalog_state` Docker named volume, not in the shared data directory or
the image. Do not expose that volume to client applications. On the first start,
Docker seeds it with the image's configuration; subsequent image rebuilds do
not replace its `etc/conf` files. Use the ignored
`etc/conf/server.properties.local` for new environment overrides. Existing
Compose deployments with data in a different location are **not migrated**:
back up or migrate them explicitly before replacing their containers.

To display the running server's bootstrap admin token:

```sh
just admin-token
```

This prints a credential to your terminal. Do not paste it into logs, tickets,
or a shared shell transcript. The server refreshes the bootstrap token on
startup; retrieve the current one after a restart.

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

To remove the containers and the **database and signing-key volume**, `exit`
the attached shell and run the following from the host machine (with
`UC_SHARED_DATA_DIR` set as above, or use the default):

```sh
docker compose down --volumes --remove-orphans
```

This does **not** remove the bind-mounted shared data directory; do not delete
it while any catalog or application still references its files. To use Docker
Compose directly instead of `just`, first create the directory, export its
absolute path as `UC_SHARED_DATA_DIR`, and set `UC_HOST_UID=$(id -u)` and
`UC_HOST_GID=$(id -g)` for the image build. Run `just compose-test` to check the
resolved mounts and storage roots without starting containers.

Refer to the main [Quickstart](quickstart.md) for more examples of how to
interact with the catalog.

## Configurations

Docker Compose is configured in the `./compose.yaml` file. It builds the local
server image with the checked-out `etc/conf` defaults. Local OIDC overrides are
passed at runtime through the ignored `etc/conf/server.properties.local` file;
see [the OIDC guide](server/oidc-auth.md).

The configuration creates a named volume for server state and uses the host
bind mount for local table and model data. See the
[deployment](./server/deployment.md) page for more details on how to configure other
databases like Postgres.
