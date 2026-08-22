# P2P Network Findings

Date: 2026-06-04

Scope: review of P2P, discovery, broker, and sync networking code, mainly:

- `flow/src/main/scala/org/alephium/flow/network`
- `flow/src/main/scala/org/alephium/flow/network/broker`
- `flow/src/main/scala/org/alephium/flow/network/interclique`
- `flow/src/main/scala/org/alephium/flow/network/intraclique`
- `flow/src/main/scala/org/alephium/flow/network/sync`
- `flow/src/main/scala/org/alephium/flow/network/udp`
- `protocol/src/main/scala/org/alephium/protocol/message`
- P2P-adjacent model code used by network validation and routing

## Executive summary

The P2P review found several externally reachable denial-of-service and resource-exhaustion issues. The highest-risk issues are the places where network messages are deserialized successfully and then routed using unsafe model helpers before full validation. A malicious peer can use malformed transaction templates, malformed block/header dependency vectors, or unsafe sync height ranges to crash broker actors or force heavy memory and disk work.

Recommended decision: treat findings 1 through 4 as release blockers or near-release blockers because they are direct remote peer paths. Findings 5 through 9 are resource-control and hardening issues that should be fixed in the same work, especially for public nodes.

## Finding 1: `TxsResponse` can crash relay by computing `tx.chainIndex` before transaction validation

Severity: P1

Affected code:

- `flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala`, `handleTxsResponse`
- `flow/src/main/scala/org/alephium/flow/network/intraclique/BrokerHandler.scala`, `handleTxsResponse`
- `flow/src/main/scala/org/alephium/flow/handler/TxHandler.scala`, `validateAndAddTxToMemPool`, `handleIntraCliqueSyncingTx`
- `protocol/src/main/scala/org/alephium/protocol/model/Transaction.scala`, `TransactionAbstract.chainIndex`
- `protocol/src/main/scala/org/alephium/protocol/model/UnsignedTransaction.scala`, `fromGroup`

### Bug

`TxsResponse` is accepted from remote peers as an `AVector[TransactionTemplate]`. The inter-clique broker checks the response with:

```scala
txs.exists(tx => !brokerConfig.contains(tx.chainIndex.from))
```

The intra-clique broker uses an even stronger invariant:

```scala
assume(txs.forall(tx => brokerConfig.isIncomingChain(tx.chainIndex)))
```

Both paths compute `tx.chainIndex` before the transaction has gone through transaction validation.

That is unsafe because `TransactionTemplate` inherits `TransactionAbstract.chainIndex`, which calls `unsigned.fromGroup`. `UnsignedTransaction.fromGroup` uses `inputs.head.fromGroup` and is documented as only working for validated transactions. A network peer can send a transaction template with an empty input vector, or another malformed shape, and trigger an exception before validation returns a normal invalid-transaction result.

`TxHandler` repeats the same unsafe assumption:

- `validateAndAddTxToMemPool` computes `tx.chainIndex` and then `assume(!brokerConfig.isIncomingChain(chainIndex))`
- `handleIntraCliqueSyncingTx` computes `tx.chainIndex` and then `assume(brokerConfig.isIncomingChain(chainIndex))`

### Impact

A malicious connected peer can crash the broker actor, and potentially the tx handler path, with a malformed but deserializable `TxsResponse`.

Impact class: remote peer-triggered actor crash, mempool relay DoS, poor peer accounting, and possible repeated node resource exhaustion if the peer reconnects or many peers are used.

### Recommended fix

Do not call `tx.chainIndex` on unvalidated transaction templates.

Concrete direction:

- Add a safe transaction-template chain-index helper that returns `Either[InvalidTxStatus, ChainIndex]` or `Option[ChainIndex]`.
- Make the helper validate the minimum shape first:
  - non-empty inputs where required
  - valid input/output group hints
  - valid network id
  - input/output count bounds
- In `handleTxsResponse`, reject malformed templates as peer misbehavior instead of evaluating `tx.chainIndex` directly.
- Replace `assume` in intra-clique tx handling with normal error handling and peer penalties.
- In `TxHandler`, make unsafe chain-index assumptions unreachable from remote data. Route through validation first or use the safe helper.

