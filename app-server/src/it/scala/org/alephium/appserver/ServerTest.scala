package org.alephium.appserver

import java.net.InetSocketAddress

import akka.actor.Terminated
import akka.io.{IO, Tcp}
import akka.testkit.TestProbe

import org.alephium.appserver.RPCModel._
import org.alephium.util._

class ServerTest extends AlephiumSpec {
  it should "shutdown the node when Tcp port is used" in new Fixture("1-node") {
    val connection = TestProbe()
    IO(Tcp) ! Tcp.Bind(connection.ref, new InetSocketAddress(defaultMasterPort))

    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server.system.whenTerminated.futureValue is a[Terminated]
  }

  it should "shutdown the clique when one node of the clique is down" in new Fixture("2-nodes") {

    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId      = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    server0.stop().futureValue is (())
    server1.system.whenTerminated.futureValue is a[Terminated]
  }

  it should "boot and sync single node clique" in new Fixture("1-node") {
    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)
    server.start().futureValue is (())
    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }

  it should "boot and sync two nodes clique" in new Fixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start.futureValue is (())

    request[Boolean](getSelfCliqueSynced) is false

    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    server1.start.futureValue is (())

    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }

  it should "boot and sync two cliques" in new Fixture("2-cliques-of-2-nodses") {
    val fromTs = TimeStamp.now()

    val clique1           = bootClique(nbOfNodes = 2)
    val masterPortClique1 = clique1.head.config.masterAddress.getPort

    clique1.foreach(_.start)

    startWS(wsPort(masterPortClique1))

    clique1.foreach { server =>
      request[Boolean](startMining, rpcPort(server.config.publicAddress.getPort)) is true
    }

    blockNotifyProbe.receiveN(1, Duration.ofMinutesUnsafe(2).asScala)

    clique1.foreach { server =>
      request[Boolean](stopMining, rpcPort(server.config.publicAddress.getPort)) is true
    }

    val selfClique1 = request[SelfClique](getSelfClique, rpcPort(masterPortClique1))

    val clique2 =
      bootClique(nbOfNodes = 2,
                 bootstrap = Some(new InetSocketAddress("localhost", masterPortClique1)))
    val masterPortClique2 = clique2.head.config.masterAddress.getPort

    clique2.foreach(_.start)

    eventually(
      request[Seq[InterCliquePeerInfo]](getInterCliquePeerInfo, rpcPort(masterPortClique2)) is Seq(
        InterCliquePeerInfo(selfClique1.cliqueId,
                            new InetSocketAddress("localhost", masterPortClique1),
                            true)))

    val toTs = TimeStamp.now()

    request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique1)).blocks.size is
      request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique2)).blocks.size
  }

  it should "work with 2 nodes" in new Fixture("2-nodes") {
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
      request[TransferResult](transfer(publicKey, tranferKey, privateKey, transferAmount), rpcPort)

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

    val tx2 = request[TransferResult](sendTransaction(createTx), rpcPort)

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

  class Fixture(val name: String) extends TestFixture
}
