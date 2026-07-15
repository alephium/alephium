// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.app

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import io.vertx.core.http.{HttpServer, HttpServerOptions}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe

import org.alephium.flow.handler.TestUtils
import org.alephium.flow.mining.Miner
import org.alephium.http.HttpService
import org.alephium.util.{ActorRefT, AlephiumFutureSpec, Service, SocketUtil}

class RestServerStartupSpec extends AlephiumFutureSpec with SocketUtil {

  it should "register ws handlers before listen() is called" in new ServerFixture {
    implicit val system: ActorSystem  = ActorSystem("rest-server-startup-spec")
    implicit val ec: ExecutionContext = system.dispatcher
    implicit val api: ApiConfig       = ApiConfig.load(newConfig)

    @volatile var wsServiceCompletedAt: Long = 0L
    @volatile var listenCalledAt: Long        = 0L

    val wsStub = new Service {
      override protected def executionContext: ExecutionContext = ec
      override def subServices: ArraySeq[Service]             = ArraySeq.empty
      override protected def startSelfOnce(): Future[Unit] = Future {
        wsServiceCompletedAt = System.nanoTime()
      }(ec)
      override protected def stopSelfOnce(): Future[Unit] = Future.unit
    }

    // Override listen() to record the call time without binding to a port.
    // httpServer is non-null here because spyHttpService.startSelfOnce() (which
    // creates it) runs as a sub-service of RestServer before startSelfOnce() fires.
    val spyHttpService = new HttpService(new HttpServerOptions())(ec) {
      override def listen(port: Int, address: String): Future[HttpServer] = {
        listenCalledAt = System.nanoTime()
        Future.successful(httpServer)
      }
    }

    val (allHandlers, _) = TestUtils.createAllHandlersProbe
    val minerProbe        = TestProbe()
    val miner             = ActorRefT[Miner.Command](minerProbe.ref)

    val node = new ServerFixture.NodeDummy(
      dummyIntraCliqueInfo,
      dummyNeighborPeers,
      dummyBlock,
      TestProbe().ref,
      allHandlers,
      dummyTx,
      dummyContract,
      storages
    )

    val blocksExporter = new BlocksExporter(node.blockFlow, rootPath)(config.broker)

    val restServer = new RestServer(
      node,
      generatePort(),
      miner,
      blocksExporter,
      spyHttpService,
      walletServer = None,
      wsService = Some(wsStub)
    )(config.broker, api, config.network, ec)

    restServer.start().futureValue
    restServer.stop().futureValue

    wsServiceCompletedAt isnot 0L
    listenCalledAt isnot 0L
    (wsServiceCompletedAt < listenCalledAt) is true

    system.terminate().futureValue
    ()
  }
}
