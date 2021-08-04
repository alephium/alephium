Docker setup
====

This folder container all the necessary material to build and run Alephium via docker container.

# Prerequisites

We're using [docker](https://docs.docker.com/engine/) and [docker-compose](https://docs.docker.com/compose/) to build and run Alephium here.
Make sure you installed both before proceeding further.

# Build

From this `docker` folder, run:

```
docker-compose build alephium
```

A container called `alephium/alephium:latest` will be created from an official [Release](https://github.com/alephium/alephium/releases).

# Run

The provided [docker-compose.yml](./docker-compose.yml] file can also be used to run Alephium:

```
docker-compose up -d
```

The default config connects your container to the testnet, and makes the API available to [http://127.0.0.1:12973/docs](http://127.0.0.1:12973/docs):

```
curl http://127.0.0.1:12973/infos/self-clique
```

## Monitoring

A local instance of grafana will be started at `http://127.0.0.1:3000` with two built-in dashboards:

`JVM Overview`:

```
http://127.0.0.1:3000/d/ME6diT3Mk/jvm-overview?orgId=1&refresh=30s
```

and `Alephium Overview`:
```
http://127.0.0.1:3000/d/S3eJTo3Mk/alephium-overview?orgId=1&refresh=30s
```

# Configuration

In order to persist your data (blocks, wallets, ...), two volumes/mounts can be used.

- `/alephium-home/.alephium` inside the container is where the chain's data and logs are stored
- `/alephium-home/.alephium-wallets` inside the container is where the wallets are stored.

Create these folders on the host:

```
mkdir ./alephium-data ./alephium-wallets && chown nobody ./alephium-data ./alephium-wallets
```

Mount them as volumes inside the container:

```
    volumes:
      - ./alephium-data:/alephium-home/.alephium
      - ./alephium-wallets:/alephium-home/.alephium-wallets
```

All good, your data will survive accross restarts!

For more configuration, check the [Testnet Guide](https://github.com/alephium/alephium/wiki/Testnet-Guide) on the wiki.
