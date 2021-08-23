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

package org.alephium.protocol.message

import org.alephium.protocol.WireVersion
import org.alephium.serde._
import org.alephium.util.AlephiumSpec

class HeaderSpec extends AlephiumSpec {
  it should "serialize/deserialize the Header when version compatible" in {
    val header = Header(WireVersion.currentWireVersion)
    val bytes  = serialize(header)
    deserialize[Header](bytes) isE header
  }

  it should "deserialize failed when version not compatible" in {
    val invalidVersion = WireVersion(WireVersion.currentWireVersion.value + 1)
    val bytes          = serialize(Header(invalidVersion))
    deserialize[Header](bytes).leftValue is a[SerdeError]
  }
}
