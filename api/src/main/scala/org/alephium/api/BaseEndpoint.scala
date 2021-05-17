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

import com.typesafe.scalalogging.StrictLogging
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemasLike

trait BaseEndpoint extends ErrorExamples with TapirCodecs with TapirSchemasLike with StrictLogging {
  import Endpoints._

  type BaseEndpoint[A, B] = Endpoint[A, ApiError[_ <: StatusCode], B, Any]

  val baseEndpoint: BaseEndpoint[Unit, Unit] =
    endpoint
      .errorOut(
        oneOf[ApiError[_ <: StatusCode]](
          error(ApiError.BadRequest),
          error(ApiError.InternalServerError),
          error(ApiError.NotFound),
          error(ApiError.ServiceUnavailable),
          error(ApiError.Unauthorized)
        )
      )
}
