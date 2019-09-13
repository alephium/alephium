package org.alephium

import org.alephium.flow.{Mode, Platform}

object Boot extends Platform with RPCServer {
  val mode = new Mode.Local

  init()
}
