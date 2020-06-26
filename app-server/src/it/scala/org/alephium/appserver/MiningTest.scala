package org.alephium.appserver

import org.alephium.appserver.RPCModel._
import org.alephium.util._

class MiningTest extends AlephiumSpec {
  it should "work with 2 nodes" in new TestFixture("2-nodes") {
    val fromTs = TimeStamp.now()

    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId      = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    val selfClique = request[SelfClique](getSelfClique)
    val group      = request[Group](getGroup(publicKey))
    val index      = group.group / selfClique.groupNumPerBroker
    val rpcPort    = selfClique.peers(index).rpcPort.get

    request[Balance](getBalance(publicKey), rpcPort) is initialBalance

    startWS(defaultWsMasterPort)

    val tx =
      request[TxResult](transfer(publicKey, tranferKey, privateKey, transferAmount), rpcPort)

    selfClique.peers.foreach { peer =>
      request[Boolean](startMining, peer.rpcPort.get) is true
    }

    awaitNewBlock(tx.fromGroup, tx.toGroup)
    awaitNewBlock(tx.fromGroup, tx.fromGroup)

    request[Balance](getBalance(publicKey), rpcPort) is
      Balance(initialBalance.balance - transferAmount, 1)

    val createTx =
      request[CreateTransactionResult](createTransaction(publicKey, tranferKey, transferAmount),
                                       rpcPort)

    val tx2 = request[TxResult](sendTransaction(createTx), rpcPort)

    awaitNewBlock(tx2.fromGroup, tx2.toGroup)
    awaitNewBlock(tx2.fromGroup, tx2.fromGroup)

    selfClique.peers.foreach { peer =>
      request[Boolean](stopMining, peer.rpcPort.get) is true
    }

    request[Balance](getBalance(publicKey), rpcPort) is
      Balance(initialBalance.balance - (2 * transferAmount), 1)

    val toTs = TimeStamp.now()

    //TODO Find a better assertion
    request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort).blocks.size should be > 16

    server1.stop()
    server0.stop()
  }
}
