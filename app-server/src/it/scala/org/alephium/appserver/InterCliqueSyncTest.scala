package org.alephium.appserver

import java.net.InetSocketAddress

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.alephium.appserver.ApiModel._
import org.alephium.util._

class InterCliqueSyncTest extends AlephiumSpec {
  it should "boot and sync two cliques" in new TestFixture("2-cliques-of-2-nodses") {
    val fromTs = TimeStamp.now()

    val clique1           = bootClique(nbOfNodes = 2)
    val masterPortClique1 = clique1.head.config.network.masterAddress.getPort

    Future.sequence(clique1.map(_.start())).futureValue

    startWS(wsPort(masterPortClique1))

    clique1.foreach { server =>
      request[Boolean](startMining, rpcPort(server.config.network.publicAddress.getPort)) is true
    }

    blockNotifyProbe.receiveN(10, Duration.ofMinutesUnsafe(2).asScala)

    clique1.foreach { server =>
      request[Boolean](stopMining, rpcPort(server.config.network.publicAddress.getPort)) is true
    }

    val selfClique1 = request[SelfClique](getSelfClique, rpcPort(masterPortClique1))

    val clique2 =
      bootClique(nbOfNodes = 2,
                 bootstrap = Some(new InetSocketAddress("localhost", masterPortClique1)))
    val masterPortClique2 = clique2.head.config.network.masterAddress.getPort

    Future.sequence(clique2.map(_.start())).futureValue

    clique2.zip(clique1).zipWithIndex.foreach {
      case ((server, remote), index) =>
        eventually {
          val response =
            request[Seq[InterCliquePeerInfo]](getInterCliquePeerInfo,
                                              rpcPort(server.config.network.publicAddress.getPort))
          response is Seq(
            InterCliquePeerInfo(selfClique1.cliqueId,
                                index,
                                new InetSocketAddress("localhost",
                                                      remote.config.network.publicAddress.getPort),
                                true))
        }
    }

    val toTs = TimeStamp.now()

    request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique1)).blocks.size is
      request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique2)).blocks.size
  }
}
