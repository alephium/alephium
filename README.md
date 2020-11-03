# Alephium

This repository is the reference implementation of Alephium platform,
which is a sharded blockchain to make programmable money scalable and secure.

Please visit our website: [https://alephium.org/](https://alephium.org/), telegram: [@alephiumgroup](https://t.me/alephiumgroup) for more information.

## Main Innovations

The protocol's innovations extend battle-tested ideas from [Bitcoin](https://bitcoin.org/bitcoin.pdf) and [Ethereum](https://ethereum.org/en/whitepaper/):

* BlockFlow algorithm based on UTXO model enables sharding and scalability for today (code + [algorithm paper](https://github.com/alephium/research/blob/master/alephium.pdf))
  * the first sharding algorithm supports `single-step cross-shard transactions`, same user experience as single chain
  * the `PoW based sharding` does not rely on beacon chain, simple and elegant
* `Stateful UTXO model` combines the advantages of both eUTXO model and account model (see code, wiki to come)
  * tokens are first-class citizens and UTXO-based, which are safely `owned by users` directly instead of contracts
  * the same `expressiveness as account model`. DApps can be easily built on top of it with better security
  * support `multiple participants` in a single smart contract tx call
* Novel VM design resolves many critical challenges of dApp platforms (see code, wiki to come)
  * it's way `less IO intensive`
  * `flash loan is not available` by design
  * eliminate many `attack vectors of EVM` including unlimited authorization, double dip issue, reentrancy attack, etc
  * Utxo style `fine-grained execution model` makes risk-free arbitrage less common
* `Front-running mitigation` through random execution of transactions (see code, wiki to come)  
* PoLW algorithm `reduces the energy consumption` of PoW in the long term ([research paper](https://github.com/alephium/research/blob/master/polw.pdf))

## Development Status

This project is currently under heavy development toward MainNet launch.
Any contribution is welcome, so don't hesitate to send an issue or pull request.

## Installation

### Requierments

You must have the following dependencies installed on your system in order to run our JAR delivrable:

- java (>= 8, 11 is recommended)

### Running

You can obtain our latest single JAR distribution from the GitHub releases and start the application using the following command:

   java -jar alephium-<VERSION>.jar

## Build from source

### Requierments

In order to build the project from source the following dependencies must be installed on your system:
- java (>= 8, 11 is recommended)
- Python (Python3 is recommended)
- [SBT](https://docs.scala-lang.org/getting-started/sbt-track/getting-started-with-scala-and-sbt-on-the-command-line.html)

### Single JAR

In order to build a single runnable JAR use the following command:
  ./make assembly

The resulting assembly file will appear in `/app-server/target/scala-2.13/` directory.

### Univeral Zip distribution

In order to build a zip distribution including launch scripts use the following command:
  ./make package

The resulting package file will appear in the `app-server/target/scala-2.12/universal` directory.

## Configuration

You can define user specific settings in the file `$ALEPHIUM_HOME/user.conf`, where by default $ALEPHIUM_HOME points to `~/.alephium`.

## Testing

There are two kinds of tests: 

1) Unit and property tests, which can be run with the `./make test` command.
2) Integration tests, `./make ittest`.

