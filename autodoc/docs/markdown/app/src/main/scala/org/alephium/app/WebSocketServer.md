[View code on GitHub](https://github.com/alephium/alephium/blob/master/app/src/main/scala/org/alephium/app/WebSocketServer.scala)

The `WebSocketServer` class in this file is responsible for creating a WebSocket server that listens for incoming connections and sends real-time notifications to clients subscribed to certain events. The server is built using the Vert.x library and Akka actors.

The `WebSocketServer` class takes in a `Node` object, which represents a node in the Alephium network. It also takes in a WebSocket port number. The server is started by calling the `startSelfOnce()` method, which binds the server to the specified port and network interface. The server listens for incoming WebSocket connections on the `/events` path. When a client connects to the server, the server sends a message to an `EventHandler` actor, which keeps track of all the connected clients. The `EventHandler` actor subscribes to certain events on the `Node` object and sends real-time notifications to all connected clients when these events occur.

The `WebSocketServer` class also defines an `EventHandler` actor, which is responsible for handling incoming events from the `Node` object and sending notifications to all connected clients. The `EventHandler` actor keeps track of all the connected clients using a mutable `HashSet`. When an event occurs on the `Node` object, the `EventHandler` actor sends a notification to all connected clients by sending a message to the Vert.x event bus. The `WebSocketServer` class also defines a `handleEvent` method, which takes an event and returns a JSON-encoded notification that can be sent to clients.

Overall, the `WebSocketServer` class provides a way for clients to receive real-time notifications about events occurring on a node in the Alephium network. This can be useful for building applications that need to respond to changes in the network in real-time. An example of how this class might be used in the larger project is to build a web-based dashboard that displays real-time information about the state of the network, such as the number of connected nodes, the current block height, and the number of transactions in the mempool.
## Questions: 
 1. What is the purpose of this code?
   
   This code defines a WebSocket server that listens for events and sends them to subscribers. It is part of the Alephium project, which is a free software library.

2. What external libraries or dependencies does this code use?
   
   This code uses several external libraries, including Akka, Vert.x, and Tapir. It also imports several classes from the Alephium project, such as Node and FlowHandler.

3. What is the role of the EventHandler class?
   
   The EventHandler class is an Akka actor that subscribes to events and sends them to WebSocket clients. It maintains a list of subscribers and sends events to each one using the Vert.x event bus.