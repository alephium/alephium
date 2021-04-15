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

package org.alephium.flow.core

import akka.util.ByteString

import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, PersistedOutput}
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._

// scalastyle:off number.of.methods
class UtxoUtilsSpec extends AlephiumSpec with LockupScriptGenerators {

  implicit val groupConfig = new GroupConfig {
    override def groups: Int = 2
  }

  it should "return the matching utxo when exists" in new Fixture {
    val utxos = buildUtxos(2, 1, 3)

    UtxoUtils.select(utxos, U256.unsafe(1)) is Right(AVector(utxos(1)))
  }

  it should "return everything in case of wallet dump" in new Fixture {
    val utxos = buildUtxos(1, 2, 3)

    UtxoUtils.select(utxos, U256.unsafe(6)) is Right(AVector(utxos(0), utxos(1), utxos(2)))
  }

  it should "return all the smallers value if their sum match the target" in new Fixture {
    val utxos = buildUtxos(1, 2, 3, 7, 8, 9)

    UtxoUtils.select(utxos, U256.unsafe(6)) is Right(AVector(utxos(0), utxos(1), utxos(2)))
  }

  it should "return the smallest greater if the sum of the smallest is less than the target" in new Fixture {
    val utxos = buildUtxos(1, 2, 3, 8, 9)

    UtxoUtils.select(utxos, U256.unsafe(7)) is Right(AVector(utxos(3)))
  }

  it should "return error if not enough amount" in new Fixture {
    val utxos = buildUtxos(1, 2, 3)

    UtxoUtils.select(utxos, U256.unsafe(7)) is Left("Not enough balance")
  }

  it should "return the minimum set of smallers value if their sum is bigger than the match" in new Fixture {
    val utxos = buildUtxos(1, 2, 3, 7, 8, 9)

    UtxoUtils.select(utxos, U256.unsafe(5)) is Right(AVector(utxos(1), utxos(2)))
  }

  trait Fixture extends AlephiumConfigFixture {

    def buildOutput(lockupScript: LockupScript, amount: U256): AssetOutputInfo = {
      val output =
        AssetOutput(amount, lockupScript, TimeStamp.now(), AVector.empty, ByteString.empty)
      val ref = AssetOutputRef.unsafe(Hint.from(output), Hash.generate)
      AssetOutputInfo(ref, output, PersistedOutput)
    }

    val defaultLockupScript = p2pkhLockupGen(GroupIndex.unsafe(0)).sample.get

    def buildUtxos(amounts: Int*): AVector[AssetOutputInfo] = {
      AVector.from(amounts.map { amount => buildOutput(defaultLockupScript, U256.unsafe(amount)) })
    }
  }
}
