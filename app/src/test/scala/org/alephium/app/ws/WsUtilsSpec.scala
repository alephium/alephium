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
import scala.concurrent.Future

import io.vertx.core.{Future => VertxFuture}
import org.scalatest.concurrent.ScalaFutures

import org.alephium.app.ws.WsUtils._
import org.alephium.util.AlephiumSpec

class WsUtilsSpec extends AlephiumSpec with WsFixture with ScalaFutures {

  "VertxFuture" should "convert to Scala Future" in {
    val successVertxFuture = VertxFuture.succeededFuture("Success").asScala
    val failureVertxFuture =
      VertxFuture.failedFuture[String](new RuntimeException("Test Failure")).asScala

    whenReady(successVertxFuture) { result =>
      result is "Success"
    }

    whenReady(failureVertxFuture.failed) { exception =>
      exception is a[RuntimeException]
      exception.getMessage is "Test Failure"
    }
  }

  "Scala Future" should "convert to VertxFuture" in {
    val successfulFuture: Future[String] = Future.successful("Success")
    val failedFuture: Future[String]     = Future.failed(new RuntimeException("Test Failure"))

    val vertxFutureFromSuccess = successfulFuture.asVertx(Implicits.global)
    val vertxFutureFromFailure = failedFuture.asVertx(Implicits.global)

    vertxFutureFromSuccess.onComplete { handler =>
      handler.succeeded() is true
      handler.result() is "Success"
      ()
    }

    vertxFutureFromFailure.onComplete { handler =>
      handler.failed() is true
      handler.cause() is a[RuntimeException]
      handler.cause().getMessage is "Test Failure"
      ()
    }
  }
}
