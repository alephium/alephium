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
