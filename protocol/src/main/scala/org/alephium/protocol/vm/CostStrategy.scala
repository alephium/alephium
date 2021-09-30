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

trait CostStrategy {
  var gasRemaining: GasBox

  def chargeGas(instr: GasSimple): ExeResult[Unit] = chargeGas(instr.gas())

  def chargeGasWithSize(instr: GasFormula, size: Int): ExeResult[Unit] = {
    chargeGas(instr.gas(size))
  }

  def chargeContractLoad(obj: StatefulContractObject): ExeResult[Unit] = {
    chargeGas(GasSchedule.contractLoadGas)
  }

  def chargeContractUpdate(obj: StatefulContractObject): ExeResult[Unit] = {
    chargeGas(GasSchedule.contractUpdateGas)
  }

  def chargeContractInput(): ExeResult[Unit] = chargeGas(GasSchedule.txInputBaseGas)

  def chargeGeneratedOutput(): ExeResult[Unit] = chargeGas(GasSchedule.txOutputBaseGas)

  def chargeGas(gas: GasBox): ExeResult[Unit] = {
    gasRemaining.use(gas).map(gasRemaining = _)
  }
}
