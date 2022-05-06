# Alephium Change Log

## Unreleased

### API changes
* Rename `gas` to `gasAmount` for tx endpoints
* Add `gasAmount` and `gasPrice` for tx build responses
* Remove unnecessary `utxosLimit` from tx endpoints

## 1.3.2

* Fix OpenAPI version

## 1.3.1

* Upgrade Rocksdb to support Apple M1

## 1.3.0

### API changes
Note: We introduced many breaking API changes, please refer to OpenAPI file or Swagger UI for query and response examples.
* New endpoints `/events` for contract events. The endpoints are still in beta.
* New endpoints `/infos/version` for node version purely.
* Move personalized info from `/infos/self-clique` to a new endpoint `/infos/chain-params`.
* Path renaming:
  * `/transactions/decode` -> `/transactions/decode-unsigned-tx`
  * `/contracts/build-script` -> `/contracts/unsigned-tx/build-script`
  * `/contracts/build-contract` -> `/contracts/unsigned-tx/build-contract`
* Query and response format for several endpoints have changed to make them dev friendly.

### New features
Note: The focus of `1.3.0` was new features for smart contract development.
* Add loop, multiple return, events, inheritance to Ralph language
* Log contract events and store them in Rocksdb
* Support unit tests for smart contracts via API endpoints
* Improve protocol endpoints to return full data for blocks and transactions.

## All changes
* Improve mining dispatcher by @polarker in https://github.com/alephium/alephium/pull/524
* Add loop for Ralph language by @Lbqds in https://github.com/alephium/alephium/pull/525
* Add unit tests support for smart contracts by @polarker in https://github.com/alephium/alephium/pull/526
* Add multiple return for Ralph language by @Lbqds in https://github.com/alephium/alephium/pull/529
* Fix return statement for Ralph language by @Lbqds in https://github.com/alephium/alephium/pull/530
* Add contract events&logs by @h0ngcha0 in https://github.com/alephium/alephium/pull/531
* Fix/check function return types by @Lbqds in https://github.com/alephium/alephium/pull/532
* Improve contract endpoints by @polarker in https://github.com/alephium/alephium/pull/533
* Support event in contract test by @polarker in https://github.com/alephium/alephium/pull/534
* Update sbt to 1.6.2 by @tdroxler in https://github.com/alephium/alephium/pull/536
* Contract endpoints improvements by @polarker in https://github.com/alephium/alephium/pull/535
* Fix config path in AlephiumConfigSpec by @polarker in https://github.com/alephium/alephium/pull/537
* Improve protocol endpoints by @tdroxler in https://github.com/alephium/alephium/pull/538
* Better openapi by @polarker in https://github.com/alephium/alephium/pull/539
* Improve openapi types by @polarker in https://github.com/alephium/alephium/pull/540
* Improve val for contracts by @polarker in https://github.com/alephium/alephium/pull/542
* Contract lifecycle events by @h0ngcha0 in https://github.com/alephium/alephium/pull/543
* Add contract inheritance by @Lbqds in https://github.com/alephium/alephium/pull/544
* Fix the order of contract inheritance by @polarker in https://github.com/alephium/alephium/pull/545

## 1.2.8
* Improve logging and thread pool for mining controller
* Send latest mining jobs to new mining connections always

## 1.2.7
* Add gasAmount and gasPrice to sweep endpoint

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
