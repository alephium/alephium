# Alephium WebSocket API

This sub-project provides WebSocket support for real-time event notifications from the Alephium blockchain.

## Overview

The WebSocket API runs on the same port as the REST API under the `/ws` endpoint. It implements a **JSON-RPC 2.0** compliant subscribe/unsubscribe model with support for multiple event types within a single connection.

### Features

- **Same port as REST API**: WebSocket endpoint at `/ws`
- **JSON-RPC 2.0 compliant**: Standard request/response/notification format
- **Multiple subscriptions per connection**: Subscribe to different event types simultaneously
- **Keep-alive ping/pong**: Automatic connection maintenance
- **Efficient broadcasting**: Uses Vert.x consumer-handler model for scalability

### Supported Event Types

1. **Block notifications** (`block`): Notified when a new block is mined
2. **Transaction notifications** (`tx`): Notified when a new transaction is added to mempool
3. **Contract event notifications** (`contract`): Notified when contract events are emitted for specified addresses

## Configuration

Default network settings:

```conf
alephium.network.ws {
  enabled = false
  max-connections = 100
  max-requests-per-second = 100
  max-frame-size = 16384
  max-subscriptions-per-connection = 50
  max-contract-event-addresses = 100
  ping-frequency = 30 second
}
```

## Connection

### Endpoint URLs

- **Configured REST port**: `ws://localhost:<rest-port>/ws`
- **Default node port**: `ws://localhost:12973/ws`
- **Typical DevNet port**: `ws://localhost:22973/ws`

### Using wscat

```bash
wscat --show-ping-pong -c ws://localhost:22973/ws
```

You should see periodic ping messages:
```
< Received ping (data: "ping")
```

### Using websocat

```bash
websocat --print-ping-rtts --ping-interval 30 ws://localhost:22973/ws
```

This will print round-trip times for ping/pong messages to stderr. You can also customize the ping timeout:

```bash
websocat --print-ping-rtts --ping-interval 30 --ping-timeout 60 ws://localhost:22973/ws
```

## JSON-RPC Commands

All commands follow JSON-RPC 2.0 format with `jsonrpc`, `id`, `method`, and `params` fields.

### Subscribe to Block Notifications

**Request:**
```json
{"jsonrpc": "2.0", "id": 1, "method": "subscribe", "params": ["block"]}
```

**Response:**
```json
{"result":"93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82","id":1,"jsonrpc":"2.0"}
```

The `result` is your subscription ID for this subscription.

**Notification (when a block is mined):**
```json
{"jsonrpc":"2.0","method":"subscription","params":{"subscription":"93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82","result":{...block data...}}}
```

### Subscribe to Transaction Notifications

**Request:**
```json
{"jsonrpc": "2.0", "id": 3, "method": "subscribe", "params": ["tx"]}
```

**Response:**
```json
{"result":"58a72e720ef67dc31efbc40224e24b8770c8a928a04b6400273f838093209745","id":3,"jsonrpc":"2.0"}
```

**Notification (when a transaction is added to mempool):**
```json
{"jsonrpc":"2.0","method":"subscription","params":{"subscription":"58a72e720ef67dc31efbc40224e24b8770c8a928a04b6400273f838093209745","result":{...transaction data...}}}
```

### Subscribe to Contract Events

Subscribe to all events from specific contract addresses:

**Request:**
```json
{"jsonrpc": "2.0", "id": 4, "method": "subscribe", "params": ["contract", {"addresses":["22DqLoDtKZMfr34ZWR8VQs7opZAUG97t2ZRgYSVfwZ3nm"]}]}
```

**Response:**
```json
{"result":"6117f640ab981ed82e2107a745db4ed64d6395f5dfac1933edc1619a18c6b9ef","id":4,"jsonrpc":"2.0"}
```

Subscribe to a specific event index from contract addresses:

