[View code on GitHub](https://github.com/alephium/alephium/protocol/src/main/scala/org/alephium/protocol/vm/ContractState.scala)

This file contains code related to the storage of smart contract states in the Alephium project. The `ContractState` trait is defined, which represents the state of a smart contract. It contains information such as the code hash, initial state hash, immutable fields, mutable fields, and contract output reference. The `ContractStorageState` trait is also defined, which is a sealed trait that represents the storage state of a smart contract. It has two implementations: `ContractLegacyState` and `ContractMutableState`.

`ContractLegacyState` represents the storage state of a smart contract that was created before the introduction of the new storage model. It contains the code hash, initial state hash, mutable fields, and contract output reference. The immutable fields are empty in this case. `ContractMutableState` represents the storage state of a smart contract that was created using the new storage model. It contains the mutable fields, contract output reference, and the hash of the immutable state.

The `ContractNewState` case class represents the storage state of a smart contract that was created using the new storage model. It contains two fields: `immutable` and `mutable`. `immutable` is an instance of `ContractImmutableState`, which contains the code hash, initial state hash, and immutable fields. `mutable` is an instance of `ContractMutableState`, which contains the mutable fields, contract output reference, and the hash of the immutable state.

The `ContractImmutableState` case class represents the immutable state of a smart contract. It contains the code hash, initial state hash, and immutable fields. The `ContractLegacyState` and `ContractImmutableState` case classes have `serde` instances defined for serialization and deserialization.

The purpose of this code is to provide a way to store the state of smart contracts in the Alephium project. It allows for different storage models to be used, depending on when the smart contract was created. The `ContractState` trait provides a high-level interface for accessing the state of a smart contract, while the `ContractStorageState` trait provides a way to store the state of a smart contract. The `ContractNewState` case class is used to represent the storage state of a smart contract that was created using the new storage model. It is used in other parts of the project to store and retrieve the state of smart contracts.
## Questions: 
 1. What is the purpose of the `alephium.protocol.vm` package?
- The `alephium.protocol.vm` package contains code related to the virtual machine used by the Alephium project.

2. What is the difference between `ContractLegacyState` and `ContractNewState`?
- `ContractLegacyState` represents the storage state of a legacy contract, while `ContractNewState` represents the storage state of a new contract. `ContractNewState` contains both immutable and mutable fields, while `ContractLegacyState` only contains mutable fields.

3. What is the purpose of the `updateMutFieldsUnsafe` and `updateOutputRef` methods in `ContractState`?
- `updateMutFieldsUnsafe` updates the mutable fields of a contract's storage state, while `updateOutputRef` updates the reference to the output produced by the contract. These methods are used to modify the state of a contract during execution.