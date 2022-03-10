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

package org.alephium.tools

import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.api.model.ApiKey
import org.alephium.app.Documentation
import org.alephium.protocol.config.GroupConfig
import org.alephium.wallet.WalletDocumentation

@SuppressWarnings(Array("org.wartremover.warts.GlobalExecutionContext"))
object OpenApiUpdate extends App {

  val wallet: WalletDocumentation = new WalletDocumentation {
    override val maybeApiKey: Option[ApiKey] = None

    implicit override val groupConfig: GroupConfig =
      new GroupConfig {
        override def groups: Int = 4
      }
  }

  new Documentation {
    override val port                        = 12973
    override val maybeApiKey: Option[ApiKey] = None
    override val walletEndpoints             = wallet.walletEndpoints
    implicit override val groupConfig: GroupConfig =
      new GroupConfig {
        override def groups: Int = 4
      }

    private val json = openApiJson(openAPI, dropAuth = maybeApiKey.isEmpty)

    import java.io.PrintWriter
    new PrintWriter("../api/src/main/resources/openapi.json") { write(json); close() }
  }
}