**Request:**
```json
{"jsonrpc": "2.0", "id": 5, "method": "subscribe", "params": ["contract", {"addresses":["22DqLoDtKZMfr34ZWR8VQs7opZAUG97t2ZRgYSVfwZ3nm"], "eventIndex":0}]}
```

**Response:**
```json
{"result":"a50e23aa408dc9c8bc4ac982e67a12eb726dfc20ea175225026c032a7f99ebfb","id":5,"jsonrpc":"2.0"}
```

**Notification (when contract event is emitted):**
```json
{"jsonrpc":"2.0","method":"subscription","params":{"subscription":"6117f640ab981ed82e2107a745db4ed64d6395f5dfac1933edc1619a18c6b9ef","result":{...contract event data...}}}
```

### Unsubscribe

Unsubscribe using the subscription ID returned when you subscribed:

**Request:**
```json
{"jsonrpc": "2.0", "id": 6, "method": "unsubscribe", "params": ["93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82"]}
```

**Response:**
```json
{"result":true,"id":6,"jsonrpc":"2.0"}
```

## Error Codes

The WebSocket API returns JSON-RPC error codes for various conditions:

- **-32010 (Already Subscribed)**: Attempted to subscribe to an event type you're already subscribed to
- **-32011 (Already Unsubscribed)**: Attempted to unsubscribe from a subscription that doesn't exist

### Example Error Response

**Already subscribed error:**
```json
{"error":{"code":-32010,"message":"93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82"},"id":2,"jsonrpc":"2.0"}
```

**Already unsubscribed error:**
```json
{"error":{"code":-32011,"message":"93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82"},"id":7,"jsonrpc":"2.0"}
```

## Example Usage Session

Here's a complete example session using `wscat`:

```bash
# Connect to WebSocket
$ wscat --show-ping-pong -c ws://localhost:22973/ws

# Subscribe to blocks
> {"jsonrpc": "2.0", "id": 1, "method": "subscribe", "params": ["block"]}
< {"result":"93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82","id":1,"jsonrpc":"2.0"}

# Subscribe to transactions
> {"jsonrpc": "2.0", "id": 2, "method": "subscribe", "params": ["tx"]}
< {"result":"58a72e720ef67dc31efbc40224e24b8770c8a928a04b6400273f838093209745","id":2,"jsonrpc":"2.0"}

# Subscribe to contract events
> {"jsonrpc": "2.0", "id": 3, "method": "subscribe", "params": ["contract", {"addresses":["22DqLoDtKZMfr34ZWR8VQs7opZAUG97t2ZRgYSVfwZ3nm"]}]}
< {"result":"6117f640ab981ed82e2107a745db4ed64d6395f5dfac1933edc1619a18c6b9ef","id":3,"jsonrpc":"2.0"}

# Wait for notifications...
< {"jsonrpc":"2.0","method":"subscription","params":{"subscription":"93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82","result":{...}}}

# Unsubscribe from blocks
> {"jsonrpc": "2.0", "id": 4, "method": "unsubscribe", "params": ["93e64b6b355a29e8ddd4a43c2832d52300502e52b3e0cb6d032b1ba2bed14d82"]}
< {"result":true,"id":4,"jsonrpc":"2.0"}
```

## Security & Rate Limiting

The WebSocket API includes protection against various attack vectors:

- **Connection flooding**: Limited by `alephium.network.ws.max-connections`
- **Request flooding**: Limited by `alephium.network.ws.max-requests-per-second`
- **Subscription flooding**: Limited by `alephium.network.ws.max-subscriptions-per-connection`
- **Contract address flooding**: Limited by `alephium.network.ws.max-contract-event-addresses`
- **Large payload flooding**: Limited by `alephium.network.ws.max-frame-size`

## See Also

- [Alephium Web3 SDK](https://github.com/alephium/alephium-web3) - JavaScript/TypeScript library with WebSocket support
- [REST API Documentation](../api) - HTTP REST API reference
