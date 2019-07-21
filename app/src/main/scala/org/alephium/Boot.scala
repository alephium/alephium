package org.alephium

import org.alephium.flow.Mode

object Boot extends RPCServer {
  val mode = new Mode.Local

  init()
}
