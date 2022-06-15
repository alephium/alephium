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

import sttp.model.{Header, StatusCode}
import sttp.tapir._
import sttp.tapir.server.interceptor._
import sttp.tapir.server.interceptor.decodefailure.DefaultDecodeFailureHandler._
import sttp.tapir.server.model.ValuedEndpointOutput

import org.alephium.api.{alphJsonBody, ApiError}

trait DecodeFailureHandler {

  def failureResponse(c: StatusCode, hs: List[Header], m: String): ValuedEndpointOutput[_] = {
    ValuedEndpointOutput(
      statusCode.and(headers).and(alphJsonBody[ApiError.BadRequest]),
      (c, hs, ApiError.BadRequest(m))
    )
  }

  def failureMessage(ctx: DecodeFailureContext): String = {
    val base = FailureMessages.failureSourceMessage(ctx.failingInput)

    val detail = ctx.failure match {
      case DecodeResult.InvalidValue(errors) if errors.nonEmpty =>
        Some(ValidationMessages.validationErrorsMessage(errors))
      case DecodeResult.Error(original, error) => Some(s"${error.getMessage}: $original")
      case _                                   => None
    }

    FailureMessages.combineSourceAndDetail(base, detail)
  }

  val myDecodeFailureHandler =
    default.copy(
      response = failureResponse,
      respond = respond(
        _,
        badRequestOnPathErrorIfPathShapeMatches = true,
        badRequestOnPathInvalidIfPathShapeMatches = true
      ),
      failureMessage = failureMessage
    )
}
