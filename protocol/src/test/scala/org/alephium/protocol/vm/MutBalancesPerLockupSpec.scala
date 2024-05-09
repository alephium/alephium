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

import org.scalatest.Assertion

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{GroupConfig, NetworkConfigFixture}
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, U256}
import org.alephium.util.Bytes.byteStringOrdering

class MutBalancesPerLockupSpec extends AlephiumSpec {

  it should "tokenVector" in new Fixture {
    val tokens = mutable.Map(tokenId -> ALPH.oneAlph)
    MutBalancesPerLockup(ALPH.oneAlph, tokens, 1).tokenVector is AVector((tokenId, ALPH.oneAlph))

    val tokenIdZero = TokenId.generate
    tokens.addOne((tokenIdZero, U256.Zero))

    MutBalancesPerLockup(ALPH.oneAlph, tokens, 1).tokenVector is AVector((tokenId, ALPH.oneAlph))

    tokens.remove(tokenIdZero)

    forAll(hashGen) { newTokenIdValue =>
      tokens.addOne((TokenId.unsafe(newTokenIdValue), ALPH.oneAlph))
      MutBalancesPerLockup(ALPH.oneAlph, tokens, 1).tokenVector is AVector.from(
        tokens.toSeq.sortBy(_._1.bytes)
      )
    }
  }

  it should "getTokenAmount" in new Fixture {
    val tokenId2 = TokenId.generate
    val tokens   = mutable.Map(tokenId -> U256.One, tokenId2 -> U256.Two)

    val balancesPerLockup = MutBalancesPerLockup(ALPH.oneAlph, tokens, 1)

    balancesPerLockup.getTokenAmount(tokenId) is Some(U256.One)
    balancesPerLockup.getTokenAmount(tokenId2) is Some(U256.Two)
    balancesPerLockup.getTokenAmount(TokenId.generate) is None
  }

  it should "addAlph" in new Fixture {
    val balancesPerLockup = MutBalancesPerLockup(ALPH.oneAlph, mutable.Map.empty, 1)

    var current = ALPH.oneAlph

    forAll(amountGen(1)) { amount =>
      current.add(amount) match {
        case Some(newCurrent) =>
          current = newCurrent
          balancesPerLockup.addAlph(amount) is Some(())
        case None =>
          balancesPerLockup.addAlph(amount) is None
      }
      balancesPerLockup.attoAlphAmount is current
    }

    balancesPerLockup.addAlph(U256.MaxValue) is None
  }

  it should "addToken" in new Fixture {
    val tokens            = mutable.Map(tokenId -> ALPH.oneAlph)
    val balancesPerLockup = MutBalancesPerLockup(ALPH.oneAlph, tokens, 1)

    balancesPerLockup.addToken(tokenId, ALPH.oneAlph) is Some(())
    balancesPerLockup.getTokenAmount(tokenId) is Some(ALPH.alph(2))

    balancesPerLockup.addToken(tokenId, U256.MaxValue) is None
    balancesPerLockup.getTokenAmount(tokenId) is Some(ALPH.alph(2))

    val tokenId2 = TokenId.generate
    balancesPerLockup.getTokenAmount(tokenId2) is None
    balancesPerLockup.addToken(tokenId2, ALPH.oneAlph) is Some(())
    balancesPerLockup.getTokenAmount(tokenId2) is Some(ALPH.oneAlph)
  }

  it should "subAlph" in new Fixture {
    val balancesPerLockup = MutBalancesPerLockup(U256.HalfMaxValue, mutable.Map.empty, 1)

    var current = U256.HalfMaxValue

    forAll(amountGen(1)) { amount =>
      current.sub(amount) match {
        case Some(newCurrent) =>
          current = newCurrent
          balancesPerLockup.subAlph(amount) is Some(())
        case None =>
          balancesPerLockup.subAlph(amount) is None
      }
      balancesPerLockup.attoAlphAmount is current
    }

    balancesPerLockup.subAlph(U256.MaxValue) is None
  }

