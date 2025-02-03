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

import org.alephium.protocol.ALPH
import org.alephium.util.AlephiumActorSpec

class ConfigTest extends AlephiumActorSpec {
  it should "load testnet genesis" in new CliqueFixture {
    val clique    = bootClique(nbOfNodes = 1)
    val theConfig = clique.servers.head.config
    theConfig.genesisBlocks(0)(0).coinbase.outputsLength is 1
    theConfig.genesisBlocks(1)(1).coinbase.outputsLength is 2
    theConfig.genesisBlocks(2)(2).coinbase.outputsLength is 1
    theConfig.genesisBlocks(3)(3).coinbase.outputsLength is 2

    val specialTx = theConfig.genesisBlocks(3)(3).coinbase
    specialTx.unsigned.fixedOutputs.head.lockTime is ALPH.LaunchTimestamp
    specialTx.unsigned.fixedOutputs.last.lockTime is ALPH.LaunchTimestamp.plusHoursUnsafe(3 * 24)
  }
}
