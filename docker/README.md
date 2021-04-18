Docker setup
====

This folder container all the necessary material to build and run Alephium via docker container.

# Prerequisites

We're using [docker](https://docs.docker.com/engine/) and [docker-compose](https://docs.docker.com/compose/) to build and run Alephium here.
Make sure you installed both before proceeding further.

# Build

From this `docker` folder, run:

```
docker-compose build alephuim
```

A container called `alephium/alephium:latest` will be created from an official [Release](https://github.com/alephium/alephium/releases).

# Run

The provided [docker-compose.yml](./docker-compose.yml] file can also be used to run Alephium:

```
docker-compose up -d alephium
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
