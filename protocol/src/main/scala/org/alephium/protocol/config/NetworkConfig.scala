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

package org.alephium.protocol.config

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{HardFork, NetworkId}
import org.alephium.util.{Bytes, Duration, TimeStamp}

trait NetworkConfig {
  def networkId: NetworkId

  lazy val magicBytes: ByteString =
    Bytes.from(Hash.hash(s"alephium-${networkId.id}").toRandomIntUnsafe)

  def noPreMineProof: ByteString

  // scalastyle:off magic.number
  lazy val coinbaseLockupPeriod: Duration = networkId match {
    case NetworkId.AlephiumMainNet => Duration.ofMinutesUnsafe(500)
    case _                         => Duration.ofMinutesUnsafe(10)
  }
  // scalastyle:on magic.number

  def lemanHardForkTimestamp: TimeStamp

  def getHardFork(timeStamp: TimeStamp): HardFork = {
    if (timeStamp >= lemanHardForkTimestamp) {
      HardFork.Leman
    } else {
      HardFork.Mainnet
    }
  }
}
