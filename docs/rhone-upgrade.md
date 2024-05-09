## Rhone Upgrade Core Changes

### Chain Changes:
- **Reduced Target Block Time**: Block time has been reduced from 64 seconds to 16 seconds using the Ghost algorithm for BlockFlow. This change aligns key parameters with Ethereum's approach, including adjustments to mining rewards and support for uncle block rewards.
- **Sequential Transaction**: Transactions within the same block now support sequential execution from the same address across inter-group chains for better user experience.
- **New PoLW Address Type**: A new address type simplifies the creation of Proof-of-Less-Work (PoLW) coinbase transactions.
- **Modified Reorg Depth Protection**: The maximum reorg depth has been adjusted to 200, reducing the reorg window from 106 minutes to 53 minutes.
- **Permissioned Testnet Mining**: Mining on the testnet is now permissioned to maintain stable mining difficulties.

### Ralph & VM Changes:
- **Reduced Contract Deposit**: Minimum contract deposit has been lowered from 1 ALPH to 0.1 ALPH.
- **Map Support**: New instructions and language syntax now support map structures in Ralph.
- **Multiple Inheritance**: Added support for multiple inheritance for improved code organization and reusability.
- **Dynamic Method Dispatch**: Methods can now be dispatched dynamically based on their signatures.
- **Upgraded Reentrancy Protection**: Reentrancy protection has been upgraded from a contract-level to a method-level approach.
- **Receiving-Only Mode**: Contract methods can now receive payments multiple times within the same transaction.
- **Gasless transaction**: Contracts can now pay for gas fees, enabling gasless transactions.
- **Zero Amount Token Functions**: Built-in functions related to tokens now support zero amounts.
- **Mutable New Contracts**: Contracts can utilize fields in the same transaction in which they are created.
- **New Built-In Functions**: Introduced `minimalContractDeposit`, `mapEntryDeposit`, and other built-in functions.
- **Increased Limits**: Transaction gas limit and contract bytecode size limit have been increased for greater flexibility.

Code: https://github.com/alephium/alephium/pull/1084