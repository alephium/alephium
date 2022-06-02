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

import org.alephium.protocol.model.TokenId
import org.alephium.util.U256

/*
 * For each stateful frame, users could put a set of assets.
 * Contracts could move funds, generate outputs by using vm's instructions.
 * `remaining` is the current usable balances
 * `approved` is the balances that function call potentially can use
 */
final case class MutBalanceState(remaining: MutBalances, approved: MutBalances) {
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

  def useApproved(): MutBalanceState = {
    val toUse = approved.use()
    MutBalanceState(toUse, MutBalances.empty)
  }

  def useAll(lockupScript: LockupScript): Option[MutBalancesPerLockup] = {
    remaining.useAll(lockupScript)
  }

  def useAlph(lockupScript: LockupScript, amount: U256): Option[Unit] = {
    remaining.subAlph(lockupScript, amount)
  }

  def useToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
    remaining.subToken(lockupScript, tokenId, amount)
  }
}

object MutBalanceState {
  def from(balances: MutBalances): MutBalanceState = MutBalanceState(balances, MutBalances.empty)
}
