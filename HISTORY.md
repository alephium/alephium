# Alephium Change Log

## Unreleased

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
* `/infos/history-hashrate` endpoint used for get history hashrate
* `/infos/current-hashrate` endpoint used for get current hashrate

### Improvements
* Api key is optional if the api interface is `127.0.0.1`.
* Better UTXO selection algorithm
