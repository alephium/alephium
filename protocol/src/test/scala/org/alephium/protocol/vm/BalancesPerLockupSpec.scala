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

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{CompilerConfig, GroupConfig, NetworkConfigFixture}
import org.alephium.protocol.model.{TxGenerators, TxOutput}
import org.alephium.util.{AlephiumSpec, AVector, U256}
import org.alephium.util.Bytes.byteStringOrdering

class BalancesPerLockupSpec extends AlephiumSpec {

  it should "tokenVector" in new Fixture {
    val tokens = mutable.Map((tokenId -> ALPH.oneAlph))
    BalancesPerLockup(ALPH.oneAlph, tokens, 1).tokenVector is AVector((tokenId, ALPH.oneAlph))

    val tokenIdZero = hashGen.sample.get
    tokens.addOne((tokenIdZero, U256.Zero))

    BalancesPerLockup(ALPH.oneAlph, tokens, 1).tokenVector is AVector((tokenId, ALPH.oneAlph))

    tokens.remove(tokenIdZero)

    forAll(hashGen) { newTokenId =>
      tokens.addOne((newTokenId, ALPH.oneAlph))
      BalancesPerLockup(ALPH.oneAlph, tokens, 1).tokenVector is AVector.from(
        tokens.toSeq.sortBy(_._1.bytes)
      )
    }
  }

  it should "getTokenAmount" in new Fixture {
    val tokenId2 = hashGen.sample.get
    val tokens   = mutable.Map((tokenId -> U256.One), (tokenId2 -> U256.Two))

    val balancesPerLockup = BalancesPerLockup(ALPH.oneAlph, tokens, 1)

    balancesPerLockup.getTokenAmount(tokenId) is Some(U256.One)
    balancesPerLockup.getTokenAmount(tokenId2) is Some(U256.Two)
    balancesPerLockup.getTokenAmount(hashGen.sample.get) is None
  }

  it should "addAlph" in new Fixture {
    val balancesPerLockup = BalancesPerLockup(ALPH.oneAlph, mutable.Map.empty, 1)

    var current = ALPH.oneAlph

    forAll(amountGen(1)) { amount =>
      current.add(amount) match {
        case Some(newCurrent) =>
          current = newCurrent
          balancesPerLockup.addAlph(amount) is Some(())
        case None =>
          balancesPerLockup.addAlph(amount) is None
      }
      balancesPerLockup.alphAmount is current
    }

    balancesPerLockup.addAlph(U256.MaxValue) is None
  }

  it should "addToken" in new Fixture {
    val tokens            = mutable.Map((tokenId -> ALPH.oneAlph))
    val balancesPerLockup = BalancesPerLockup(ALPH.oneAlph, tokens, 1)

    balancesPerLockup.addToken(tokenId, ALPH.oneAlph) is Some(())
    balancesPerLockup.getTokenAmount(tokenId) is Some(ALPH.alph(2))

    balancesPerLockup.addToken(tokenId, U256.MaxValue) is None
    balancesPerLockup.getTokenAmount(tokenId) is Some(ALPH.alph(2))

    val tokenId2 = hashGen.sample.get
    balancesPerLockup.getTokenAmount(tokenId2) is None
    balancesPerLockup.addToken(tokenId2, ALPH.oneAlph) is Some(())
    balancesPerLockup.getTokenAmount(tokenId2) is Some(ALPH.oneAlph)
  }

  it should "subAlph" in new Fixture {
    val balancesPerLockup = BalancesPerLockup(U256.HalfMaxValue, mutable.Map.empty, 1)

    var current = U256.HalfMaxValue

    forAll(amountGen(1)) { amount =>
      current.sub(amount) match {
        case Some(newCurrent) =>
          current = newCurrent
          balancesPerLockup.subAlph(amount) is Some(())
        case None =>
          balancesPerLockup.subAlph(amount) is None
      }
      balancesPerLockup.alphAmount is current
    }

    balancesPerLockup.subAlph(U256.MaxValue) is None
  }

