[View code on GitHub](https://github.com/alephium/alephium/blob/master/ralph/src/main/scala/org/alephium/ralph/StaticAnalysis.scala)

The `StaticAnalysis` object in the `alephium` project contains a set of functions that perform static analysis on Alephium smart contracts. These functions are used to check the validity of the contracts before they are deployed on the Alephium blockchain. 

The `checkMethodsStateless` function takes an abstract syntax tree (AST) of a contract, a vector of methods, and a state object as input. It checks if private methods are used and if the fields are updated correctly. The `checkMethodsStateful` function calls `checkMethodsStateless` and additionally checks if the code uses contract assets. The `checkMethods` function calls `checkMethodsStateful` and checks the methods of a stateful contract. 

The `checkIfPrivateMethodsUsed` function checks if private methods are used in the contract. If a private method is not used, a warning is issued. 

The `checkCodeUsingContractAssets` function checks if the code uses contract assets. If the code does not use contract assets but the annotation of contract assets is turned on, an error is thrown. 

The `checkUpdateFields` function checks if the fields are updated correctly. If the fields are not updated but the `useUpdateFields` annotation is turned on, a warning is issued. If the fields are updated but the `useUpdateFields` annotation is not turned on, a warning is issued. 

The `checkExternalCallPermissions` function checks if external calls have check external callers. If an external call does not have check external callers, a warning is issued. 

The `checkInterfaceCheckExternalCaller` function checks if the interface has check external callers. If the interface does not have check external callers, an error is thrown. 

The `checkExternalCalls` function checks the validity of external calls in the Alephium smart contracts. It calls `checkExternalCallPermissions` and `checkInterfaceCheckExternalCaller` to check if external calls have check external callers. 

Overall, the `StaticAnalysis` object provides a set of functions that perform static analysis on Alephium smart contracts to ensure their validity before deployment on the Alephium blockchain.
## Questions: 
 1. What is the purpose of the `checkExternalCalls` function and how does it work?
- The `checkExternalCalls` function checks if external calls made by a contract have the necessary permission checks in place. It does this by building a table of functions that require permission checks and checking if the external calls made by a contract have those checks in place. It also checks if child contracts that implement an interface with permission checks have those checks in place.

2. What is the purpose of the `checkUpdateFields` function and when is it called?
- The `checkUpdateFields` function checks if a function updates any mutable fields in a contract and whether the function has the `useUpdateFields` annotation. It is called for each function in a contract except for the main function in a transaction script.

3. What is the purpose of the `checkIfPrivateMethodsUsed` function and when is it called?
- The `checkIfPrivateMethodsUsed` function checks if any private functions in a contract are unused and issues a warning if so. It is called for each private function in a contract.