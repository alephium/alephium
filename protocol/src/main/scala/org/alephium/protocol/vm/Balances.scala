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

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.alephium.protocol.model.{AssetOutput, TokenId, TxOutput}
import org.alephium.util.{AVector, U256}

/*
 * For each stateful frame, users could put a set of assets.
 * Contracts could move funds, generate outputs by using vm's instructions.
 * `remaining` is the current usable balances
 * `approved` is the balances for payable function call
 */
final case class BalanceState(remaining: Balances, approved: Balances) {
  def approveALPH(lockupScript: LockupScript, amount: U256): Option[Unit] = {
    for {
      _ <- remaining.subAlph(lockupScript, amount)
      _ <- approved.addAlph(lockupScript, amount)
    } yield ()
  }

  def approveToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
    for {
      _ <- remaining.subToken(lockupScript, tokenId, amount)
      _ <- approved.addToken(lockupScript, tokenId, amount)
    } yield ()
  }

  def alphRemaining(lockupScript: LockupScript): Option[U256] = {
    remaining.getBalances(lockupScript).map(_.alphAmount)
  }

  def tokenRemaining(lockupScript: LockupScript, tokenId: TokenId): Option[U256] = {
    remaining.getTokenAmount(lockupScript, tokenId)
  }

  def isPaying(lockupScript: LockupScript): Boolean = {
    remaining.all.exists(_._1 == lockupScript)
  }

  def useApproved(): BalanceState = {
    val toUse = approved.use()
    BalanceState(toUse, Balances.empty)
  }

  def useAll(lockupScript: LockupScript): Option[BalancesPerLockup] = {
    remaining.useAll(lockupScript)
  }

  def useAlph(lockupScript: LockupScript, amount: U256): Option[Unit] = {
    remaining.subAlph(lockupScript, amount)
  }

  def useToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
    remaining.subToken(lockupScript, tokenId, amount)
  }
}

object BalanceState {
  def from(balances: Balances): BalanceState = BalanceState(balances, Balances.empty)
}

final case class Balances(all: ArrayBuffer[(LockupScript, BalancesPerLockup)]) {
  def getBalances(lockupScript: LockupScript): Option[BalancesPerLockup] = {
    all.collectFirst { case (ls, balance) if ls == lockupScript => balance }
  }

  def getAlphAmount(lockupScript: LockupScript): Option[U256] = {
    getBalances(lockupScript).map(_.alphAmount)
  }

  def getTokenAmount(lockupScript: LockupScript, tokenId: TokenId): Option[U256] = {
    getBalances(lockupScript).flatMap(_.getTokenAmount(tokenId))
  }

  def addAlph(lockupScript: LockupScript, amount: U256): Option[Unit] = {
    getBalances(lockupScript) match {
      case Some(balances) =>
        balances.addAlph(amount)
      case None =>
        all.addOne(lockupScript -> BalancesPerLockup.alph(amount))
        Some(())
    }
  }

  def addToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
    getBalances(lockupScript) match {
      case Some(balances) =>
        balances.addToken(tokenId, amount)
      case None =>
        all.addOne(lockupScript -> BalancesPerLockup.token(tokenId, amount))
        Some(())
    }
  }

  def subAlph(lockupScript: LockupScript, amount: U256): Option[Unit] = {
    getBalances(lockupScript).flatMap(_.subAlph(amount))
  }

  def subToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
    getBalances(lockupScript).flatMap(_.subToken(tokenId, amount))
  }

  def add(lockupScript: LockupScript, balancesPerLockup: BalancesPerLockup): Option[Unit] = {
    getBalances(lockupScript) match {
      case Some(balances) =>
        balances.add(balancesPerLockup)
      case None =>
        all.addOne(lockupScript -> balancesPerLockup)
        Some(())
    }
  }

  def sub(lockupScript: LockupScript, balancesPerLockup: BalancesPerLockup): Option[Unit] = {
    getBalances(lockupScript).flatMap(_.sub(balancesPerLockup))
  }

  def use(): Balances = {
    val newAll = all.map { case (lockupScript, balancesPerLockup) =>
      lockupScript -> balancesPerLockup.copy(scopeDepth = balancesPerLockup.scopeDepth + 1)
    }
    all.clear()
    Balances(newAll)
  }

  def useAll(lockupScript: LockupScript): Option[BalancesPerLockup] = {
    val index = all.indexWhere { case (ls, _) => ls == lockupScript }
    if (index == -1) {
      None
    } else {
      val (_, balances) = all(index)
      all.remove(index)
      Some(balances)
    }
  }

  def useForNewContract(): Option[BalancesPerLockup] = {
    Option.when(all.nonEmpty) {
      val accumulator = BalancesPerLockup.empty
      all.foreach { balances => accumulator.add(balances._2) }
      all.clear()
      accumulator
    }
  }

  def merge(balances: Balances): Option[Unit] = {
    @tailrec
    def iter(index: Int): Option[Unit] = {
      if (index >= balances.all.length) {
        Some(())
      } else {
        val (lockupScript, balancesPerLockup) = balances.all(index)
        add(lockupScript, balancesPerLockup) match {
          case Some(_) => iter(index + 1)
          case None    => None
        }
      }
    }

    iter(0)
  }

  // scalastyle:off return
  final def toOutputs(): Option[AVector[TxOutput]] = {
    @tailrec
    def iter(acc: AVector[TxOutput], index: Int): Option[AVector[TxOutput]] = {
      if (index == all.length) {
        Some(acc)
      } else {
        val (lockupScript, balancesPerLockup) = all(index)
        balancesPerLockup.toTxOutput(lockupScript) match {
          case Right(Some(output)) => iter(acc :+ output, index + 1)
          case Right(None)         => iter(acc, index + 1)
          case Left(_)             => None
        }
      }
    }
    iter(AVector.ofSize[TxOutput](all.size), 0)
  }
  // scalastyle:on return
}

object Balances {
  // TODO: optimize this
  def from(inputs: AVector[AssetOutput], outputs: AVector[AssetOutput]): Option[Balances] = {
    val inputBalances = inputs.fold(Option(empty)) {
      case (Some(balances), input) =>
        balances.add(input.lockupScript, BalancesPerLockup.from(input)).map(_ => balances)
      case (None, _) => None
    }
    val finalBalances = outputs.fold(inputBalances) {
      case (Some(balances), output) =>
        balances.sub(output.lockupScript, BalancesPerLockup.from(output)).map(_ => balances)
      case (None, _) => None
    }
    finalBalances
  }

  // Need to be `def` as it's mutable
  def empty: Balances = Balances(ArrayBuffer.empty)
}
