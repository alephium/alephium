package org.alephium.appserver

import org.alephium.util._

class IntraCliqueSyncTest extends AlephiumSpec {
  it should "boot and sync single node clique" in new TestFixture("1-node") {
    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)
    server.start().futureValue is (())
    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }

  it should "boot and sync two nodes clique" in new TestFixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start().futureValue is (())

    request[Boolean](getSelfCliqueSynced) is false

    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    server1.start().futureValue is (())

    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }
}