  it should "subToken" in new Fixture {
    val tokens            = mutable.Map(tokenId -> ALPH.oneAlph)
    val balancesPerLockup = MutBalancesPerLockup(ALPH.oneAlph, tokens, 1)

    balancesPerLockup.subToken(tokenId, ALPH.oneAlph) is Some(())
    balancesPerLockup.getTokenAmount(tokenId) is Some(U256.Zero)

    balancesPerLockup.subToken(tokenId, U256.MaxValue) is None
    balancesPerLockup.getTokenAmount(tokenId) is Some(U256.Zero)

    val tokenId2 = TokenId.generate
    balancesPerLockup.getTokenAmount(tokenId2) is None
    balancesPerLockup.subToken(tokenId2, ALPH.oneAlph) is None
    balancesPerLockup.getTokenAmount(tokenId2) is None
  }

  it should "add" in new Fixture {
    val balancesPerLockup =
      MutBalancesPerLockup(ALPH.oneAlph, mutable.Map(tokenId -> ALPH.oneAlph), 1)

    val tokenId2 = TokenId.generate
    val balancesPerLockup2 = MutBalancesPerLockup(
      ALPH.oneAlph,
      mutable.Map(tokenId -> ALPH.oneAlph, tokenId2 -> ALPH.oneAlph),
      1
    )

    balancesPerLockup.add(balancesPerLockup2) is Some(())
    balancesPerLockup is MutBalancesPerLockup(
      ALPH.alph(2),
      mutable.Map(tokenId -> ALPH.alph(2), tokenId2 -> ALPH.oneAlph),
      1
    )

    balancesPerLockup.add(MutBalancesPerLockup(U256.MaxValue, mutable.Map.empty, 1)) is None
    balancesPerLockup.add(
      MutBalancesPerLockup(ALPH.oneAlph, mutable.Map(tokenId -> U256.MaxValue), 1)
    ) is None
  }

  it should "sub" in new Fixture {
    val balancesPerLockup =
      MutBalancesPerLockup(ALPH.oneAlph, mutable.Map(tokenId -> ALPH.oneAlph), 1)

    val tokenId2 = TokenId.generate
    val balancesPerLockup2 =
      MutBalancesPerLockup(ALPH.oneAlph, mutable.Map(tokenId -> ALPH.oneAlph), 1)

    balancesPerLockup.sub(balancesPerLockup2) is Some(())
    balancesPerLockup is MutBalancesPerLockup(U256.Zero, mutable.Map(tokenId -> U256.Zero), 1)

    balancesPerLockup.sub(MutBalancesPerLockup(U256.MaxValue, mutable.Map.empty, 1)) is None
    balancesPerLockup.sub(
      MutBalancesPerLockup(ALPH.oneAlph, mutable.Map(tokenId -> U256.MaxValue), 1)
    ) is None
    balancesPerLockup.sub(
      MutBalancesPerLockup(ALPH.oneAlph, mutable.Map(tokenId2 -> ALPH.oneAlph), 1)
    ) is None
  }

  trait ToTxOutputFixture extends Fixture {
    import org.alephium.protocol.model.TokenId.tokenIdOrder

    val lockupScript = lockupScriptGen.sample.get
    val tokens       = AVector.fill(maxTokenPerContractUtxo + 1)(TokenId.generate).sorted
    val tokenId0     = tokens(0)
    val tokenId1     = tokens(1)

