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

import org.alephium.macros.Gas
import org.alephium.protocol.PublicKey

//scalastyle:off magic.number

trait GasSchedule {
  protected def typeHint(): Unit // for type safety
}

trait GasSimple extends GasSchedule {
  protected def typeHint(): Unit = ()

  def gas(): GasBox
}

trait GasFormula extends GasSchedule {
  protected def typeHint(): Unit = ()

  def gas(size: Int): GasBox
}

@Gas
trait GasZero extends GasSimple
object GasZero {
  val gas: GasBox = GasBox.unsafe(0)
}

@Gas
trait GasVeryLow extends GasSimple
object GasVeryLow {
  val gas: GasBox = GasBox.unsafe(3)
}

@Gas
trait GasLow extends GasSimple
object GasLow {
  val gas: GasBox = GasBox.unsafe(5)
}

@Gas
trait GasMid extends GasSimple
object GasMid {
  val gas: GasBox = GasBox.unsafe(8)
}

@Gas
trait GasHigh extends GasSimple
object GasHigh {
  val gas: GasBox = GasBox.unsafe(10)
}

trait GasToByte extends GasFormula {
  def gas(inputLength: Int): GasBox = GasToByte.gas(inputLength)
}
object GasToByte {
  val baseGas: Int         = 3
  val extraGasPerWord: Int = 2

  def gas(inputLength: Int): GasBox =
    GasBox.unsafe(baseGas + extraGasPerWord * ((inputLength + 3) / 8))
}

trait GasHash extends GasFormula {
  def gas(inputLength: Int): GasBox = GasHash.gas(inputLength)
}
object GasHash {
  val baseGas: Int         = 30
  val extraGasPerWord: Int = 6

  def gas(inputLength: Int): GasBox =
    GasBox.unsafe(GasHash.baseGas + GasHash.extraGasPerWord * ((inputLength + 3) / 8))
}

@Gas
trait GasSignature extends GasSimple
object GasSignature {
  val gas: GasBox = GasBox.unsafe(2000)
}

@Gas
trait GasCreate extends GasSimple
object GasCreate {
  val gas: GasBox = GasBox.unsafe(32000)
}

@Gas
trait GasDestroy extends GasSimple
object GasDestroy {
  val gas: GasBox = GasBox.unsafe(10000)
}

@Gas
trait GasBalance extends GasSimple
object GasBalance {
  val gas: GasBox = GasBox.unsafe(50)
}

trait GasCall extends GasFormula {
  def gas(size: Int): GasBox = ??? // should call the companion object instead
}

object GasSchedule {
  val callGas: GasBox           = GasBox.unsafe(200)
  val contractLoadGas: GasBox   = GasBox.unsafe(1000)
  val contractUpdateGas: GasBox = GasBox.unsafe(5000)

  /*
   * The gas cost of a transaction consists of 4 parts
   * 1. a fixed base gas for each transaction
   * 2. gas for each input including the auto generated contract inputs:
   *    2.1. gas for removing the input from the blockchain state trie
   *    2.2. data gas based on the length of the serialized input
   *    2.3. execution gas for the unlock script of the input
   * 3. gas for each output including the auto generated vm outputs:
   *    3.1. gas for adding the output into the blockchain state trie
   *    3.2. data gas based on the length of the serialized output
   * 4. execution gas for the optional tx script
   */
  val txBaseGas: GasBox       = GasBox.unsafe(1000)
  val txInputBaseGas: GasBox  = GasBox.unsafe(2000)
  val txOutputBaseGas: GasBox = GasBox.unsafe(4500)

  val p2pkUnlockGas: GasBox = {
    GasBox.unsafe(GasHash.gas(PublicKey.length).value + GasSignature.gas.value)
  }
}