Add regression tests:

- Inter-clique `TxsResponse` with a zero-input `TransactionTemplate` must not throw.
- Intra-clique `TxsResponse` with a zero-input `TransactionTemplate` must not throw.
- Invalid transaction group or shape must publish the expected misbehavior and close or ignore the peer deterministically.
- Valid transaction relay should keep working.

Expected result: malformed transaction relay data is rejected as invalid peer input and cannot crash network actors.

## Finding 2: Malformed block/header dependencies can crash P2P flow-data routing before validation

Severity: P1

Affected code:

- `flow/src/main/scala/org/alephium/flow/network/broker/BrokerHandler.scala`, `validateFlowData`
- `flow/src/main/scala/org/alephium/flow/handler/DependencyHandler.scala`, `processReadies`
- `flow/src/main/scala/org/alephium/flow/validation/Validation.scala`, `validateFlowForest`
- `protocol/src/main/scala/org/alephium/protocol/model/BlockHeader.scala`, `chainIndex`, `parentHash`
- `protocol/src/main/scala/org/alephium/protocol/model/BlockDeps.scala`, `getOutDep`, `outDeps`

### Bug

P2P block and header messages enter through `NewBlock`, `BlocksResponse`, `NewHeader`, and `HeadersResponse`. The broker first runs a PoW precheck and then routes by:

```scala
datas.forall { data => data.chainIndex.relateTo(brokerConfig) == isBlock }
```

`data.chainIndex` for a `BlockHeader` is derived from `blockDeps.length`:

```scala
val groups = (blockDeps.length + 1) / 2
ChainIndex.from(hash, groups)
```

For malformed dependency vectors, this can compute invalid group counts before the dependency count has been checked. Later routing has the same problem: `DependencyHandler.processReadies` indexes `blockHandlers(block.chainIndex)` or `headerHandlers(header.chainIndex)` before the full header validation path has necessarily returned `InvalidDepsNum`.

`Validation.validateFlowForest` also groups by `_.chainIndex` and builds forests using `_.parentHash`. `parentHash` calls `blockDeps.uncleHash(chainIndex.to)`, which can index into malformed `outDeps`.

### Impact

A remote peer can send a malformed header or block that passes deserialization and reaches routing code where dependency-derived fields are evaluated before dependency shape validation. Depending on the malformed dependency vector, this can cause an uncaught exception instead of a deterministic invalid-data result.

Impact class: remote peer-triggered broker/dependency actor crash, network validation DoS, and inconsistent invalid-peer accounting.

### Recommended fix

Validate flow-data shape before any chain-index or parent-hash routing.

Concrete direction:

- Add a cheap shape precheck for `FlowData` received from the network:
  - `header.blockDeps.length == brokerConfig.depsNum`
  - no dependency-derived access before this passes
- Run this precheck before `validateFlowData` calls `data.chainIndex`.
- In `DependencyHandler`, reject malformed dependency length before using `block.chainIndex` or `header.chainIndex` as a map key.
- Avoid using `Validation.validateFlowForest` on untrusted flow data until dependency length is checked.
- Prefer safe accessors that return an invalid status over `assume` or vector indexing.

Add regression tests:

- `NewHeader` with empty or short dependencies must publish invalid flow data or serde/validation failure, not throw.
- `BlocksResponse` containing a block with malformed header dependencies must not crash the broker.
- `DependencyHandler.AddFlowData` with malformed dependencies must not throw when processing readies.

Expected result: malformed dependency shape is handled as invalid P2P input.

## Finding 3: TCP inbound message buffering has no maximum frame or buffer limit

Severity: P1

Affected code:

- `flow/src/main/scala/org/alephium/flow/network/broker/ConnectionHandler.scala`, inbound read buffer
- `protocol/src/main/scala/org/alephium/protocol/message/Message.scala`, message deserialization
- `protocol/src/main/scala/org/alephium/protocol/message/MessageSerde.scala`, `extractMessageBytes`
- `flow/src/main/scala/org/alephium/flow/setting/AlephiumConfig.scala`, `connectionBufferCapacityInByte`

