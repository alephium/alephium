package org.alephium

import org.alephium.flow.Mode
import org.alephium.mock.{MockBrokerHandler, MockMiner}

object Boot extends RPCServer {

  override val mode = new Mode.Local {
    override def builders: Mode.Builder =
      new MockBrokerHandler.Builder with MockMiner.Builder
  }

  init()
}
