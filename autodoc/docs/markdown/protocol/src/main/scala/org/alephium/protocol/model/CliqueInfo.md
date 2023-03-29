[View code on GitHub](https://github.com/alephium/alephium/blob/master/protocol/src/main/scala/org/alephium/protocol/model/CliqueInfo.scala)

The `CliqueInfo` class is a model that represents information about a clique in the Alephium protocol. A clique is a group of brokers that work together to validate transactions and maintain the blockchain. The purpose of this class is to provide information about the brokers in a clique, their addresses, and their roles.

The `CliqueInfo` class has several properties. The `id` property is a unique identifier for the clique. The `externalAddresses` property is a vector of optional external addresses for each broker in the clique. The `internalAddresses` property is a vector of internal addresses for each broker in the clique. The `groupNumPerBroker` property is the number of groups assigned to each broker. The `priKey` property is the private key for the clique.

The `CliqueInfo` class has several methods. The `brokerNum` method returns the number of brokers in the clique. The `cliqueConfig` method returns a `CliqueConfig` object that contains information about the clique's configuration. The `intraBrokers` method returns a vector of `BrokerInfo` objects that represent the brokers in the clique. The `coordinatorAddress` method returns the internal address of the coordinator broker. The `selfInterBrokerInfo` method returns an `InterBrokerInfo` object that represents the current broker's inter-broker information. The `selfBrokerInfo` method returns a `BrokerInfo` object that represents the current broker's information. The `interBrokers` method returns a vector of `BrokerInfo` objects that represent the external addresses of the brokers in the clique.

The `CliqueInfo` class also has a `validate` method that validates the information in a `CliqueInfo` object. The `unsafe` method creates a new `CliqueInfo` object.

Overall, the `CliqueInfo` class provides information about a clique in the Alephium protocol. It can be used to configure and manage the brokers in a clique, as well as to validate the information in a `CliqueInfo` object.
## Questions: 
 1. What is the purpose of the `CliqueInfo` class?
- The `CliqueInfo` class represents information about a clique, which is a group of brokers in the Alephium protocol.
2. What is the significance of the `groupNumPerBroker` parameter?
- The `groupNumPerBroker` parameter determines the number of groups assigned to each broker in the clique.
3. What is the purpose of the `validate` method in the `CliqueInfo` companion object?
- The `validate` method checks whether the number of groups in the `CliqueInfo` object matches the number of groups specified in the `GroupConfig` object, and returns an error message if they do not match.