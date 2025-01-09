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

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.model.JsonRPC.Error
import org.alephium.util.{AVector, EitherF}

object WsUtils {
  def deduplicate[T](vec: AVector[T]): AVector[T] = {
    val seen = mutable.Set[T]()
    vec.filter { elem =>
      if (seen.contains(elem)) {
        false
      } else {
        seen.add(elem)
        true
      }
    }
  }

  def buildUniqueContractAddresses(
      addressArr: mutable.ArrayBuffer[ujson.Value]
  ): Either[Error, AVector[Address.Contract]] = {
    EitherF
      .foldTry(
        addressArr,
        (mutable.Set.empty[String], mutable.ArrayBuffer.empty[Address.Contract])
      ) { case ((seen, addresses), addressVal) =>
        addressVal.strOpt match {
          case Some(address) if seen.contains(address) =>
            Left(WsError.duplicatedAddresses(address))
          case Some(address) =>
            LockupScript.p2c(address).map(Address.Contract(_)) match {
              case Some(contractAddress) =>
                Right((seen.addOne(address), addresses :+ contractAddress))
              case None => Left(WsError.invalidContractAddress(address))
            }
          case None => Left(WsError.invalidContractAddressType)
        }
      }
      .map { case (_, addresses) => AVector.from(addresses) }
  }

  implicit class RichVertxFuture[T](val vertxFuture: VertxFuture[T]) {
    def asScala: Future[T] = {
      val promise = Promise[T]()
      vertxFuture.onComplete {
        case handler if handler.succeeded() =>
          promise.success(handler.result())
        case handler if handler.failed() =>
          promise.failure(handler.cause())
      }
      promise.future
    }
  }
}
