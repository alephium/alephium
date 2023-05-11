[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network/udp)

The `.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network/udp` folder contains code for handling UDP communication in the Alephium project. It includes three main files: `SelectionHandler.scala`, `SerializedExecutionContext.scala`, and `UdpServer.scala`.

`SelectionHandler.scala` defines the `SelectionHandler` object and class, which are responsible for selecting UDP packets from a selector. The object extends the Akka actor system, while the class handles the actual packet selection. The `registerTask` method adds tasks to the `pendingTasks` buffer and wakes up the selector. The `select` method selects UDP packets from the selector, and the `loop` method executes the `select` method in a loop.

```scala
val selectionHandler = SelectionHandler(system)
val udpServer = new UdpServer(selectionHandler)
selectionHandler.registerTask(udpServer)
```

`SerializedExecutionContext.scala` provides the `SerializedExecutionContext` class, which is a serialized execution context for tasks that need to be executed in a specific order. It maintains a queue of tasks and ensures they are executed in the order they are added. This is useful when processing network messages that need to be executed in a specific order.

```scala
import org.alephium.flow.network.udp.SerializedExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

val serializedContext = SerializedExecutionContext(global)
val task1 = Future { /* do some work */ }
val task2 = Future { /* do some other work */ }

serializedContext.execute(() => task1)
serializedContext.execute(() => task2)
```

`UdpServer.scala` implements a UDP server for sending and receiving data over a network. It defines commands and events for interacting with the server, such as `Bind`, `Send`, and `Read`. The server uses a non-blocking I/O model, creating a `DatagramChannel` and registering it with a `SelectionHandler`. The `SelectionHandler` monitors the channel for incoming data and notifies the server when data is available to be read. The server then sends the data to the `discoveryServer` actor for processing.

```scala
val udpServer = new UdpServer(selectionHandler)
udpServer ! UdpServer.Bind(localAddress)
udpServer ! UdpServer.Send(data, remoteAddress)
```

In summary, this folder contains code for handling UDP communication in the Alephium project. The `SelectionHandler` class selects UDP packets from a selector, the `SerializedExecutionContext` class provides a serialized execution context for tasks, and the `UdpServer` class implements a UDP server for sending and receiving data over a network. These components work together to enable efficient network communication in the Alephium project.
