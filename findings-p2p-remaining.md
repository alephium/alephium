# Remaining P2P Network Findings

Date: 2026-08-21

## Scope

This report records findings discovered after the issues in
[findings-p2p-xhigh.md](findings-p2p-xhigh.md) were patched.

The review covered:

- TCP framing, buffering, and connection lifecycle
- Bootstrap and intra-clique admission
- Hello authentication and peer identity handling
- UDP discovery and misbehavior accounting
- Transaction relay and legacy synchronization
- P2P v2 request/response state and chain-state validation

This was a static code and control-flow review. No proof-of-concept traffic was
sent to a live node.

## Executive summary

Ten additional actionable issues remain:

| Priority | Count | Main risks |
| --- | ---: | --- |
| P1 | 5 | Remote process exit, clique-key disclosure, startup takeover, outbound fan-out, actor crashes |
| P2 | 3 | Synchronization stalls, spoofed bans, storage growth, broker-handler crashes |
| P3 | 2 | Rate-limit bypass and degraded peer-connectivity recovery |

The bootstrap findings are conditional on the multi-broker coordinator being
reachable during startup. The production template binds the shared P2P listener
to all interfaces, so deployments must not assume that the bootstrap phase is
automatically private.

Most remediations can be implemented without changing existing wire encodings.
Complete cryptographic replay protection for bootstrap and Hello requires a
compatible versioned challenge or transcript-signing extension. Immediate
source, identity, state, and bounds checks can close the concrete exploit paths
without a breaking protocol change.

## Finding 1: A remote bootstrap disconnect can terminate the production process

Severity: P1

Affected code:

