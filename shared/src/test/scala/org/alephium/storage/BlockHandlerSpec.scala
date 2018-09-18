package org.alephium.storage

import org.alephium.{AlephiumActorSpec, TxFixture}

class BlockHandlerSpec extends AlephiumActorSpec("block_handler_spec") with TxFixture {

  behavior of "BlockPoolHandler"
}
