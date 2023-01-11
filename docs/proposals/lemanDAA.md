# Improved DAA for Leman upgrade

## Brief Summary

An improved difficulty adjustment algorithm (DAA) to equalize the mining incentives across different chains.

## Original DAA Design

In the initial version of DAA, each chain's diff is adjusted independently of other chains: this allows each chain to have an individual mining difficulty.

As a result, some chains might be easier to mine than others at certain times and miners distribute hashrate unevenly across different chains.

The DAA adjusts well in such circumstances, but the block times become more dynamic. And this might become a problem in the future.

## Revised DAA Proposal

This proposal makes sure all chains have the same incentives at the same time for miners. Since the mining incentive is based on both `mining_diff` and `block_reward`, we just need to make sure the two are the same for different chains at a specific timestamp.

* In the improved DAA, the `mining_diff` is estimated and adjusted based on all of the chains by "averaging" the estimation done by the original DAA. This makes sure that all chains will have the same mining difficulty.

* `block_reward` has two parts: `coinbase_reward` and `tx_fees`. The constant `coinbase_reward` is the same for different chains when the diff is the same. The `tx_fees` depends on the list of transactions inside a block.

* In the initial design, half of the tx fees get paid to miners, which can provide mining incentive dynamics. In the improved DAA, all of the tx fees will get burnt to resolve the issue.

## Impacts

Here are some of the impacts that the authors are aware of:

* More stable mining difficulty as the new DAA averages the estimations from all of the chains.

* More stable block time due to fairer mining incentives and more stable diff. As a result, more blocks will be mined.

* No tx rewards due to burning. However, depending on the amount of tx fee, there might be more block rewards due to more stable block time.

  * In the short term, this has little impact on the miners as the number of transactions is currently low.
  * In the long term, this does not change the dynamic of mining as miners mine for profit.

* More burning, more deflation. Note that this is a side effect, not the goal of the proposal.

## Questions

**Q**: How unusual is complete tx fee burning?

[EIP1559](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1559.md) is a popular fee mechanism design. During the uncongested periods, it burns almost all of user gas fees as the tips should be close to 0.

Other blockchains, such as [Avalanche](https://docs.avax.network/quickstart/transaction-fees), burn tx fee completely.

**Q**: What will happen when all coins are mined?

As of today, and based on other chain’s examples (see bitcoin’s halvings, ethereum’s EIP1559, etc…), it's still an open question  whether the tx fee is sufficient to guarantee the security of a blockchain over the long run.

As the Alephium protocol has coinbase rewards planned for over more than 80 years, it is expected that Alephium will have enough time to consider, compare & adopt the best solutions over time.

Right now, the core devs' main focuses are on the virtual machine, smart contract language, SDK, infrastructures, etc…

**Q**: Will there be more research on fee mechanism design?

Yes. Transaction fee mechanism design is a fascinating topic and Alephium's protocol design always embraces solid innovations.

There is a lot of research in this field that is worth tracking. As an example, this is a great paper:
`Chung, Hao, and Elaine Shi. "Foundations of transaction fee mechanism design." arXiv preprint arXiv:2111.03151 (2021).`
