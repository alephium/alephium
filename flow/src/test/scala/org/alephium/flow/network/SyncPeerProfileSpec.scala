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

import org.alephium.protocol.model.{NetworkId, ReleaseVersion}
import org.alephium.util.{AlephiumSpec, Duration}

class SyncPeerProfileSpec extends AlephiumSpec {
  it should "use 2048 blocks / 15s for fast mainnet peers" in {
    SyncPeerProfile.Fast.blocksPerWindow is FastBlocksPerWindow
    SyncPeerProfile.Fast.windowSize(NetworkId.AlephiumMainNet) is FastBlocksWindowSizeMainnet
    FastBlocksWindowSizeMainnet is Duration.ofSecondsUnsafe(15)
  }

  it should "keep the 30s window for legacy mainnet peers" in {
    SyncPeerProfile.Legacy.blocksPerWindow is LegacyBlocksPerWindow
    SyncPeerProfile.Legacy.windowSize(NetworkId.AlephiumMainNet) is RateLimiterWindowSizeMainnet
  }

  it should "use fixed windows for legacy peers and sliding windows for fast peers" in {
    SyncPeerProfile.Legacy.newBlockRateLimiter(NetworkId.AlephiumMainNet) is
      a[FixedWindowRateLimiter]
    SyncPeerProfile.Fast.newBlockRateLimiter(NetworkId.AlephiumMainNet) is
      a[SlidingWindowRateLimiter]
  }

  it should "keep the shorter non-mainnet window for both profiles" in {
    SyncPeerProfile.Fast.windowSize(NetworkId.AlephiumDevNet) is RateLimiterWindowSize
    SyncPeerProfile.Legacy.windowSize(NetworkId.AlephiumDevNet) is RateLimiterWindowSize
    SyncPeerProfile.Fast.windowSize(NetworkId.AlephiumTestNet) is RateLimiterWindowSize
  }

  it should "select fast from 4.7.0+" in {
    SyncPeerProfile.fromReleaseVersion(Some(ReleaseVersion(4, 7, 0))) is SyncPeerProfile.Fast
    SyncPeerProfile.fromReleaseVersion(Some(ReleaseVersion(4, 6, 0))) is SyncPeerProfile.Legacy
    SyncPeerProfile.fromReleaseVersion(None) is SyncPeerProfile.Legacy
  }
}