    case class Test(alphAmount: U256, tokens: (TokenId, U256)*) {
      lazy val genesisOutputs = MutBalancesPerLockup(alphAmount, mutable.Map.from(tokens), 1)
        .toTxOutput(lockupScript, HardFork.Mainnet)

      lazy val lemanOutputs = MutBalancesPerLockup(alphAmount, mutable.Map.from(tokens), 1)
        .toTxOutput(lockupScript, HardFork.Leman)

      lazy val rhoneOutputs = MutBalancesPerLockup(alphAmount, mutable.Map.from(tokens), 1)
        .toTxOutput(lockupScript, HardFork.Rhone)

      def expectGenesis(outputs: (U256, Seq[(TokenId, U256)])*): Assertion = {
        genesisOutputs isE AVector.from(outputs).map { case (amount, tokens) =>
          TxOutput.fromDeprecated(amount, AVector.from(tokens), lockupScript)
        }
      }

      def failGenesis(): Assertion = {
        genesisOutputs is failed(
          InvalidOutputBalances(lockupScript, tokens.length, alphAmount)
        )
      }

      def expectLeman(outputs: (U256, Seq[(TokenId, U256)])*): Assertion = {
        lemanOutputs isE AVector.from(outputs).map { case (amount, tokens) =>
          TxOutput.fromDeprecated(amount, AVector.from(tokens), lockupScript)
        }
      }

      def failLeman(
          error: ExeFailure = InvalidOutputBalances(lockupScript, tokens.length, alphAmount)
      ): Assertion = {
        lemanOutputs is failed(error)
      }

      def expectRhone(outputs: (U256, Seq[(TokenId, U256)])*): Assertion = {
        rhoneOutputs isE AVector.from(outputs).map { case (amount, tokens) =>
          TxOutput.fromDeprecated(amount, AVector.from(tokens), lockupScript)
        }
      }

      def failRhone(
          error: ExeFailure = InvalidOutputBalances(lockupScript, tokens.length, alphAmount)
      ): Assertion = {
        rhoneOutputs is failed(error)
      }
    }
  }

  it should "toTxOutput for Genesis fork" in new ToTxOutputFixture {
    Test(0).expectGenesis()
    Test(ALPH.oneAlph, tokenId -> 1).expectGenesis(
      ALPH.oneAlph -> Seq(tokenId -> 1)
    )
    Test(ALPH.oneAlph, tokenId0 -> 1, tokenId1 -> 2).expectGenesis(
      ALPH.oneAlph -> Seq(tokenId0 -> 1, tokenId1 -> 2)
    )
    Test(ALPH.oneAlph).expectGenesis(ALPH.oneAlph -> Seq.empty)
    Test(0, tokenId -> 1).failGenesis()
  }

  it should "toTxOutput for Leman fork + asset lockup script" in new ToTxOutputFixture {
    override val lockupScript = assetLockupGen(GroupIndex.unsafe(0)).sample.get

    Test(0).expectLeman()
    Test(dustUtxoAmount - 1).failLeman()
    Test(dustUtxoAmount).expectLeman(dustUtxoAmount -> Seq.empty)
    Test(ALPH.oneAlph).expectLeman(ALPH.oneAlph     -> Seq.empty)

    Test(0, tokenId -> 1).failLeman()
    Test(dustUtxoAmount - 1, tokenId -> 1).failLeman()
    Test(dustUtxoAmount, tokenId -> 1).expectLeman(
      dustUtxoAmount -> Seq(tokenId -> 1)
    )
    Test(dustUtxoAmount * 2 - 1, tokenId -> 1).failLeman()
    Test(dustUtxoAmount * 2, tokenId -> 1).expectLeman(
      dustUtxoAmount -> Seq(tokenId -> 1),
      dustUtxoAmount -> Seq.empty
    )
    Test(ALPH.oneAlph, tokenId -> 1).expectLeman(
      dustUtxoAmount                         -> Seq(tokenId -> 1),
      ALPH.oneAlph.subUnsafe(dustUtxoAmount) -> Seq.empty
    )

    Test(0, tokenId0 -> 1, tokenId1 -> 2).failLeman()
    Test(dustUtxoAmount * 2 - 1, tokenId0 -> 1, tokenId1 -> 2).failLeman()
    Test(dustUtxoAmount * 2, tokenId0 -> 1, tokenId1 -> 2).expectLeman(
      dustUtxoAmount -> Seq(tokenId0 -> 1),
      dustUtxoAmount -> Seq(tokenId1 -> 2)
    )
    Test(dustUtxoAmount * 3 - 1, tokenId0 -> 1, tokenId1 -> 2).failLeman()
    Test(dustUtxoAmount * 3, tokenId0 -> 1, tokenId1 -> 2).expectLeman(
      dustUtxoAmount -> Seq(tokenId0 -> 1),
      dustUtxoAmount -> Seq(tokenId1 -> 2),
      dustUtxoAmount -> Seq.empty
    )
    Test(ALPH.oneAlph, tokenId0 -> 1, tokenId1 -> 2).expectLeman(
      dustUtxoAmount                             -> Seq(tokenId0 -> 1),
      dustUtxoAmount                             -> Seq(tokenId1 -> 2),
      ALPH.oneAlph.subUnsafe(dustUtxoAmount * 2) -> Seq.empty
    )
  }

