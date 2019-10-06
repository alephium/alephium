package org.alephium

import org.alephium.appserver.RPCServer
import org.alephium.flow.platform.{Mode, Platform}

object Boot extends Platform with RPCServer {
  val mode = new Mode.Local

  init()
}
