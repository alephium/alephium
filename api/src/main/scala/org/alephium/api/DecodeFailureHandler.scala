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

package org.alephium.api

import sttp.tapir._
import sttp.tapir.server._

import org.alephium.api.{alfJsonBody, ApiError}

trait DecodeFailureHandler {

  private def myFailureResponse(
      response: DefaultDecodeFailureResponse,
      message: String
  ): DecodeFailureHandling =
    DecodeFailureHandling.response(ServerDefaults.failureOutput(alfJsonBody[ApiError.BadRequest]))(
      (response, ApiError.BadRequest(message))
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
    respond = ServerDefaults.FailureHandling
      .respond(
        _,
        badRequestOnPathErrorIfPathShapeMatches = true,
        badRequestOnPathInvalidIfPathShapeMatches = true
      ),
    failureMessage = myFailureMessage
  )

}
