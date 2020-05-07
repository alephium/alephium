package org.alephium.appserver

import java.net.InetSocketAddress

import org.alephium.appserver.RPCModel._
import org.alephium.util._

class InterCliqueSyncTest extends AlephiumSpec {

  it should "boot and sync two cliques" in new TestFixture("2-cliques-of-2-nodses") {
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

    clique2.zip(clique1).foreach {
      case (server, remote) =>
        eventually {
          val response =
            request[Seq[InterCliquePeerInfo]](getInterCliquePeerInfo,
                                              rpcPort(server.config.publicAddress.getPort))
          response is Seq(
            InterCliquePeerInfo(selfClique1.cliqueId,
                                new InetSocketAddress("localhost",
                                                      remote.config.publicAddress.getPort),
                                true))
        }
    }

    val toTs = TimeStamp.now()

    request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique1)).blocks.size is
      request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique2)).blocks.size
  }
}
