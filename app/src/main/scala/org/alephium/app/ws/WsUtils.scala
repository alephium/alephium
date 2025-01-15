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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.model.JsonRPC.Error
import org.alephium.util.{AVector, EitherF}

object WsUtils {
  def buildUniqueContractAddresses(
      addressArr: mutable.ArrayBuffer[ujson.Value]
  ): Either[Error, AVector[Address.Contract]] = {
    EitherF
      .foldTry(addressArr, mutable.Set.empty[Address.Contract]) { case (addresses, addressVal) =>
        addressVal.strOpt match {
          case Some(address) =>
            LockupScript.p2c(address).map(Address.Contract(_)) match {
              case Some(contractAddress) if addresses.contains(contractAddress) =>
                Left(WsError.duplicatedAddresses(address))
              case Some(contractAddress) =>
                Right(addresses.addOne(contractAddress))
              case None => Left(WsError.invalidContractAddress(address))
            }
          case None => Left(WsError.invalidContractAddressType)
        }
      }
      .map(AVector.from)
  }

  implicit class RichVertxFuture[T](val vertxFuture: VertxFuture[T]) {
    def asScala: Future[T] = {
      val promise = scala.concurrent.Promise[T]()
      vertxFuture.onComplete {
        case handler if handler.succeeded() =>
          promise.success(handler.result())
        case handler if handler.failed() =>
          promise.failure(handler.cause())
      }
      promise.future
    }
  }

  implicit class RichScalaFuture[T](val scalaFuture: Future[T]) {
    def asVertx(implicit ec: ExecutionContext): VertxFuture[T] = {
      val promise = io.vertx.core.Promise.promise[T]()
      scalaFuture.onComplete {
        case Success(value) =>
          promise.complete(value)
        case Failure(exception) =>
          promise.fail(exception)
      }
      promise.future
    }
  }
}
