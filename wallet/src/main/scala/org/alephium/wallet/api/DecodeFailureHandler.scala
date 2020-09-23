package org.alephium.wallet.api

import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.{DecodeFailureContext, DecodeFailureHandling, ServerDefaults}

import org.alephium.wallet.api.WalletApiError

trait DecodeFailureHandler {

  private val failureOutput: EndpointOutput[(StatusCode, WalletApiError)] =
    statusCode.and(jsonBody[WalletApiError])

  private def myFailureResponse(statusCode: StatusCode, message: String): DecodeFailureHandling =
    DecodeFailureHandling.response(failureOutput)(
      (statusCode, WalletApiError.BadRequest(message))
    )

  private def myFailureMessage(ctx: DecodeFailureContext): String = {
    val base = ServerDefaults.FailureMessages.failureSourceMessage(ctx.input)

    val detail = ctx.failure match {
      case DecodeResult.InvalidValue(errors) if errors.nonEmpty =>
        Some(ServerDefaults.ValidationMessages.validationErrorsMessage(errors))
      case DecodeResult.Error(original, error) => Some(s"${error.getMessage}: $original")
      case _                                   => None
    }

    ServerDefaults.FailureMessages.combineSourceAndDetail(base, detail)
  }
  val myDecodeFailureHandler = ServerDefaults.decodeFailureHandler.copy(
    response = myFailureResponse,
    respondWithStatusCode = ServerDefaults.FailureHandling
      .respondWithStatusCode(_,
                             badRequestOnPathErrorIfPathShapeMatches   = true,
                             badRequestOnPathInvalidIfPathShapeMatches = true),
    failureMessage = myFailureMessage
  )

}
