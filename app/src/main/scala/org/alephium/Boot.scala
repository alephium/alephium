package org.alephium

import org.alephium.flow.Mode
import org.alephium.rpc.RPCServer

object Boot extends RPCServer {
  val mode = new Mode.Local

  init()
}