### Bug

The TCP connection handler appends every received chunk to `inMessageBuffer`:

```scala
inMessageBuffer ++= data
```

It then calls deserialization. If the frame is incomplete, `tryDeserializePayload` maps `SerdeError.NotEnoughBytes` to `Right(None)`, and the handler keeps the buffered bytes for the next read.

There is an outbound buffer cap through `connectionBufferCapacityInByte`, but there is no equivalent inbound cap. `MessageSerde.extractMessageBytes` accepts any nonnegative 32-bit length and returns `NotEnoughBytes` until that many payload bytes have arrived.

That means a peer can send a valid magic/checksum/length prefix with a huge declared payload length, then slowly stream bytes or stop. The node keeps buffering and resumes reading without enforcing a maximum message size or maximum partial-frame size.

### Impact

A malicious peer can force unbounded memory growth per TCP connection. Multiple connections can multiply the effect.

Impact class: remote memory exhaustion, actor or JVM OOM, public-node DoS.

### Recommended fix

Add explicit inbound frame and buffer limits.

Concrete direction:

- Define a maximum P2P message size per protocol version.
- Reject frames whose declared length exceeds the limit before buffering the body.
- Reject or close the connection if `inMessageBuffer.length` exceeds the configured inbound limit.
- Track inbound and outbound buffer caps separately if they need different values.
- Penalize peers that send oversized frames.
- Consider using a streaming frame parser that checks length before accumulating payload bytes.

Add regression tests:

- A message with declared length above the limit closes the connection and publishes misbehavior.
- A partial frame that grows beyond the inbound buffer limit is closed.
- Normal split-frame reads still deserialize correctly.

Expected result: a peer cannot consume unbounded memory with incomplete or oversized TCP frames.

## Finding 4: Sync height ranges are unbounded and can overflow before request handling

Severity: P1

Affected code:

- `protocol/src/main/scala/org/alephium/protocol/model/BlockHeightRange.scala`, `length`, `heights`, `isValid`
- `protocol/src/main/scala/org/alephium/protocol/message/Payload.scala`, `HeadersByHeightsRequest`, `BlocksAndUnclesByHeightsRequest`
- `flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala`, `handleFlowDataRequest`, `handleBlocksRequest`, `handleHeadersRequest`
- `flow/src/main/scala/org/alephium/flow/core/BlockHeaderChain.scala`, `getHeadersByHeightsUnsafe`
- `flow/src/main/scala/org/alephium/flow/core/BlockChain.scala`, `getBlocksWithUnclesByHeightsUnsafe`

### Bug

`BlockHeightRange.isValid` only checks:

```scala
from >= 0 && to >= from && step >= 1
```

It does not check that the resulting range length is bounded or even safely representable as an `Int`.

`length` is:

```scala
((to - from) / step) + 1
```

For values like `from = 0`, `to = Int.MaxValue`, `step = 1`, the computed length overflows. For large but non-overflowing ranges, `heights` materializes an `AVector` of all heights.

P2P sync request handling calls `range.heights` when serving requests:

- headers: `getHeadersByHeights(range.heights)`
- blocks: `getBlocksWithUnclesByHeights(range.heights)`

The payload validator accepts the range because `isValid` passes.

### Impact

A connected peer can send a by-height sync request that is valid at the serde layer but causes:

- integer overflow in range length calculations
- assertion failure in `AVector.tabulate`
- huge memory allocation
- large numbers of disk lookups and response bytes

Impact class: remote broker actor crash, memory exhaustion, disk I/O exhaustion, and bandwidth amplification.

### Recommended fix

Make `BlockHeightRange` safe by construction.

Concrete direction:

- Compute range length in `Long`.
- Reject ranges whose length is larger than the protocol maximum, for example `MaxRequestNum`.
- Reject ranges whose `to - from` would overflow.
- Make `BlockHeightRange.from` and serde validation enforce the same bound.
- Avoid materializing `range.heights` before the bound has passed.
- Consider representing ranges as iterators in serving code to avoid unnecessary full-vector allocation.

