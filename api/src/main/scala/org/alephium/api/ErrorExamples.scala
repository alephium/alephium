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

import sttp.tapir.EndpointIO.Example

trait ErrorExamples extends Examples {

  implicit val badRequestExamples: List[Example[ApiError.BadRequest]] =
    simpleExample(ApiError.BadRequest("Something bad in the request"))

  implicit val notFoundExamples: List[Example[ApiError.NotFound]] =
    simpleExample(ApiError.NotFound("wallet-name"))

  implicit val internalServerErrorExamples: List[Example[ApiError.InternalServerError]] =
    simpleExample(ApiError.InternalServerError("Ouch"))

  implicit val unauthorizedExamples: List[Example[ApiError.Unauthorized]] =
    simpleExample(ApiError.Unauthorized("You shall not pass"))

  implicit val serviceUnavailableExamples: List[Example[ApiError.ServiceUnavailable]] =
    simpleExample(ApiError.ServiceUnavailable("Self clique unsynced"))
}
