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

package org.alephium.app

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}

import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.util.{AlephiumSpec, SocketUtil}

class ServerSpec extends AlephiumSpec with ScalaFutures with SocketUtil {
  implicit override val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

  it should "start and stop correctly" in new AlephiumConfigFixture with RandomPortsConfigFixture {
    override val configValues = configPortsValues

    implicit val apiConfig: ApiConfig = ApiConfig.load(newConfig)

    val flowSystem: ActorSystem = ActorSystem("flow", newConfig)
    implicit val executionContext: ExecutionContext =
      scala.concurrent.ExecutionContext.Implicits.global

    val server = Server(rootPath, flowSystem)

    server.start().futureValue is ()
    server.stop().futureValue is ()
  }
}
