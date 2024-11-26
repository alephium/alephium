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

import scala.concurrent.{Future, Promise}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.util.AVector

sealed trait WsCommand {
  def name: String
  override def toString: String = name
}
object WsCommand {
  val values: AVector[WsCommand] = AVector(Subscribe, Unsubscribe)
  case object Subscribe   extends WsCommand { val name = "subscribe"   }
  case object Unsubscribe extends WsCommand { val name = "unsubscribe" }
  def fromString(name: String): Option[WsCommand] = name match {
    case Subscribe.name   => Some(Subscribe)
    case Unsubscribe.name => Some(Unsubscribe)
    case _                => None
  }

}
sealed trait WsMethod {
  def index: Int
  def name: String
  override def toString: String = name
}
object WsMethod {
  val values: AVector[WsMethod] = AVector(Block, Tx)
  case object Block extends WsMethod { val name = "block"; val index = 0 }
  case object Tx    extends WsMethod { val name = "tx"; val index = 1    }
  def fromString(name: String): Option[WsMethod] = name match {
    case Block.name => Some(Block)
    case Tx.name    => Some(Tx)
    case _          => None
  }
}
final case class WsEvent(command: WsCommand, method: WsMethod) {
  override def toString: String = s"${command.name}:${method.name}"
}
object WsEvent {
  def parseEvent(event: String): Option[WsEvent] = {
    event.split(":").toList match {
      case commandStr :: methodStr :: Nil =>
        for {
          command <- WsCommand.fromString(commandStr)
          method  <- WsMethod.fromString(methodStr)
        } yield WsEvent(command, method)
      case _ => None
    }
  }
}

trait WsUtils {
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
