package org.alephium

object Boot extends Platform {
  val mode = new Mode.Aws

  init()
}
