package org.alephium

import org.alephium.flow.{Mode, Platform}
import org.alephium.mock.{MockMiner, MockTcpHandler}

object Boot extends Platform {

  override val mode = new Mode.Local {
    override def builders: Mode.Builder =
      new MockTcpHandler.Builder with MockMiner.Builder
  }

  init()
}
