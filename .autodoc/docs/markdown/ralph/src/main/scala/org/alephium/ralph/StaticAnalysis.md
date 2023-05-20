[View code on GitHub](https://github.com/alephium/alephium/ralph/src/main/scala/org/alephium/ralph/StaticAnalysis.scala)

The `StaticAnalysis` object provides a set of functions that perform static analysis on Alephium smart contracts. These functions are used to check the correctness of the contracts before they are deployed on the blockchain. 

The `checkMethodsStateless` function takes an AST (Abstract Syntax Tree) of a contract, a vector of methods, and a state object as input. It checks if private methods are used and if fields are updated correctly. The `checkMethodsStateful` function extends the `checkMethodsStateless` function by checking if the code uses contract assets. The `checkMethods` function extends the `checkMethodsStateful` function by checking if external calls are permitted. 

The `checkIfPrivateMethodsUsed` function checks if private methods are used in the contract. If a private method is not used, a warning is issued. 

The `checkCodeUsingContractAssets` function checks if the code uses contract assets. If the code does not use contract assets, but the annotation of contract assets is turned on, an error is thrown. 

The `checkUpdateFields` function checks if fields are updated correctly. If fields are updated and the `useUpdateFields` flag is not set, a warning is issued. If fields are not updated and the `useUpdateFields` flag is set, a warning is issued. 

The `checkExternalCallPermissions` function checks if external calls are permitted. If an external call is made to a non-simple view function and the `checkExternalCaller` flag is not set, a warning is issued. 

The `buildNonSimpleViewFuncSet` function builds a set of non-simple view functions. A non-simple view function is a function that modifies the state of the contract. 

The `updateNonSimpleViewFuncSet` function updates the set of non-simple view functions. 

The `checkExternalCalls` function checks if external calls are permitted. It builds a table of functions that can be called externally and checks if non-simple view functions are called externally. 

Overall, the `StaticAnalysis` object provides a set of functions that perform static analysis on Alephium smart contracts. These functions are used to check the correctness of the contracts before they are deployed on the blockchain.
## Questions: 
 1. What is the purpose of the `StaticAnalysis` object?
- The `StaticAnalysis` object contains methods for checking the statelessness/statefulness of methods in a contract, whether private methods are used, whether contract assets are used in a function, and whether external calls are properly authorized.

2. What is the purpose of the `checkMethods` method?
- The `checkMethods` method checks the statelessness/statefulness of methods in a contract, as well as whether private methods are used and whether contract assets are used in a function.

3. What is the purpose of the `checkExternalCalls` method?
- The `checkExternalCalls` method checks whether external calls are properly authorized by checking if non-simple view functions are properly authorized to make external calls.