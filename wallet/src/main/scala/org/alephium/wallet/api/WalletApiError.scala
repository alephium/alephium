package org.alephium.wallet.api

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.SchemaType.{SObjectInfo, SProduct}

sealed trait WalletApiError {
  def status: StatusCode
  def detail: String
}

object WalletApiError {

  private def encodeApiError[A <: WalletApiError]: Encoder[A] = new Encoder[A] {
    final def apply(apiError: A): Json = Json.obj(
      ("status", Json.fromInt(apiError.status.code)),
      ("detail", Json.fromString(apiError.detail))
    )
  }

  final case class Unauthorized(val detail: String) extends WalletApiError {
    final val status: StatusCode = StatusCode.Unauthorized
  }

  object Unauthorized {
    implicit val encoder: Encoder[Unauthorized] = new Encoder[Unauthorized] {
      val baseEncoder = deriveEncoder[Unauthorized]
      final def apply(unauthorized: Unauthorized): Json =
        encodeApiError[Unauthorized](unauthorized).deepMerge(baseEncoder(unauthorized))
    }

    implicit val decoder: Decoder[Unauthorized] = deriveDecoder
    implicit val schema: Schema[Unauthorized] =
      Schema(
        SProduct(SObjectInfo("Unauthorized"),
                 List("status" -> Schema.schemaForInt, "detail" -> Schema.schemaForString)))
  }

  final case class BadRequest(val detail: String) extends WalletApiError {
    final val status: StatusCode = StatusCode.BadRequest
  }
  object BadRequest {
    implicit val encoder: Encoder[BadRequest] = new Encoder[BadRequest] {
      val baseEncoder = deriveEncoder[BadRequest]
      final def apply(badRequest: BadRequest): Json =
        encodeApiError[BadRequest](badRequest).deepMerge(baseEncoder(badRequest))
    }
    implicit val decoder: Decoder[BadRequest] = deriveDecoder
    implicit val schema: Schema[BadRequest] =
      Schema(
        SProduct(SObjectInfo("BadRequest"),
                 List("status" -> Schema.schemaForInt, "detail" -> Schema.schemaForString)))
  }

  implicit val decoder: Decoder[WalletApiError] = new Decoder[WalletApiError] {
    def dec(c: HCursor, status: StatusCode): Decoder.Result[WalletApiError] = status match {
      case StatusCode.BadRequest   => BadRequest.decoder(c)
      case StatusCode.Unauthorized => Unauthorized.decoder(c)
      case _                       => Left(DecodingFailure(s"$status not supported", c.history))
    }
    final def apply(c: HCursor): Decoder.Result[WalletApiError] =
      for {
        statusAsInt <- c.downField("status").as[Int]
        apiError    <- dec(c, StatusCode(statusAsInt))
      } yield apiError
  }

  implicit val encoder: Encoder[WalletApiError] = new Encoder[WalletApiError] {
    final def apply(apiError: WalletApiError): Json = apiError match {
      case badRequest: BadRequest     => BadRequest.encoder(badRequest)
      case unauthorized: Unauthorized => Unauthorized.encoder(unauthorized)
      case _                          => encodeApiError[WalletApiError](apiError)
    }
  }

  @SuppressWarnings(
    Array("org.wartremover.warts.JavaSerializable",
          "org.wartremover.warts.Product",
          "org.wartremover.warts.Serializable"))
  implicit val schema: Schema[WalletApiError] =
    Schema.oneOf[WalletApiError, StatusCode](_.status, _.toString)(
      StatusCode.BadRequest   -> BadRequest.schema,
      StatusCode.Unauthorized -> Unauthorized.schema
    )
}
