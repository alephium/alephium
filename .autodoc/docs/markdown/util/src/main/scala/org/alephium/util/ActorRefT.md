[View code on GitHub](https://github.com/alephium/alephium/util/src/main/scala/org/alephium/util/ActorRefT.scala)

This file contains the implementation of the `ActorRefT` class and its companion object. The purpose of this class is to provide a type-safe wrapper around an `ActorRef` instance, which is a reference to an actor in the Akka actor system. 

The `ActorRefT` class provides several methods that allow sending messages to the actor referenced by the `ActorRef` instance. These methods include `!`, `forward`, `ask`, and `tell`. The `!` method sends a message of type `T` to the actor, while the `forward` method forwards the message to another actor. The `ask` method sends a message and returns a `Future` that will be completed with the response from the actor. The `tell` method sends a message to the actor with an explicit sender.

The `ActorRefT` class also overrides the `equals` and `hashCode` methods to provide equality comparison based on the underlying `ActorRef` instance.

The companion object provides factory methods for creating instances of `ActorRefT`. The `apply` method creates an instance from an existing `ActorRef` instance, while the `build` methods create a new actor and return an `ActorRefT` instance that references it. The `build` method with two arguments creates an actor with a given name.

This class is likely used throughout the Alephium project to interact with actors in a type-safe manner. For example, a message can be sent to an actor of type `MyActor` using the following code:

```
val myActorRef: ActorRef = ...
val myActor: ActorRefT[MyActor.Message] = ActorRefT(myActorRef)
myActor ! MyActor.Message("hello")
```
## Questions: 
 1. What is the purpose of this code?
   - This code defines a class `ActorRefT` and an object `ActorRefT` in the `org.alephium.util` package, which provide additional functionality to Akka `ActorRef`s.

2. What is the license for this code?
   - This code is licensed under the GNU Lesser General Public License version 3 or later.

3. What are some of the additional methods provided by the `ActorRefT` class?
   - The `ActorRefT` class provides methods for sending messages (`!`, `tell`, `forward`) and asking for a response (`ask`) using an Akka `ActorRef`.