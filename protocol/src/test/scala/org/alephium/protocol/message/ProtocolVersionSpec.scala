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

import org.alephium.util.AlephiumSpec

class ProtocolVersionSpec extends AlephiumSpec {
  it should "parse protocol version from client id" in {
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0/Linux") is Some(ProtocolV1)
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0/Linux/sync-v1") is Some(ProtocolV1)
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0/Linux/sync-v2") is Some(ProtocolV2)
    ProtocolVersion.fromClientId("scala-alephium/v2.5.1/Linux") is Some(ProtocolV1)
    ProtocolVersion.fromClientId("scala-alephium/v3.12.0/Linux") is Some(ProtocolV1)
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0/Linux/sync-v3") is None
    ProtocolVersion.fromClientId("scala-alephium/v1.0/Linux") is Some(ProtocolV1)
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0.0/Linux") is Some(ProtocolV1)
    ProtocolVersion.fromClientId("scala-alephium/v1.a.0/Linux") is Some(ProtocolV1)
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0/Linux/sync-v2/more") is None
    ProtocolVersion.fromClientId("") is None
    ProtocolVersion.fromClientId("invalid") is None
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0") is None
    ProtocolVersion.fromClientId("scala-alephium/v1.0.0/Linux/invalid") is None
  }
}
