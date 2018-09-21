package org.alephium.storage

import org.alephium.{AlephiumActorSpec, TxFixture}

class FlowHandlerSpec extends AlephiumActorSpec("flow_handler_spec") with TxFixture {

  behavior of "BlockPoolHandler"
}
