# Optional details in CallContract responses

Proposal and reference implementation for [issue #1048](https://github.com/alephium/alephium/issues/1048).

## Contract

Add optional boolean `compact` to each `CallContract` request. Omitted, `null`
and `false` preserve the full existing success response, including empty
arrays. `true` returns only `type`, `returns` and `gasUsed` on success:

```json
{"type":"CallContractSucceeded","returns":[],"gasUsed":1234}
```

The five detail fields `contracts`, `txInputs`, `txOutputs`, `events` and
`debugMessages` are omitted, not replaced by empty arrays or nulls. Absence
means “not requested”; an empty array in the full response means “requested
and empty.” Failure responses retain `type: CallContractFailed` and `error`
in both modes. Send a JSON boolean; decoding follows the existing Boolean codec
(including its tolerance of quoted boolean literals), and malformed structures
such as arrays are rejected.
`returns` and `gasUsed` are always present, even for an empty return value.

`POST /contracts/multicall-contract` applies the option independently to each
entry, preserving order and allowing full and compact responses in one batch.
Calls still use the same world-state snapshots and request validation. A compact
call does not bypass method, group, asset, VM, or execution error checking.
The optional flag is carried through existing request forwarding automatically.

## Design and tradeoffs

One opt-in flag keeps the initial contract small. A selectable list of fields
would allow finer bandwidth control, but adds invalid-name handling, field
combinations, generated-client complexity and long-term naming commitments.
A separate endpoint avoids widening a response schema but duplicates routing,
SDK surfaces and batch behavior. Changing the default would break existing
clients and is deliberately avoided.

The shared success schema now models detail arrays as optional, while keeping
the existing discriminator. Old clients retain their wire response when they
do not opt in. Generated SDKs need to regenerate and treat these properties as
optional; that is a source-level compatibility change even though old requests
remain wire-compatible. Scala callers constructing or consuming
`CallContractSucceeded` likewise use `Some`/`Option` for these fields; the case
class constructor change also requires recompiling Scala library consumers.
These source/binary compatibility changes must be called out in release notes. Consumers requesting compact mode must not use
an old decoder that requires the omitted fields. Servers predating this change
may ignore an unknown request field and return a full response, so compact
clients should tolerate full responses as well.

Projection happens only after successful VM execution. It avoids final output
conversion and response serialization of omitted details, but deliberately
retains existing execution and collection logic. This is not a cheaper-gas or
lower-execution-cost mode, and no CPU savings are promised. Skipping internal
state/event collection would be a separate optimization requiring proof that
execution semantics and diagnostics are unchanged.

## Validation

API codec tests cover legacy requests, explicit false/true, invalid input,
compact success, unchanged failure and mixed batch serialization. Existing
full-response JSON tests continue to assert that empty arrays are present.
Server tests compare full and compact results for the same execution and gas,
check explicit false, unchanged failures, mixed batches and unchanged persisted
contract state. An API schema test generates OpenAPI from the actual endpoint definitions and
compares six contract-call schemas with the published document. Only the five
detail arrays become optional; `compact` is exposed on each call request.
These are codec, schema and server-utility tests, not an HTTP transport test.
