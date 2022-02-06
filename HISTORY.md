# Alephium Change Log

## Unreleased

## 1.2.6
* Improve block cache
* Cosmetic updates

## 1.2.5

* Add gas info to tx building information for tx endpoints
* Increasing polling interval to 2 seconds for mining api
* Improve full node syncing

## 1.2.4

* Improve logging message for 1.2.3 fix

## 1.2.3

* Fix docker volume for wallets
* Fix height indexing for hashes

## 1.2.2

* Add docker volume for wallet
* Improve dispatcher for mining related actors
* Fix locale issue for configuration keys

## 1.2.1

* Check estimated gas for `/transactions/build` endpoint.
* Make debug logging disabled by default

## 1.2.0

### Database change
* Upgrade rocksdb from `5.18.4` to `6.27.3`. Rocksdb will not be able to fallback to version 5 after this.

### API change
* `sweep-all` is replaced by `sweep-active-address` and `sweep-all-addresses`.
Both `sweep` endpoints would try to sweep all the UTXOs with multiple transactions.
No need to run `sweep` endpoints multiple times.
* `/wallets/<wallet>/addresses` endpoint outputs more address information.

### New Feature
* Gas estimation for multi-sig and smart contract transactions.
* UTXO selection for smart contract transactions.
* User-friendly `sweep-active-address` and `sweep-all-addresses` API endpoints.
* Batch write for Merkle tree
* `/infos/history-hashrate` endpoint for getting history hashrate
* `/infos/current-hashrate` endpoint for getting current hashrate

### Improvements
* Api key is optional if the api interface is `127.0.0.1`.
* Better UTXO selection algorithm
