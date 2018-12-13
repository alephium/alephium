package org.alephium.flow.storage

import org.alephium.TxFixture
import org.alephium.util.AlephiumActorSpec

class FlowHandlerSpec extends AlephiumActorSpec("flow_handler_spec") with TxFixture {

  behavior of "BlockPoolHandler"
}
