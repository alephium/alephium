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

import org.alephium.protocol.{PrivateKey, PublicKey}
import org.alephium.util.Duration

// TODO: refactor this as two configs
trait DiscoveryConfig {
  def discoveryPrivateKey: PrivateKey

  def discoveryPublicKey: PublicKey

  /* Maximum number of peers to track. */
  def peersPerGroup: Int

  /* Maximum number of peers used for probing during a scan. */
  def scanMaxPerGroup: Int

  /* Wait time between two scan. */
  def scanFrequency: Duration

  def scanFastFrequency: Duration

  /* Maximum number of peers returned from a query (`k` in original kademlia paper). */
  def neighborsPerGroup: Int

  /** Duration we wait before considering a peer dead. **/
  lazy val peersTimeout: Duration = scanFrequency.timesUnsafe(3)

  val expireDuration: Duration = Duration.ofHoursUnsafe(1)
}
