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

package org.alephium.http

import scala.concurrent.Future

import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor

import org.alephium.util.AlephiumSpec

class ServerOptionsSpec extends AlephiumSpec {
  "ServerOptions" should "enable metrics based on the config" in {
    def checkMetrics(enableMetrics: Boolean) = {
      ServerOptions
        .serverOptions(enableMetrics)
        .interceptors
        .exists(
          _.isInstanceOf[MetricsRequestInterceptor[Future]]
        ) is enableMetrics
    }

    checkMetrics(true)
    checkMetrics(false)
  }
}
