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

import org.alephium.protocol.PublicKey
import org.alephium.protocol.model.TxOutput
import org.alephium.serde._

//scalastyle:off magic.number

trait GasSchedule {
  protected def typeHint(): Unit // for type safety
}

trait GasSimple extends GasSchedule {
  protected def typeHint(): Unit = ()

  def gas: Int
}

trait GasFormula extends GasSchedule {
  protected def typeHint(): Unit = ()

  def gas(size: Int): Int
}

trait GasZero extends GasSimple {
  def gas: Int = GasZero.gas
}
object GasZero {
  val gas: Int = 0
}

trait GasVeryLow extends GasSimple {
  def gas: Int = GasVeryLow.gas
}
object GasVeryLow {
  val gas: Int = 3
}

trait GasLow extends GasSimple {
  def gas: Int = GasLow.gas
}
object GasLow {
  val gas: Int = 5
}

trait GasMid extends GasSimple {
  def gas: Int = GasMid.gas
}
object GasMid {
  val gas: Int = 8
}

trait GasHigh extends GasSimple {
  def gas: Int = GasHigh.gas
}
object GasHigh {
  val gas: Int = 10
}

trait GasHash extends GasFormula {
  def gas(inputLength: Int): Int = GasHash.gas(inputLength)
}
object GasHash {
  val baseGas: Int         = 30
  val extraGasPerWord: Int = 6

  def gas(inputLength: Int): Int =
    GasHash.baseGas + GasHash.extraGasPerWord * ((inputLength + 3) / 8)
}

trait GasSignature extends GasSimple {
  def gas: Int = GasSignature.gas
}
object GasSignature {
  val gas: Int = 10000 // TODO: bench this
}

trait GasCreate extends GasSimple {
  def gas: Int = GasCreate.gas
}
object GasCreate {
  val gas: Int = 32000
}

trait GasBalance extends GasSimple {
  def gas: Int = GasBalance.gas
}
object GasBalance {
  val gas: Int = 50
}

trait GasCall extends GasFormula {
  def gas(size: Int): Int = ??? // should call the companion object instead
}

object GasSchedule {
  val callGas: Int           = 200
  val contractLoadGas: Int   = 800
  val contractUpdateGas: Int = 5000

  val trieRemovalGas: Int = 4000
  val trieUpdateGas: Int  = 5000

  /*
   * The gas cost of a transaction consists of 4 parts
   * 1. a fixed base gas for each transaction
   * 2. gas for each input including the auto generated contract inputs:
   *    2.1. gas for removing the input from the blockchain state trie
   *    2.2. data gas based on the length of the serialized input
   *    2.3. execution gas for the unlockup script of the input
   * 3. gas for each output including the auto generated vm outputs:
   *    3.1. gas for adding the output into the blockchain state trie
   *    3.2. data gas based on the length of the serialized output
   * 4. execution gas for the optional tx script
   */
  val txBaseGas: Int = 4000
  val txDataGas: Int = 68

  def inputGas(theOutputOfInput: TxOutput, unlockGas: Int): Int = {
    trieRemovalGas + txDataGas * serialize(theOutputOfInput).length + unlockGas
  }

  def outputGas(txOutput: TxOutput): Int = {
    trieUpdateGas + txDataGas * serialize(txOutput).length
  }

  val p2pkUnlockGas: Int = {
    GasHash.gas(PublicKey.length) + GasSignature.gas
  }
}