Add regression tests:

- `BlockHeightRange(0, Int.MaxValue, 1)` must fail serde/request validation.
- A range with length `MaxRequestNum + 1` must fail.
- Boundary range with exactly the maximum allowed length must pass.
- Header and block request handlers must not throw on malformed or oversized ranges.

Expected result: sync ranges cannot overflow and cannot request unbounded work.

## Finding 5: Header-by-height requests bypass the rate limiter used for block downloads

Severity: P2

Affected code:

- `flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala`, `handleBlocksRequest`
- `flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala`, `handleHeadersRequest`
- `flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala`, `handleFlowDataRequest`
- `flow/src/main/scala/org/alephium/flow/network/SimpleRateLimiter.scala`

### Bug

P2P v2 block download requests are rate-limited:

```scala
val size = chains.sumBy(_._2.length)
if (!rateLimiter.tryRequest(size)) {
  ...
} else {
  handleFlowDataRequest(...)
}
```

Header-by-height requests use the same generic `handleFlowDataRequest`, but `handleHeadersRequest` calls it directly with no rate-limit check.

Headers are smaller than blocks, but they still trigger range materialization, disk reads, serialization, and outbound traffic. Combined with the unbounded `BlockHeightRange` issue above, this path can be abused heavily.

### Impact

A peer can repeatedly send `HeadersByHeightsRequest` messages and force the node to perform unbounded header lookups and send responses. The block request limiter does not protect this path.

Impact class: CPU/disk/bandwidth DoS against public P2P nodes.

### Recommended fix

Apply the same request accounting to all by-height sync requests.

Concrete direction:

- Compute request cost as the sum of safe bounded range lengths.
- Rate-limit `HeadersByHeightsRequest` and `BlocksAndUnclesByHeightsRequest` through the same limiter.
- Consider separate cost weights for headers and blocks, but never leave one path unbounded.
- Penalize or disconnect peers that repeatedly exceed limits.
- Ensure the limiter rejects negative or overflowed sizes.

Add regression tests:

- Header requests over the allowed window are ignored or rejected.
- Block and header requests both consume limiter budget.
- Overflowed range length cannot decrease limiter accounting.

Expected result: peers cannot bypass sync request limits by asking for headers instead of blocks.

## Finding 6: Legacy hash-based block/header/tx requests have no count or rate limits

Severity: P2

Affected code:

- `flow/src/main/scala/org/alephium/flow/network/broker/BrokerHandler.scala`, `Received(BlocksRequest)`
- `flow/src/main/scala/org/alephium/flow/network/broker/BrokerHandler.scala`, `Received(HeadersRequest)`
- `flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala`, `handleTxsRequest`
- `protocol/src/main/scala/org/alephium/protocol/message/Payload.scala`, `BlocksRequest`, `HeadersRequest`, `TxsRequest`

### Bug

The common broker handler serves legacy hash-based requests without checking request size or applying a rate limiter:

- `BlocksRequest` maps every requested hash through `blockflow.getHeaderVerifiedBlockBytes`
- `HeadersRequest` maps every requested hash through `blockflow.getBlockHeader`
- `TxsRequest` fetches all requested hashes from the mempool and returns a `TxsResponse`

The request payloads themselves do not impose per-message item limits. Today the only practical bound is the serialized TCP message size, which is also not explicitly capped on inbound frames.

### Impact

A malicious peer can force repeated disk lookups and large responses by sending oversized request vectors. Even after fixing the inbound frame-size issue, the protocol should still enforce semantic request limits so a peer cannot spend one maximum-sized request on excessive disk and bandwidth work.

Impact class: disk I/O exhaustion, bandwidth exhaustion, mempool scraping, and degraded service for honest peers.

### Recommended fix

Add semantic request limits to all request types.

Concrete direction:

