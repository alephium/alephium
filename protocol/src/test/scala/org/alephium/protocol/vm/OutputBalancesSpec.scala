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

import scala.collection.mutable

import akka.util.ByteString

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{CompilerConfig, GroupConfig, NetworkConfigFixture}
import org.alephium.protocol.model.{dustUtxoAmount, AssetOutput, TxGenerators}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class OutputBalancesSpec extends AlephiumSpec {
  it should "addLockedAlph" in new Fixture {
    outputBalances.addLockedAlph(lockupScript0, ALPH.oneAlph, timestamp0)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances.alph(ALPH.oneAlph, timestamp0)
    )

    outputBalances.addLockedAlph(lockupScript0, ALPH.oneAlph, timestamp1)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances(
        mutable.LinkedHashMap(
          timestamp0 -> LockedAsset.alph(ALPH.oneAlph),
          timestamp1 -> LockedAsset.alph(ALPH.oneAlph)
        )
      )
    )

    outputBalances.addLockedAlph(lockupScript0, ALPH.oneAlph, timestamp0)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances(
        mutable.LinkedHashMap(
          timestamp0 -> LockedAsset.alph(ALPH.alph(2)),
          timestamp1 -> LockedAsset.alph(ALPH.oneAlph)
        )
      )
    )

    outputBalances.addLockedAlph(lockupScript1, ALPH.oneAlph, timestamp0)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances(
        mutable.LinkedHashMap(
          timestamp0 -> LockedAsset.alph(ALPH.alph(2)),
          timestamp1 -> LockedAsset.alph(ALPH.oneAlph)
        )
      ),
      lockupScript1 -> LockedBalances.alph(ALPH.oneAlph, timestamp0)
    )
  }

  it should "addLockedToken" in new Fixture {
    outputBalances.addLockedToken(lockupScript0, tokenId0, ALPH.oneAlph, timestamp0)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances.token(tokenId0, ALPH.oneAlph, timestamp0)
    )

    outputBalances.addLockedToken(lockupScript0, tokenId1, ALPH.oneAlph, timestamp1)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances(
        mutable.LinkedHashMap(
          timestamp0 -> LockedAsset.token(tokenId0, ALPH.oneAlph),
          timestamp1 -> LockedAsset.token(tokenId1, ALPH.oneAlph)
        )
      )
    )

    outputBalances.addLockedToken(lockupScript1, tokenId1, ALPH.oneAlph, timestamp0)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances(
        mutable.LinkedHashMap(
          timestamp0 -> LockedAsset.token(tokenId0, ALPH.oneAlph),
          timestamp1 -> LockedAsset.token(tokenId1, ALPH.oneAlph)
        )
      ),
      lockupScript1 -> LockedBalances.token(tokenId1, ALPH.oneAlph, timestamp0)
    )

    outputBalances.addLockedToken(lockupScript0, tokenId0, ALPH.oneAlph, timestamp0)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances(
        mutable.LinkedHashMap(
          timestamp0 -> LockedAsset.token(tokenId0, ALPH.alph(2)),
          timestamp1 -> LockedAsset.token(tokenId1, ALPH.oneAlph)
        )
      ),
      lockupScript1 -> LockedBalances.token(tokenId1, ALPH.oneAlph, timestamp0)
    )

    outputBalances.addLockedAlph(lockupScript0, ALPH.oneAlph, timestamp1)
    outputBalances.locked is mutable.ArrayBuffer(
      lockupScript0 -> LockedBalances(
        mutable.LinkedHashMap(
          timestamp0 -> LockedAsset.token(tokenId0, ALPH.alph(2)),
          timestamp1 -> LockedAsset(
            Some(ALPH.oneAlph),
            mutable.LinkedHashMap(
              tokenId1 -> ALPH.oneAlph
            )
          )
        )
      ),
      lockupScript1 -> LockedBalances.token(tokenId1, ALPH.oneAlph, timestamp0)
    )
  }

  it should "toTxOutput" in new Fixture {
    val lockedAsset0 = LockedAsset.token(tokenId0, ALPH.oneAlph)
    lockedAsset0.toTxOutput(lockupScript0, timestamp0) is AssetOutput(
      dustUtxoAmount,
      lockupScript0,
      timestamp0,
      AVector(tokenId0 -> ALPH.oneAlph),
      ByteString.empty
    )

    val lockedAsset1 =
      LockedAsset(Some(ALPH.oneAlph), mutable.LinkedHashMap(tokenId1 -> ALPH.oneAlph))
    lockedAsset1.toTxOutput(lockupScript1, timestamp1) is AssetOutput(
      ALPH.oneAlph,
      lockupScript1,
      timestamp1,
      AVector(tokenId1 -> ALPH.oneAlph),
      ByteString.empty
    )
  }

  trait Fixture extends TxGenerators with NetworkConfigFixture.Default {
    implicit override val compilerConfig: CompilerConfig =
      new CompilerConfig {
        override def loopUnrollingLimit: Int = 1000
      }

    implicit override val groupConfig: GroupConfig =
      new GroupConfig {
        override def groups: Int = 3
      }

    val assetLockupScriptGen = for {
      groupIndex   <- groupIndexGen
      lockupScript <- assetLockupGen(groupIndex)
    } yield lockupScript

    val timestamp0    = TimeStamp.unsafe(1000)
    val timestamp1    = TimeStamp.unsafe(2000)
    val tokenId0      = hashGen.sample.get
    val tokenId1      = hashGen.sample.get
    val lockupScript0 = assetLockupScriptGen.sample.get
    val lockupScript1 = assetLockupScriptGen.sample.get

    val outputBalances = OutputBalances.empty
  }
}
