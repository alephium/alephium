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

package org.alephium.protocol.vm

import org.alephium.protocol.model.defaultGasPerInput
import org.alephium.util.AlephiumSpec

class GasScheduleSpec extends AlephiumSpec {
  it should "validate default gases" in {
    (defaultGasPerInput >= GasSchedule.secp256K1UnlockGas) is true
  }

  it should "charge gas for hash" in {
    GasHash.gas(33) is GasBox.unsafe(60)
  }

  it should "charge gas for unlock" in {
    GasSchedule.secp256K1UnlockGas is GasBox.unsafe(60 + 2000)
    GasSchedule.secp256R1UnlockGas is GasBox.unsafe(60 + 2000)
    GasSchedule.ed25519UnlockGas is GasBox.unsafe(54 + 2000)
    GasSchedule.webauthnUnlockGas(100) is GasBox.unsafe(132 + 2000)
  }
}
