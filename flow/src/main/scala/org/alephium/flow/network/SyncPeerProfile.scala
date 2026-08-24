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

package org.alephium.flow.network

import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.ReleaseVersion

sealed trait SyncPeerProfile {
  def blocksPerWindow: Int
}

object SyncPeerProfile {
  case object Legacy extends SyncPeerProfile {
    override val blocksPerWindow: Int = LegacyBlocksPerWindow
  }

  case object Fast extends SyncPeerProfile {
    override val blocksPerWindow: Int = FastBlocksPerWindow
  }

  // The first release that supports the larger block request window in both directions.
  val FastSyncMinVersion: ReleaseVersion = ReleaseVersion(4, 7, 0)

  def fromReleaseVersion(releaseVersion: Option[ReleaseVersion]): SyncPeerProfile = {
    if (releaseVersion.exists(_ >= FastSyncMinVersion)) Fast else Legacy
  }

  def fromClientId(clientId: String)(implicit networkConfig: NetworkConfig): SyncPeerProfile = {
    fromReleaseVersion(ReleaseVersion.fromClientId(clientId))
  }
}
