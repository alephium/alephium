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

package org.alephium.api.model

import org.alephium.protocol.vm
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.Hex

final case class Script(value: String) extends AnyVal {
  def toProtocol(): Either[String, vm.StatefulScript] = {
    for {
      bytes  <- Hex.from(value).toRight("Invalid script hex string")
      script <- deserialize[vm.StatefulScript](bytes).left.map(_.getMessage)
    } yield script
  }
}

object Script {
  def fromProtocol(script: vm.StatefulScript): Script = {
    Script(
      Hex.toHexString(serialize(script))
    )
  }
}
