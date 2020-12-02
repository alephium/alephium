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

import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestProbe
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures

import org.alephium.api.CirceUtils.avectorCodec
import org.alephium.api.model._
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.flow.client.Miner
import org.alephium.protocol.model.ChainIndex
import org.alephium.serde.serialize
import org.alephium.util._

class RestServerSpec
    extends AlephiumSpec
    with ScalatestRouteTest
    with EitherValues
    with ScalaFutures
    with NumericHelpers {

  it should "call GET /blockflow" in new RestServerFixture {
    Get(s"/blockflow?fromTs=0&toTs=0") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[FetchResponse] is dummyFetchResponse
    }
    Get(s"/blockflow?fromTs=10&toTs=0}") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /blockflow/blocks/<hash>" in new RestServerFixture {
    Get(s"/blockflow/blocks/${dummyBlockHeader.hash.toHexString}") ~> server.route ~> check {
      val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
      if (brokerConfig.contains(chainIndex.from) || brokerConfig.contains(chainIndex.to)) {
        status is StatusCodes.OK
        responseAs[BlockEntry] is dummyBlockEntry
      } else {
        status is StatusCodes.BadRequest
      }
    }
  }

  it should "call GET /addresses/<address>/balance" in new RestServerFixture {
    Get(s"/addresses/$dummyKeyAddress/balance") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Balance] is dummyBalance
    }
  }

  it should "call GET /addresses/<address>/group" in new RestServerFixture {
    Get(s"/addresses/$dummyKeyAddress/group") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Group] is dummyGroup
    }
  }

  it should "call GET /blockflow/hashes" in new RestServerFixture {
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[HashesAtHeight] is dummyHashesAtHeight
    }
    Get(s"/blockflow/hashes?toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=10&toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=-1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /blockflow/chains" in new RestServerFixture {
    Get(s"/blockflow/chains?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[ChainInfo] is dummyChainInfo
    }
    Get(s"/blockflow/chains?toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/chains?fromGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/chains?fromGroup=10&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/chains?fromGroup=1&toGroup=10") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /transactions/unconfirmed" in new RestServerFixture {
    Get(s"/transactions/unconfirmed?fromGroup=0&toGroup=0") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[AVector[Tx]] is AVector.empty[Tx]
    }
  }

  it should "call GET /transactions/build" in new RestServerFixture {
    Get(s"/transactions/build?fromKey=$dummyKey&toAddress=$dummyToAddres&value=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[BuildTransactionResult] is dummyBuildTransactionResult
    }
  }

  it should "call POST /transactions/send" in new RestServerFixture {
    val tx =
      s"""{"tx":"${Hex.toHexString(serialize(dummyTx.unsigned))}","signature":"${dummySignature.toHexString}","publicKey":"$dummyKey"}"""
    val entity = HttpEntity(ContentTypes.`application/json`, tx)
    Post(s"/transactions/send", entity) ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[TxResult] is dummyTransferResult
    }
  }

  it should "call POST /miners" in new RestServerFixture {
    Post(s"/miners?action=start-mining") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Boolean] is true
      minerProbe.expectMsg(Miner.Start)
    }

    Post(s"/miners?action=stop-mining") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Boolean] is true
      minerProbe.expectMsg(Miner.Stop)
    }
  }

  it should "call GET /docs" in new RestServerFixture {
    Get(s"/docs") ~> server.route ~> check {
      status is StatusCodes.PermanentRedirect
    }
    Get(s"/docs/openapi.yaml") ~> server.route ~> check {
      status is StatusCodes.OK
    }
  }

  trait RestServerFixture extends ServerFixture {
    lazy val minerProbe = TestProbe()
    lazy val miner      = ActorRefT[Miner.Command](minerProbe.ref)

    lazy val blockFlowProbe = TestProbe()
    lazy val node = new NodeDummy(dummyIntraCliqueInfo,
                                  dummyNeighborCliques,
                                  dummyBlock,
                                  blockFlowProbe.ref,
                                  dummyTx,
                                  storages)
    lazy val blocksExporter     = new BlocksExporter(node)
    lazy val server: RestServer = RestServer(node, miner, blocksExporter, None)
  }
}