- Define maximum item counts for `BlocksRequest`, `HeadersRequest`, and per-chain `TxsRequest`.
- Apply a per-peer rate limiter to these legacy request paths.
- Reject or truncate requests above the limit. Rejecting with misbehavior is preferable for repeated abuse.
- Avoid loading blocks/headers for hashes that are not relevant to the remote broker's group intersection.
- Add metrics for rejected oversized requests.

Add regression tests:

- Oversized `BlocksRequest` does not trigger disk reads for all hashes.
- Oversized `HeadersRequest` is rejected or capped.
- Oversized `TxsRequest` is rejected or capped.
- Normal small requests still get responses.

Expected result: request-serving work is bounded independently of TCP frame size.

## Finding 7: Inbound connection limits do not count handshakes in progress

Severity: P2

Affected code:

- `flow/src/main/scala/org/alephium/flow/network/TcpController.scala`, inbound connection confirmation
- `flow/src/main/scala/org/alephium/flow/network/InterCliqueManager.scala`, `handleConnection`
- `flow/src/main/scala/org/alephium/flow/network/InterCliqueManager.scala`, `getInConnectionPerGroup`
- `flow/src/main/scala/org/alephium/flow/network/broker/InboundBrokerHandler.scala`, handshake duration
- `flow/src/main/scala/org/alephium/flow/network/broker/BrokerHandler.scala`, handshake timeout

### Bug

Inbound TCP connections are confirmed by `TcpController` if the remote IP is not banned. `InterCliqueManager` then decides whether to create an inbound broker handler using:

```scala
checkForInConnection(networkSetting.maxInboundConnectionsPerGroup)
```

But `checkForInConnection` counts only established broker states in `brokers`. A connection is not added there until after the remote peer completes the signed `Hello` handshake.

As a result, connections that are open but still handshaking do not consume inbound capacity. A peer can open many TCP connections and stay silent or delay the handshake until timeout.

### Impact

The configured inbound connection limit protects only completed handshakes. It does not protect file descriptors, actor count, memory, or handshake timer load during connection floods.

Impact class: public-node connection exhaustion and actor/resource DoS.

### Recommended fix

Account for inbound connections before handshake completion.

Concrete direction:

- Add pending inbound counters by remote IP and globally.
- Enforce limits before creating an `InboundBrokerHandler`.
- Count pending handshakes against per-group limits conservatively, or introduce a separate `maxPendingInboundConnections`.
- Add per-IP handshake rate limits.
- Close connections that do not send `Hello` quickly, and penalize repeated timeout behavior.
- Remove pending counters on handshake success, timeout, close, and actor termination.

Add regression tests:

- Multiple inbound sockets from the same remote before handshake cannot exceed the pending limit.
- Pending counters are released on timeout and close.
- Completed handshakes still obey existing per-group limits.

Expected result: inbound capacity limits protect the node before and after handshake.

## Finding 8: UDP discovery responds to unauthenticated spoofable requests and has no source rate limit

Severity: P2

Affected code:

- `protocol/src/main/scala/org/alephium/protocol/message/DiscoveryMessage.scala`, `Ping`, `FindNode`, `Neighbors`, signature selection
- `flow/src/main/scala/org/alephium/flow/network/DiscoveryServer.scala`, `handlePayload`
- `flow/src/main/scala/org/alephium/flow/network/DiscoveryServerState.scala`, `getNeighbors`, `send`
- `flow/src/main/scala/org/alephium/flow/network/udp/UdpServer.scala`, UDP read loop

### Bug

Discovery messages are signed only when the payload exposes a sender clique id. `FindNode` and `Neighbors` use `senderCliqueId = None`, which means the expected signature is zero. `Ping` also has no sender clique id when `senderInfo` is absent.

The discovery server responds to:

- `Ping(id, None)` with `Pong(id, selfPeerInfo)`
- `FindNode(targetId)` with `Neighbors(getNeighbors(targetId))`

There is no per-source or global discovery request rate limiter in `DiscoveryServer` or `UdpServer`.

Because UDP source addresses can be spoofed, these unauthenticated request types can be used to make a node send discovery responses to a victim address. `Neighbors` can be larger than the request because it can include up to `maxSentPeers`.

