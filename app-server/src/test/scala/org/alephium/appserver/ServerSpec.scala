package org.alephium.appserver

import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.platform.Mode
import org.alephium.util.AlephiumSpec

class ServerSpec extends AlephiumSpec with ScalaFutures {

  behavior of "Server"
  it should "start and stop correctly" in {
    val server = new Server(new Mode.Default)

    server.start.futureValue is (())
    server.stop.futureValue is (())
  }
}
