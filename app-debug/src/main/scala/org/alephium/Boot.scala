package org.alephium

import org.alephium.flow.Mode
import org.alephium.rpc.RPCServer
import org.alephium.mock.{MockBrokerHandler, MockMiner}

object Boot extends RPCServer {

  override val mode = new Mode.Local {
    override def builders: Mode.Builder =
      new MockBrokerHandler.Builder with MockMiner.Builder
  }

  init()
}