  it should "toTxOutput for Leman fork + contract lockup script" in new ToTxOutputFixture {
    override val lockupScript = LockupScript.p2c(ContractId.generate)
    val address               = Address.from(lockupScript)

    Test(0).expectLeman()
    Test(ALPH.oneAlph - 1).failLeman(LowerThanContractMinimalBalance(address, ALPH.oneAlph - 1))
    Test(ALPH.oneAlph).expectLeman(ALPH.oneAlph -> Seq.empty)

    Test(0, tokenId -> 1).failLeman()
    Test(ALPH.oneAlph - 1, tokenId -> 1)
      .failLeman(LowerThanContractMinimalBalance(address, ALPH.oneAlph - 1))
    Test(ALPH.oneAlph, tokenId -> 1).expectLeman(
      ALPH.oneAlph -> Seq(tokenId -> 1)
    )
    Test(ALPH.oneAlph, tokens.init.map(_ -> U256.One).toSeq: _*).expectLeman(
      ALPH.oneAlph -> tokens.init.map(_ -> U256.One).toSeq
    )
    Test(ALPH.oneAlph, tokens.map(_ -> U256.One).toSeq: _*)
      .failLeman(InvalidTokenNumForContractOutput(address, tokens.length))
  }

  it should "toTxOutput for rhone fork + contract lockup script" in new ToTxOutputFixture {
    override val lockupScript = LockupScript.p2c(ContractId.generate)
    val address               = Address.from(lockupScript)
    val minimalDeposit        = ALPH.oneAlph.divUnsafe(U256.unsafe(10))

    Test(0).expectRhone()
    Test(minimalDeposit - 1).failRhone(LowerThanContractMinimalBalance(address, minimalDeposit - 1))
    Test(minimalDeposit).expectRhone(minimalDeposit -> Seq.empty)

    Test(0, tokenId -> 1).failRhone()
    Test(minimalDeposit - 1, tokenId -> 1)
      .failRhone(LowerThanContractMinimalBalance(address, minimalDeposit - 1))
    Test(minimalDeposit, tokenId -> 1).expectRhone(
      minimalDeposit -> Seq(tokenId -> 1)
    )
    Test(minimalDeposit, tokens.init.map(_ -> U256.One).toSeq: _*).expectRhone(
      minimalDeposit -> tokens.init.map(_ -> U256.One).toSeq
    )
    Test(minimalDeposit, tokens.map(_ -> U256.One).toSeq: _*)
      .failRhone(InvalidTokenNumForContractOutput(address, tokens.length))
  }

  trait Fixture extends TxGenerators with NetworkConfigFixture.Default {
    val tokenId = TokenId.generate

    implicit override val groupConfig: GroupConfig =
      new GroupConfig {
        override def groups: Int = 3
      }
  }
}
