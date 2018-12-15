package org.alephium

import org.alephium.flow.{Mode, Platform}

object Boot extends Platform {
  val mode = new Mode.Local(args.head.toInt)

  init()
}
