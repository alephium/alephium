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

package org.alephium.rpc

import scala.concurrent.Future

import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import org.scalatest.EitherValues

import org.alephium.rpc.model._
import org.alephium.util.AlephiumSpec

class JsonRPCHandlerSpec extends AlephiumSpec with ScalatestRouteTest with EitherValues {
  it should "route http" in {
    val response = JsonRPC.Response.Success(Json.fromInt(42), 1)

    val handler = Map("foo" -> { _: JsonRPC.Request =>
      Future.successful(response)
    })
    val route = JsonRPCHandler.routeHttp(handler)

    val jsonRequest = CirceUtils.print(JsonRPC.Request("foo", JsonObject.empty.asJson, 1).asJson)
    val entity      = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    val httpRequest = HttpRequest(HttpMethods.POST, entity = entity)

    httpRequest ~> route ~> check {
      status is StatusCodes.OK
      val success = responseAs[JsonRPC.Response.Success]
      success is response
    }
  }
}
