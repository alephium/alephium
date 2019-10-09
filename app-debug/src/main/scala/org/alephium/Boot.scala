package org.alephium

import org.alephium.appserver.RPCServer
import org.alephium.flow.platform.{Mode, Platform}
import org.alephium.mock.{MockBrokerHandler, MockMiner}

object Boot extends Platform with RPCServer {

  override val mode = new Mode.Local {
    override def builders: Mode.Builder =
      new MockBrokerHandler.Builder with MockMiner.Builder
  }

  init()
}
