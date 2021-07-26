# Alephium

This repository contains the reference implementation of Alephium, a sharded
blockchain that makes programmable money scalable and secure.

## Overview

The protocol's innovations extend battle-tested ideas from [Bitcoin](https://bitcoin.org/bitcoin.pdf) and [Ethereum](https://ethereum.org/en/whitepaper/):

* BlockFlow algorithm based on UTXO model enables sharding and scalability for today (code + [algorithm paper](https://github.com/alephium/research/blob/master/alephium.pdf))
  * The first sharding algorithm that supports `single-step cross-shard transactions`, offering the same user experience as single chain
  * Simple and elegant `PoW based sharding`, does not rely on beacon chain
* `Stateful UTXO model` combines the advantages of both eUTXO model and account model (see code, wiki to come)
  * Tokens are first-class citizens and UTXO-based, which are `owned by users` directly instead of contracts
  * Offer the same expressiveness as `account model`. DApps can be easily built on top of it with better security
  * Support `multiple participants` in a single smart contract transaction. Multiple calls can be packed into a single transaction too.
* Novel VM design resolves many critical challenges of dApp platforms (see code, wiki to come)
  * Less IO intensive
  * Flash loan is not available by design
  * Eliminate many attack vectors of EVM, including unlimited authorization, double dip issue, reentrancy attack, etc
  * UTXO style `fine-grained execution model` reduces risk-free arbitrage
* `Front-running mitigation` through random execution of transactions (see code, wiki to come)
* PoLW algorithm reduces the energy consumption of PoW in the long term ([research paper](https://github.com/alephium/research/blob/master/polw.pdf))
  * Adaptive rewards based on hashrate and timestamp are designed and implemented
  * Internal mining cost through burning will be added when hashrate and energy consumption is significantly high

## Development Status

This project is currently under heavy development. You can try out our testnet using the
[Testnet Guide](https://github.com/alephium/alephium/wiki/Testnet-Guide).

## Installation

### Prerequisites

The following dependencies must be installed in order to run the JAR deliverable:

- java (>= 8, 11 is recommended)

### Running

You can obtain our latest single JAR distribution from the GitHub releases and start the application using the following command:

    java -jar alephium-<VERSION>.jar

## Build From Source

### Requirements

In order to build the project from source the following dependencies must be installed on your system:
- java (>= 8, 11 is recommended)
- Python (Python3 is recommended)
- [SBT](https://docs.scala-lang.org/getting-started/sbt-track/getting-started-with-scala-and-sbt-on-the-command-line.html)

### Single JAR

Use the following command to build a single runnable JAR :
  
    make assembly

The resulting assembly file will appear in `/app/target/scala-2.13/` directory.

### Univeral Zip distribution

Use the following command to build a zip distribution including launch scripts:

    make package

The resulting package file will appear in the `app/target/scala-2.13/universal` directory.

### Docker Image

Use the following command to build a docker image:

    make docker

## Configuration

You can define user specific settings in the file `$ALEPHIUM_HOME/user.conf`, where by default `$ALEPHIUM_HOME` points to `~/.alephium`.

## Testing

There are two kinds of tests:

1) Unit tests and property based tests, which can be run with the `make test` command.
2) Integration tests, which can be run with the `make itest` command.