### Impact

An attacker can abuse public nodes as UDP reflectors and can spend node CPU/bandwidth on unauthenticated discovery traffic. This is not a consensus-integrity issue, but it is relevant for public node operations and network reputation.

Impact class: UDP reflection/amplification, bandwidth DoS, discovery noise, and possible IP-based misbehavior side effects.

### Recommended fix

Harden discovery against spoofing and request floods.

Concrete direction:

- Add per-source rate limiting for discovery requests.
- Require a return-routability cookie or session before sending large `Neighbors` responses.
- Keep unauthenticated responses small, or do not answer unauthenticated `FindNode` directly.
- Consider signing `FindNode` when the sender has a broker identity.
- Drop or deprioritize repeated unauthenticated requests from the same IP/subnet.
- Add metrics for dropped discovery packets by reason.

Add regression tests:

- Repeated `FindNode` from one source is rate-limited.
- `Neighbors` is not sent before the source proves reachability, if the cookie design is adopted.
- Signed/normal discovery between honest peers still works.

Expected result: discovery cannot be used as an easy unauthenticated UDP reflector.

## Finding 9: Intra-clique inventory handling trusts a remote-size invariant

Severity: P3

Affected code:

- `flow/src/main/scala/org/alephium/flow/network/intraclique/BrokerHandler.scala`, `handleInv`
- `flow/src/main/scala/org/alephium/flow/network/intraclique/BrokerHandler.scala`, `extractToSync`

### Bug

The intra-clique broker handles `NewInv` with:

```scala
assume(hashes.length * remoteBrokerInfo.brokerNum == brokerConfig.chainNum)
```

Then `extractToSync` indexes the `hashes` vector by calculated group offsets.

This is an invariant for honest brokers inside the same clique, but it is still reached from a network message. If an intra-clique peer is buggy, compromised, or misconfigured and sends a short `NewInv`, the receiver can throw instead of rejecting the message.

### Impact

This is lower severity because intra-clique peers are more trusted operationally than arbitrary internet peers. Still, a compromised or faulty intra-clique broker can crash the receiver's intra-clique broker actor and disrupt internal synchronization.

Impact class: intra-clique synchronization DoS.

### Recommended fix

Replace the invariant with normal message validation.

Concrete direction:

- Check the expected `hashes.length` before indexing.
- On mismatch, log and close the intra-clique connection or publish a misbehavior event.
- Keep `extractToSync` total by returning `Either` instead of assuming the caller validated.

Add regression tests:

- Short `NewInv` from an intra-clique peer does not throw.
- Oversized `NewInv` is rejected deterministically.
- Correct inventory still produces the expected header/block requests.

Expected result: malformed intra-clique inventory is handled as invalid network input.


P1 — Complete malformed TCP frames remain buffered forever. A deserialization error
     publishes misbehavior but neither discards the frame nor closes the connection (flow/src/
     main/scala/org/alephium/flow/network/broker/ConnectionHandler.scala:291). Every later byte
     causes the same attacker-controlled frame to be checksummed and deserialized again,
     enabling CPU, log, and allocation amplification.

P1 — Vector deserialization preallocates attacker-sized reference arrays. The only bound is
     elementCount <= remainingBytes, followed immediately by Array.ofDim[T](n) (serde/src/main/
     scala/org/alephium/serde/Serde.scala:315). With the production 100 MB frame cap, nested P2P
     vectors can allocate several times the wire size before semantic validation. Combined with
     finding 4, the allocation can repeat for every additional chunk.

P2 — Transaction announcements and responses remain unbounded and uncorrelated.
     NewTxHashes accepts any per-chain count, while TxsResponse has no count validation
     (protocol/src/main/scala/org/alephium/protocol/message/Payload.scala:472). Response IDs are
     ignored before transactions are forwarded into expensive validation (flow/src/main/scala/
     org/alephium/flow/network/interclique/BrokerHandler.scala:242).

P2 — Legacy InvRequest/InvResponse lack dimensions, count limits, rate limits, and
     response correlation.
> maybe we should drop the support of legacy messages
