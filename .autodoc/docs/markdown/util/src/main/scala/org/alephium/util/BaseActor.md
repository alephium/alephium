[View code on GitHub](https://github.com/alephium/alephium/util/src/main/scala/org/alephium/util/BaseActor.scala)

This file defines a `BaseActor` trait that extends the Akka `Actor` trait and provides some utility methods for scheduling messages to be sent to other actors. The `BaseActor` trait also overrides the `supervisorStrategy` and `unhandled` methods of the `Actor` trait.

The `supervisorStrategy` method is used to define how the actor should handle exceptions thrown by its child actors. In this case, the `BaseActor` trait uses a `DefaultStrategy` object to create a `SupervisorStrategy` that resumes the child actor in case of an exception.

The `unhandled` method is called when the actor receives a message that it doesn't know how to handle. In this case, the `BaseActor` trait logs a warning message indicating that the message was unhandled.

The `BaseActor` trait provides several methods for scheduling messages to be sent to other actors. These methods use the Akka scheduler to send messages at a specified interval or after a specified delay. The `scheduleCancellable` methods return a `Cancellable` object that can be used to cancel the scheduled message.

The `BaseActor` trait also defines a `terminateSystem` method that can be used to terminate the actor system. The behavior of this method depends on the current environment (`Env`), which is an enumeration that represents the current execution environment (e.g., production, integration, test).

The `BaseActor` object defines an `envalidActorName` method that takes a string and returns a new string with invalid characters replaced by hyphens. This method is used to create valid actor names from user input.

Finally, the file defines a `DefaultStrategy` class that extends the `SupervisorStrategyConfigurator` trait and provides two `OneForOneStrategy` objects for handling exceptions thrown by child actors. The `resumeStrategy` resumes the child actor, while the `stopStrategy` stops the child actor. The `DefaultStrategy` class is used by the `BaseActor` trait to define the `supervisorStrategy`.
## Questions: 
 1. What is the purpose of the `BaseActor` trait?
- The `BaseActor` trait is a Scala trait that extends the `Actor` trait and provides additional functionality for scheduling messages and handling unhandled messages.

2. What is the purpose of the `DefaultStrategy` class?
- The `DefaultStrategy` class is a Scala class that implements the `SupervisorStrategyConfigurator` trait and provides a default supervisor strategy for child actors. The strategy is to resume the actor in case of an unhandled throwable, except in the `Env.Test` environment where the actor is stopped.

3. What is the purpose of the `terminateSystem` method?
- The `terminateSystem` method is a method of the `BaseActor` trait that terminates the actor system depending on the current environment. In the `Env.Prod` environment, the system is exited with a status code of 1. In the `Env.Integration` environment, the system is terminated. In all other environments, the actor is stopped.