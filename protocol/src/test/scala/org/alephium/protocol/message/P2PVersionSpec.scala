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

class P2PVersionSpec extends AlephiumSpec {
  it should "parse protocol version from client id" in {
    P2PVersion.fromClientId("scala-alephium/v1.0.0/Linux") is Some(P2PV1)
    P2PVersion.fromClientId("scala-alephium/v1.0.0/Linux/p2p-v1") is Some(P2PV1)
    P2PVersion.fromClientId("scala-alephium/v1.0.0/Linux/p2p-v2") is Some(P2PV2)
    P2PVersion.fromClientId("scala-alephium/v2.5.1/Linux") is Some(P2PV1)
    P2PVersion.fromClientId("scala-alephium/v3.12.0/Linux") is Some(P2PV1)
    P2PVersion.fromClientId("scala-alephium/v1.0.0/Linux/p2p-v3") is None
    P2PVersion.fromClientId("scala-alephium/v1.0/Linux") is Some(P2PV1)
    P2PVersion.fromClientId("scala-alephium/v1.0.0.0/Linux") is Some(P2PV1)
    P2PVersion.fromClientId("scala-alephium/v1.a.0/Linux") is Some(P2PV1)
    P2PVersion.fromClientId("scala-alephium/v1.0.0/Linux/p2p-v2/more") is None
    P2PVersion.fromClientId("") is None
    P2PVersion.fromClientId("invalid") is None
    P2PVersion.fromClientId("scala-alephium/v1.0.0") is None
    P2PVersion.fromClientId("scala-alephium/v1.0.0/Linux/invalid") is None
  }
}
