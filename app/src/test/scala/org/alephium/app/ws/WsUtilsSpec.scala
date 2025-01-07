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

package org.alephium.app.ws

import scala.concurrent.ExecutionContext.Implicits
import scala.util.{Failure, Success}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.util.AVector

class WsUtilsSpec extends WsFixture {
  it should "build addresses" in {
    WsUtils.buildAddresses(AVector("")).isLeft is true
    WsUtils.buildAddresses(AVector(contractAddress_0.toBase58)).isRight is true
    WsUtils.buildAddresses(AVector(contractAddress_0.toBase58, contractAddress_1.toBase58)) match {
      case Right(addresses) =>
        addresses.length is 2
        addresses(0).toBase58 is contractAddress_0.toBase58
        addresses(1).toBase58 is contractAddress_1.toBase58
      case Left(_) => fail("Should return Right for valid addresses")
    }

    WsUtils.buildAddresses(AVector(contractAddress_0.toBase58, "invalid-address")) match {
      case Right(_)    => fail("Should return Left for invalid address")
      case Left(error) => error is WsError.invalidContractAddress("invalid-address")
    }
  }

  it should "find first address duplicate" in {
    WsUtils.firstDuplicate(AVector(contractAddress_0, contractAddress_1)).isEmpty is true
    val duplicateAddresses = AVector(contractAddress_0, contractAddress_1, contractAddress_0)
    WsUtils.firstDuplicate(duplicateAddresses) match {
      case Some(duplicate) => duplicate is contractAddress_0
      case None            => fail("Should find the duplicate address")
    }
    WsUtils.firstDuplicate(AVector.empty[String]).isEmpty is true
    WsUtils.firstDuplicate(AVector(contractAddress_0)).isEmpty is true
    val multipleDuplicates =
      AVector(contractAddress_0, contractAddress_1, contractAddress_0, contractAddress_1)
    WsUtils.firstDuplicate(multipleDuplicates) match {
      case Some(duplicate) => duplicate is contractAddress_0
      case None            => fail("Should find the first duplicate")
    }
  }

  it should "deduplicate addresses" in {
    val duplicateAddresses = AVector(contractAddress_0, contractAddress_1, contractAddress_0)
    WsUtils.deduplicate(duplicateAddresses) is AVector(contractAddress_0, contractAddress_1)
    WsUtils.deduplicate(AVector.empty[String]) is AVector.empty[String]
    WsUtils.deduplicate(AVector(contractAddress_0, contractAddress_1)) is AVector(
      contractAddress_0,
      contractAddress_1
    )
    WsUtils.deduplicate(AVector(contractAddress_0)) is AVector(contractAddress_0)
    val multipleDuplicates =
      AVector(contractAddress_0, contractAddress_1, contractAddress_0, contractAddress_1)
    WsUtils.deduplicate(multipleDuplicates) is AVector(contractAddress_0, contractAddress_1)
  }

  it should "convert VertxFuture to Scala Future" in {
    import WsUtils._
    VertxFuture
      .succeededFuture("Success")
      .asScala
      .onComplete {
        case Success(value) => value is "Success"
        case Failure(_)     => fail("The future should not fail")
      }(Implicits.global)

    val exception                              = new RuntimeException("Test Failure")
    val failedVertxFuture: VertxFuture[String] = VertxFuture.failedFuture(exception)
    failedVertxFuture.asScala
      .onComplete {
        case Success(_)  => fail("The future should not succeed")
        case Failure(ex) => ex is exception
      }(Implicits.global)
  }
}