  it should "subToken" in new Fixture {
    val tokens            = mutable.Map((tokenId -> ALPH.oneAlph))
    val balancesPerLockup = BalancesPerLockup(ALPH.oneAlph, tokens, 1)

    balancesPerLockup.subToken(tokenId, ALPH.oneAlph) is Some(())
    balancesPerLockup.getTokenAmount(tokenId) is Some(U256.Zero)

    balancesPerLockup.subToken(tokenId, U256.MaxValue) is None
    balancesPerLockup.getTokenAmount(tokenId) is Some(U256.Zero)

    val tokenId2 = hashGen.sample.get
    balancesPerLockup.getTokenAmount(tokenId2) is None
    balancesPerLockup.subToken(tokenId2, ALPH.oneAlph) is None
    balancesPerLockup.getTokenAmount(tokenId2) is None
  }

  it should "add" in new Fixture {
    val balancesPerLockup =
      BalancesPerLockup(ALPH.oneAlph, mutable.Map((tokenId -> ALPH.oneAlph)), 1)

    val tokenId2 = hashGen.sample.get
    val balancesPerLockup2 = BalancesPerLockup(
      ALPH.oneAlph,
      mutable.Map((tokenId -> ALPH.oneAlph), (tokenId2 -> ALPH.oneAlph)),
      1
    )

    balancesPerLockup.add(balancesPerLockup2) is Some(())
    balancesPerLockup is BalancesPerLockup(
      ALPH.alph(2),
      mutable.Map((tokenId -> ALPH.alph(2)), (tokenId2 -> ALPH.oneAlph)),
      1
    )

    balancesPerLockup.add(BalancesPerLockup(U256.MaxValue, mutable.Map.empty, 1)) is None
    balancesPerLockup.add(
      BalancesPerLockup(ALPH.oneAlph, mutable.Map((tokenId -> U256.MaxValue)), 1)
    ) is None
  }

  it should "sub" in new Fixture {
    val balancesPerLockup =
      BalancesPerLockup(ALPH.oneAlph, mutable.Map((tokenId -> ALPH.oneAlph)), 1)

    val tokenId2 = hashGen.sample.get
    val balancesPerLockup2 =
      BalancesPerLockup(ALPH.oneAlph, mutable.Map((tokenId -> ALPH.oneAlph)), 1)

    balancesPerLockup.sub(balancesPerLockup2) is Some(())
    balancesPerLockup is BalancesPerLockup(U256.Zero, mutable.Map((tokenId -> U256.Zero)), 1)

    balancesPerLockup.sub(BalancesPerLockup(U256.MaxValue, mutable.Map.empty, 1)) is None
    balancesPerLockup.sub(
      BalancesPerLockup(ALPH.oneAlph, mutable.Map((tokenId -> U256.MaxValue)), 1)
    ) is None
    balancesPerLockup.sub(
      BalancesPerLockup(ALPH.oneAlph, mutable.Map((tokenId2 -> ALPH.oneAlph)), 1)
    ) is None
  }

  it should "toTxOutput" in new Fixture {
    val tokens = mutable.Map((tokenId -> ALPH.oneAlph))

    val lockupScript = lockupScriptGen.sample.get

    BalancesPerLockup(ALPH.oneAlph, tokens, 1).toTxOutput(lockupScript) is Right(
      Some(TxOutput.from(ALPH.oneAlph, AVector.from(tokens), lockupScript))
    )
    BalancesPerLockup(ALPH.oneAlph, mutable.Map.empty, 1).toTxOutput(lockupScript) is Right(
      Some(TxOutput.from(ALPH.oneAlph, AVector.empty, lockupScript))
    )
    BalancesPerLockup(U256.Zero, mutable.Map.empty, 1).toTxOutput(lockupScript) is Right(None)
    BalancesPerLockup(U256.Zero, tokens, 1).toTxOutput(lockupScript) is Left(
      Right(InvalidOutputBalances)
    )
    BalancesPerLockup(U256.Zero, mutable.Map.empty, 1).toTxOutput(lockupScript) is Right(None)
  }

  trait Fixture extends TxGenerators with NetworkConfigFixture.Default {
    val tokenId = hashGen.sample.get

    implicit override val groupConfig: GroupConfig =
      new GroupConfig {
        override def groups: Int = 3
      }
    implicit override val compilerConfig: CompilerConfig =
      new CompilerConfig {
        override def loopUnrollingLimit: Int = 1000
      }
  }
}
