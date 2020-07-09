package org.alephium.appserver

import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestProbe
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures

import org.alephium.appserver.ApiModel._
import org.alephium.flow.U64Helpers
import org.alephium.flow.platform.Mode
import org.alephium.protocol.model.ChainIndex
import org.alephium.util._

class RestServerSpec
    extends AlephiumSpec
    with ScalatestRouteTest
    with EitherValues
    with ScalaFutures
    with U64Helpers {
  import ServerFixture._

  it should "call GET /blockflow" in new RestServerFixture {
    Get(s"/blockflow?fromTs=0&toTs=0") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[FetchResponse] is dummyFetchResponse
    }
    Get(s"/blockflow?fromTs=10&toTs=0}") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /blocks/<hash>" in new RestServerFixture {
    Get(s"/blocks/${dummyBlockHeader.hash.toHexString}") ~> server.route ~> check {
      val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
      if (config.brokerInfo.contains(chainIndex.from) || config.brokerInfo.contains(chainIndex.to)) {
        status is StatusCodes.OK
        responseAs[BlockEntry] is dummyBlockEntry
      } else {
        status is StatusCodes.BadRequest
      }
    }
  }

  trait RestServerFixture extends ServerFixture {

    lazy val mode: Mode = new ModeDummy(dummyIntraCliqueInfo,
                                        dummyNeighborCliques,
                                        dummyBlock,
                                        TestProbe().ref,
                                        dummyTx,
                                        storages)
    lazy val server: RestServer = RestServer(mode)
  }
}
