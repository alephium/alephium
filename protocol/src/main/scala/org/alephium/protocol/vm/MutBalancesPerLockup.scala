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
import scala.util.Try

import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp, U256}

final case class MutBalancesPerLockup(
    var attoAlphAmount: U256,
    tokenAmounts: mutable.Map[TokenId, U256],
    scopeDepth: Int
) {
  def tokenVector: AVector[(TokenId, U256)] = {
    import org.alephium.protocol.model.TokenId.tokenIdOrder
    AVector.from(tokenAmounts.filter(_._2.nonZero)).sortBy(_._1)
  }

  def getTokenAmount(tokenId: TokenId): Option[U256] = tokenAmounts.get(tokenId)

  def addAlph(amount: U256): Option[Unit] = {
    attoAlphAmount.add(amount).map(attoAlphAmount = _)
  }

  def addToken(tokenId: TokenId, amount: U256): Option[Unit] = {
    tokenAmounts.get(tokenId) match {
      case Some(currentAmount) =>
        currentAmount.add(amount).map(tokenAmounts(tokenId) = _)
      case None =>
        tokenAmounts(tokenId) = amount
        Some(())
    }
  }

  def subAlph(amount: U256): Option[Unit] = {
    attoAlphAmount.sub(amount).map(attoAlphAmount = _)
  }

  def subToken(tokenId: TokenId, amount: U256): Option[Unit] = {
    tokenAmounts.get(tokenId).flatMap { currentAmount =>
      currentAmount.sub(amount).map(tokenAmounts(tokenId) = _)
    }
  }

  def add(another: MutBalancesPerLockup): Option[Unit] =
    Try {
      attoAlphAmount =
        attoAlphAmount.add(another.attoAlphAmount).getOrElse(throw MutBalancesPerLockup.error)
      another.tokenAmounts.foreach { case (tokenId, amount) =>
        tokenAmounts.get(tokenId) match {
          case Some(currentAmount) =>
            tokenAmounts(tokenId) =
              currentAmount.add(amount).getOrElse(throw MutBalancesPerLockup.error)
          case None =>
            tokenAmounts(tokenId) = amount
        }
      }
    }.toOption

  def sub(another: MutBalancesPerLockup): Option[Unit] =
    Try {
      attoAlphAmount =
        attoAlphAmount.sub(another.attoAlphAmount).getOrElse(throw MutBalancesPerLockup.error)
      another.tokenAmounts.foreach { case (tokenId, amount) =>
        tokenAmounts.get(tokenId) match {
          case Some(currentAmount) =>
            tokenAmounts(tokenId) =
              currentAmount.sub(amount).getOrElse(throw MutBalancesPerLockup.error)
          case None => throw MutBalancesPerLockup.error
        }
      }
    }.toOption

  def toTxOutput(lockupScript: LockupScript, hardFork: HardFork): ExeResult[AVector[TxOutput]] = {
    if (hardFork.isLemanEnabled()) {
      toTxOutputLeman(lockupScript, TimeStamp.zero, hardFork)
    } else {
      toTxOutputDeprecated(lockupScript)
    }
  }

  def toTxOutputLeman(
      lockupScript: LockupScript,
      lockTime: TimeStamp,
      hardFork: HardFork
  ): ExeResult[AVector[TxOutput]] = {
    val tokens = tokenVector
    if (attoAlphAmount.isZero) {
      if (tokens.isEmpty) {
        Right(AVector.empty)
      } else {
        failed(InvalidOutputBalances(lockupScript, tokens.length, attoAlphAmount))
      }
    } else {
      lockupScript match {
        case l: LockupScript.Asset =>
          TxOutput
            .from(attoAlphAmount, tokens, l, lockTime)
            .toRight(Right(InvalidOutputBalances(lockupScript, tokens.length, attoAlphAmount)))
        case l: LockupScript.P2C =>
          if (attoAlphAmount < minimalContractStorageDeposit(hardFork)) {
            failed(LowerThanContractMinimalBalance(Address.Contract(l), attoAlphAmount))
          } else if (tokens.length > maxTokenPerContractUtxo) {
            failed(InvalidTokenNumForContractOutput(Address.Contract(l), tokens.length))
          } else {
            Right(AVector[TxOutput](ContractOutput(attoAlphAmount, l, tokens)))
          }
      }
    }
  }

  def toTxOutputDeprecated(lockupScript: LockupScript): ExeResult[AVector[TxOutput]] = {
    val tokens = tokenVector
    if (attoAlphAmount.isZero) {
      if (tokens.isEmpty) {
        Right(AVector.empty)
      } else {
        failed(InvalidOutputBalances(lockupScript, tokens.length, attoAlphAmount))
      }
    } else {
      Right(AVector(TxOutput.fromDeprecated(attoAlphAmount, tokens, lockupScript)))
    }
  }

  def toLockedTxOutput(
      lockupScript: LockupScript.Asset,
      lockTime: TimeStamp,
      hardFork: HardFork
  ): ExeResult[AVector[TxOutput]] = {
    toTxOutputLeman(lockupScript, lockTime, hardFork)
  }
}

object MutBalancesPerLockup {
  val error: ArithmeticException = new ArithmeticException("Balance amount")

  // Need to be `def` as it's mutable
  def empty: MutBalancesPerLockup = MutBalancesPerLockup(U256.Zero, mutable.Map.empty, 0)

  def alph(amount: U256): MutBalancesPerLockup = {
    MutBalancesPerLockup(amount, mutable.Map.empty, 0)
  }

  protected[vm] def alph(amount: U256, scopeDepth: Int): MutBalancesPerLockup = {
    MutBalancesPerLockup(amount, mutable.Map.empty, scopeDepth)
  }

  def token(id: TokenId, amount: U256): MutBalancesPerLockup = {
    MutBalancesPerLockup(U256.Zero, mutable.Map(id -> amount), 0)
  }

  def from(output: TxOutput): MutBalancesPerLockup =
    MutBalancesPerLockup(output.amount, mutable.Map.from(output.tokens.toIterable), 0)
}