- [Bootstrapper.scala](flow/src/main/scala/org/alephium/flow/network/Bootstrapper.scala#L86)
- [BrokerConnector.scala](flow/src/main/scala/org/alephium/flow/network/bootstrap/BrokerConnector.scala#L79)
- [BaseActor.scala](util/src/main/scala/org/alephium/util/BaseActor.scala#L70)
- [system_prod.conf.tmpl](flow/src/main/resources/system_prod.conf.tmpl#L78)

### Bug

During multi-broker coordinator startup, every accepted TCP connection is
forwarded to CliqueCoordinator. Each connection creates a BrokerConnector,
which watches its ConnectionHandler.

BrokerConnector handles Terminated only in its final forwardReady state.
Termination in the initial, forwardCliqueInfo, or awaitAck state falls through
to unhandled. Its unhandled implementation calls terminateSystem, which calls
sys.exit(1) in production.

An unauthenticated client can therefore connect to the shared P2P listener and
disconnect before bootstrap completes, terminating the node process.

### Impact

A remote party can repeatedly keep a multi-broker coordinator offline during
startup. No valid bootstrap message or clique credential is required.

### Recommended fix

- Handle connection termination explicitly in every bootstrap phase.
- Never call process-wide termination for unexpected remote input.
- Release any state associated with the connector and continue or fail through
  a bounded coordinator-level retry policy.
- Add tests for disconnects before PeerInfo, before clique broadcast, and before
  Ack.

## Finding 2: Unauthenticated bootstrap peers can steal the clique key or stall startup

Severity: P1

Affected code:

- [CliqueCoordinator.scala](flow/src/main/scala/org/alephium/flow/network/bootstrap/CliqueCoordinator.scala#L60)
- [CliqueCoordinatorState.scala](flow/src/main/scala/org/alephium/flow/network/bootstrap/CliqueCoordinatorState.scala#L38)
- [IntraCliqueInfo.scala](flow/src/main/scala/org/alephium/flow/network/bootstrap/IntraCliqueInfo.scala#L25)

### Bug

CliqueCoordinator accepts a self-asserted PeerInfo for any unfilled broker ID.
It does not authenticate the connector, bind the claim to an expected source,
or challenge possession of a configured credential.

Once all broker slots are filled, the coordinator broadcasts IntraCliqueInfo.
That structure contains the clique discovery private key. A client that claims
all missing broker IDs receives the key and can acknowledge those slots.

The coordinator also has no timeout for filling slots or receiving all
acknowledgements. A client can reserve a slot or reach the acknowledgement phase
and remain silent indefinitely.

### Impact

- Disclosure of the shared clique private key
- Attacker-controlled peer addresses in the constructed clique
- Startup takeover or indefinite startup denial of service
- Stale connector and socket accumulation

### Recommended fix

- Restrict bootstrap connections to configured internal peer endpoints or a
  dedicated internal listener.
- Bind every accepted broker ID and acknowledgement to its connector.
- Stop rejected or duplicate connectors immediately.
- Release reservations on termination and add timeouts for every phase.
- Add a versioned authenticated bootstrap challenge for robust cryptographic
  identity verification.

## Finding 3: Hello authentication is replayable and permits self-admission

Severity: P1

Affected code:

- [Payload.scala](protocol/src/main/scala/org/alephium/protocol/message/Payload.scala#L212)
- [IntraCliqueManager.scala](flow/src/main/scala/org/alephium/flow/network/IntraCliqueManager.scala#L133)
- [InterCliqueManager.scala](flow/src/main/scala/org/alephium/flow/network/InterCliqueManager.scala#L522)
- [intraclique/OutboundBrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/intraclique/OutboundBrokerHandler.scala#L33)
- [intraclique/BrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/intraclique/BrokerHandler.scala#L85)

### Bug

Hello signs only BrokerInfo.hash. Client ID and timestamp are not covered, and
validation only requires a positive timestamp. The same signed identity can be
captured and replayed indefinitely, with a modified timestamp or advertised
P2P version.

Concrete admission checks are also missing:

- IntraCliqueManager does not reject the local broker ID.
- InterCliqueManager does not reject the local clique or local peer ID.
- The intra-clique outbound handler discards the expected BrokerInfo and retains
  only its address.

For a two-broker clique, reflecting the target's own Hello creates a one-entry
broker map and satisfies the current ready condition. The attacker then reaches
the trusted intra-clique transaction-response path.

### Impact

- Startup readiness can be spoofed.
- An outsider can impersonate a previously observed peer.
- Client ID tampering can influence protocol-version selection.
- The trusted intra-clique relay path can be exposed to an external connection.

### Recommended fix

- Reject the local broker ID in intra-clique admission.
- Reject the local clique and peer ID in inter-clique admission.
- Verify the expected broker identity for outbound intra-clique connections.
- Require inbound intra-clique sources to match configured internal peers.
- Add a versioned nonce or transcript signature that covers identity, client ID,
  timestamp, direction, and both connection challenges.

## Finding 4: Unsigned Neighbors messages trigger uncontrolled outbound fan-out

Severity: P1

Affected code:

- [DiscoveryMessage.scala](protocol/src/main/scala/org/alephium/protocol/message/DiscoveryMessage.scala#L176)
- [DiscoveryServer.scala](flow/src/main/scala/org/alephium/flow/network/DiscoveryServer.scala#L253)
- [DiscoveryServerState.scala](flow/src/main/scala/org/alephium/flow/network/DiscoveryServerState.scala#L325)
- [UdpServer.scala](flow/src/main/scala/org/alephium/flow/network/udp/UdpServer.scala#L106)

### Bug

Neighbors has no sender clique ID and is therefore accepted with a zero
signature. Its deserializer validates each BrokerInfo but imposes no maximum
count and does not require a matching outstanding FindNode request.

DiscoveryServer sends ConfirmPeer for every unknown entry. Each confirmation
can create a discovery session and send a Ping to the peer-supplied address.
The UDP receive buffer permits a 128 KB datagram, while normal responses send
at most 20 peers.

### Impact

One datagram can cause thousands of actor messages and outbound UDP sends. The
addresses can target arbitrary third parties, including loopback or private
networks. The existing inbound packet limiter counts one input packet rather
than the semantic fan-out.

### Recommended fix

- Reject Neighbors lists larger than the normal response maximum.
- Accept Neighbors only from the exact endpoint of an outstanding FindNode
  exchange or a verified table peer.
- Validate address routability according to deployment policy.
- Add per-source and global outbound discovery budgets.

## Finding 5: ChainState accepts fabricated height, weight, and genesis claims

Severity: P1

Affected code:

- [interclique/BrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala#L496)
- [interclique/BrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala#L650)
- [BlockFlowSynchronizer.scala](flow/src/main/scala/org/alephium/flow/network/sync/BlockFlowSynchronizer.scala#L448)

### Bug

For non-genesis tips, checkChainState verifies acceptable hash work and expected
chain position but does not validate the claimed height or weight. A peer can
pair a known valid block hash with a negative height and an arbitrarily large
weight.

The synchronizer selects tips by claimed weight. The selected height later
reaches calculateRequestSpan, which asserts that both heights are nonnegative.

The genesis alternative is also too broad: any hash and chain position is
accepted when height and weight equal the genesis constants.

### Impact

A normal remote peer can poison best-tip selection and trigger assertion-based
broker-handler crashes or prolonged futile synchronization.

### Recommended fix

- Require nonnegative heights before storing a tip.
- Enforce the expected chain index for every tip, including genesis.
- Require the exact local genesis hash for a genesis claim.
- Treat height and weight as untrusted hints and make all subsequent arithmetic
  total and overflow-safe.
- Disconnect peers that provide internally inconsistent chain-state claims.

## Finding 6: A mismatched response type consumes a valid pending request

Severity: P2

Affected code:

- [interclique/BrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala#L521)
- [interclique/BrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala#L689)
- [BrokerStatusTracker.scala](flow/src/main/scala/org/alephium/flow/network/sync/BrokerStatusTracker.scala#L40)

### Bug

The P2P v2 block and header response handlers remove a pending request by ID
before verifying that the stored request expects that response type.

A peer can answer a block request with a header response, or the reverse. The
handler publishes InvalidResponse, but the pending entry and its timeout have
already been removed. The synchronizer's assigned block task remains pending in
BrokerStatusTracker.

### Impact

A malicious peer can keep a synchronization task permanently assigned without
replying correctly and without triggering the normal request timeout.

### Recommended fix

Look up and validate the expected response type before removing the pending
entry. A mismatch should close the peer or leave the original timeout active.
Ensure the synchronizer is explicitly notified or the task is recycled on every
terminal response error.

## Finding 7: Spoofed UDP parse failures can ban victims and grow storage

Severity: P2

Affected code:

- [DiscoveryServer.scala](flow/src/main/scala/org/alephium/flow/network/DiscoveryServer.scala#L191)
- [MisbehaviorManager.scala](flow/src/main/scala/org/alephium/flow/network/broker/MisbehaviorManager.scala#L117)
- [InMemoryMisbehaviorStorage.scala](flow/src/main/scala/org/alephium/flow/network/broker/InMemoryMisbehaviorStorage.scala#L26)

### Bug

Discovery deserialization errors are reported as SerdeError against the UDP
source address before any signature or reachability proof is available.
Repeated spoofed packets can accumulate penalties against an arbitrary victim
address and eventually ban it.

The misbehavior store is an unbounded mutable map. Expired entries are removed
only when that same peer is queried or when the full list is requested; there
is no periodic global expiration.

### Impact

- Off-path ban of legitimate peers on networks that permit source spoofing
- Long-lived false penalties
- Unbounded memory growth from many spoofed source addresses

### Recommended fix

Do not assign peer penalties for unauthenticated UDP parse failures. Record a
metric and rely on packet rate limiting instead. Bound the storage and
periodically purge expired entries.

## Finding 8: Oversized release-version components throw NumberFormatException

Severity: P2

Affected code:

- [ReleaseVersion.scala](protocol/src/main/scala/org/alephium/protocol/model/ReleaseVersion.scala#L96)
- [broker/BrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/broker/BrokerHandler.scala#L93)

### Bug

The release-version regex accepts digit strings of arbitrary length and then
calls toInt without safe parsing. Hello permits a client ID up to 256 bytes.

A peer can provide an otherwise valid signed BrokerInfo and a client ID whose
major, minor, or patch component exceeds Int range. ReleaseVersion.from throws
instead of returning None.

### Impact

Remote crash or restart of the broker handler during handshake.

### Recommended fix

Use toIntOption or an equivalent safe parser and return None for overflow.
Cover all three version components with regression tests.

## Finding 9: Empty by-height requests bypass request-rate accounting

Severity: P3

Affected code:

- [interclique/BrokerHandler.scala](flow/src/main/scala/org/alephium/flow/network/interclique/BrokerHandler.scala#L599)
- [SimpleRateLimiter.scala](flow/src/main/scala/org/alephium/flow/network/SimpleRateLimiter.scala#L26)

### Bug

isValidHeightRange uses forall, so an empty vector is valid. Its calculated
request cost is zero, and SimpleRateLimiter accepts zero without incrementing
the counter. The broker still constructs and sends an empty response.

### Impact

An authenticated peer can generate unlimited request/response and actor work
outside the intended request budget.

### Recommended fix

Reject empty request vectors or charge a minimum cost of one for every handled
request.

## Finding 10: Periodic outbound-connection refill runs only once

Severity: P3

Affected code:

- [InterCliqueManager.scala](flow/src/main/scala/org/alephium/flow/network/InterCliqueManager.scala#L327)

### Bug

checkConnectionsCount is incremented only inside the branch that runs when its
low four bits are zero. It starts at zero, performs one connection check, and
becomes one. Because it is no longer incremented outside that branch, it can
never reach sixteen and the periodic check never runs again.

### Impact

The node loses its periodic recovery path for insufficient outbound
connections. Other connection and discovery events can mask the problem, so
existing tests may pass while an isolated node remains under-connected.

### Recommended fix

Increment the counter on every status update and run the refill check at counts
0, 16, 32, and so on. Add a test that clears initial probe messages and observes
multiple intervals.

## Recommended remediation order

1. Bootstrap process-exit and authentication/state handling
2. Hello admission guards and replay-resistant handshake design
3. Discovery Neighbors correlation/fan-out and UDP penalty accounting
4. ChainState validation and response-state correctness
5. Safe version parsing, empty-request charging, and connection-refill logic
