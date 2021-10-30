Docker setup
====

This folder container all the necessary material to build and run Alephium via docker container.

# Prerequisites

We're using [docker-compose](https://docs.docker.com/compose/) to run Alephium here.
Make sure you installed `docker` and `docker-compose` before proceeding further.

# Run

The provided [docker-compose.yml](./docker-compose.yml) file will be used to run Alephium:

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

## GPU Miner (Optional)

Make sure that the Nvidia graphics card works on the host machine. One way to verify is to run
the `nvidia-smi` command.

Install [nvidia-docker](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html#docker),
which enables the docker runtime to access the Nvidia graphics card on the host machine.

Restart docker daemon and run
```
docker run --rm --gpus all --privileged --entrypoint nvidia-smi liuhongchao/gpu-miner:v0.7
```
to verify the setup is successful. It should have the same output as running `nvidia-smi` on the host machine.

To start the GPU miner docker container, run
```
docker-compose -f docker-compose.yml -f docker-compose.gpu-miner.yml up -d
```
