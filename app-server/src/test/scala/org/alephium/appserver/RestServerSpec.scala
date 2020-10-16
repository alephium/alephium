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

package org.alephium.appserver

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestProbe
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures

import org.alephium.appserver.ApiModel._
import org.alephium.appserver.ServerFixture.NodeDummy
import org.alephium.flow.client.Miner
import org.alephium.protocol.model.ChainIndex
import org.alephium.rpc.CirceUtils.avectorCodec
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

  it should "call GET /blocks/<hash>" in new RestServerFixture {
    Get(s"/blocks/${dummyBlockHeader.hash.toHexString}") ~> server.route ~> check {
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

  it should "call GET /hashes" in new RestServerFixture {
    Get(s"/hashes?fromGroup=1&toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[HashesAtHeight] is dummyHashesAtHeight
    }
    Get(s"/hashes?toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/hashes?fromGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/hashes?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/hashes?fromGroup=10&toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/hashes?fromGroup=1&toGroup=10&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/hashes?fromGroup=1&toGroup=10&height=-1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /chains" in new RestServerFixture {
    Get(s"/chains?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[ChainInfo] is dummyChainInfo
    }
    Get(s"/chains?toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/chains?fromGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/chains?fromGroup=10&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/chains?fromGroup=1&toGroup=10") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /unconfirmed-transactions" in new RestServerFixture {
    Get(s"/unconfirmed-transactions?fromGroup=0&toGroup=0") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[AVector[Tx]] is AVector.empty[Tx]
    }
  }

  it should "call GET /unsigned-transactions" in new RestServerFixture {
    Get(s"/unsigned-transactions?fromKey=$dummyKey&toAddress=$dummyToAddres&value=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[CreateTransactionResult] is dummyCreateTransactionResult
    }
  }

  it should "call POST /transactions" in new RestServerFixture {
    val tx =
      s"""{"tx":"${Hex.toHexString(serialize(dummyTx.unsigned))}","signature":"${dummySignature.toHexString}","publicKey":"$dummyKey"}"""
    val entity = HttpEntity(ContentTypes.`application/json`, tx)
    Post(s"/transactions", entity)
      .addHeader(RawHeader("X-API-KEY", apiKey.value)) ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[TxResult] is dummyTransferResult
    }

    //Fail without api-key
    Post(s"/transactions", entity) ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call POST /miners" in new RestServerFixture {
    Post(s"/miners?action=start-mining")
      .addHeader(RawHeader("X-API-KEY", apiKey.value)) ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Boolean] is true
      minerProbe.expectMsg(Miner.Start)
    }

    Post(s"/miners?action=stop-mining")
      .addHeader(RawHeader("X-API-KEY", apiKey.value)) ~> server.route ~> check {
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
    lazy val server: RestServer = RestServer(node, miner, None)
  }
}
