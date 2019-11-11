package org.alephium

import org.alephium.appserver.Server
import org.alephium.flow.platform.Mode
import org.alephium.mock.{MockBrokerHandler, MockMiner}

object Boot extends Server (
  new Mode.Local {
    override def builders: Mode.Builder =
      new MockBrokerHandler.Builder with MockMiner.Builder
  }
) with App
