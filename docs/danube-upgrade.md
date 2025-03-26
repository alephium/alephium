## Danube Network Upgrade

The Danube upgrade represents a major milestone for the Alephium blockchain, delivering substantial performance improvements, enhanced user experience, and expanded developer capabilities. This upgrade builds upon the foundation established by the Rhone upgrade to further advance the network's scalability, usability, and functionality.

### Network Performance Improvements

- **Reduced Block Time**: Block time has been reduced from 16 seconds to 8 seconds through comprehensive client optimizations, with mining emission adjusted proportionally to maintain the same average emission rate. This change doubles block throughput while maintaining network security.
- **Sync Protocol V2**: Completely redesigned synchronization algorithm that dramatically reduces resource consumption while enabling parallel block downloads for faster network synchronization. This allows new nodes to join the network more efficiently and improves overall network resilience.
- **Optimistic BlockFlow Execution**: Enhanced BlockFlow consensus algorithm with optimistic block execution, significantly improving throughput and network efficiency.

### Tokenomics Enhancements

- **Sustainable Tail Emission**: Implemented a sustainable tail emission model by removing the 81-year mining cap, making the tokenomics more intuitive for newcomers while preserving the original emission schedule for the first 81 years. This approach follows similar models used by Monero and Ethereum.

### User Experience Improvements

- **Groupless Addresses**: Introduced new address types that abstract away the technical concept of address groups, simplifying wallet interactions and making the blockchain more accessible to mainstream users.
- **PassKey Authentication**: Added native support for PassKey authentication through a new address type, enabling passwordless authentication, more seamless onboarding experiences, and improved security.

### Developer Experience Enhancements

- **Chained Contract Calls**: TxScripts can now call multiple contracts and chain asset outputs, enabling more sophisticated contract interactions and better composability for building complex DApps and DeFi protocols.
- **Enhanced VM Instructions**: Added new VM instruction to identify the external caller of a function, complementing the existing instruction that identifies the immediate caller. This improves contract security and enables more advanced permission models.
- **Simplified Contract Creation**: Automated contract deposit management from the transaction caller by the VM, eliminating the need for developers to specify deposit amounts in contract code, reducing complexity and potential errors in smart contract development.

### References

- Main Implementation: [GitHub PR #1254](https://github.com/alephium/alephium/pull/1254)
- Sync V2 Implementation: [GitHub PR #1228](https://github.com/alephium/alephium/pull/1228)
