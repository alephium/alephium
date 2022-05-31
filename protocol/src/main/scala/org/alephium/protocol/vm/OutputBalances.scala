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

import org.alephium.protocol.model.{dustUtxoAmount, AssetOutput, TokenId}
import org.alephium.util.{AVector, TimeStamp, U256}

final case class LockedAsset(
    var alphAmount: Option[U256],
    tokens: mutable.LinkedHashMap[TokenId, U256]
) {
  def addAlph(amount: U256): Option[Unit] = {
    alphAmount match {
      case Some(currentAmount) =>
        currentAmount.add(amount).map(v => alphAmount = Some(v))
      case None =>
        alphAmount = Some(amount)
        Some(())
    }
  }

  def addToken(tokenId: TokenId, amount: U256): Option[Unit] = {
    tokens.get(tokenId) match {
      case Some(currentAmount) =>
        currentAmount.add(amount).map(tokens(tokenId) = _)
      case None =>
        tokens(tokenId) = amount
        Some(())
    }
  }

  def toTxOutput(lockupScript: LockupScript.Asset, timestamp: TimeStamp): AssetOutput = {
    val alph = alphAmount.getOrElse(dustUtxoAmount)
    AssetOutput(alph, lockupScript, timestamp, AVector.from(tokens), ByteString.empty)
  }
}

object LockedAsset {
  def alph(amount: U256): LockedAsset = LockedAsset(Some(amount), mutable.LinkedHashMap.empty)
  def token(tokenId: TokenId, amount: U256): LockedAsset =
    LockedAsset(None, mutable.LinkedHashMap(tokenId -> amount))
}

final case class LockedBalances(assets: mutable.LinkedHashMap[TimeStamp, LockedAsset]) {
  def addAlph(amount: U256, timestamp: TimeStamp): Option[Unit] = {
    assets.get(timestamp) match {
      case Some(asset) => asset.addAlph(amount)
      case None =>
        assets(timestamp) = LockedAsset.alph(amount)
        Some(())
    }
  }

  def addToken(tokenId: TokenId, amount: U256, timestamp: TimeStamp): Option[Unit] = {
    assets.get(timestamp) match {
      case Some(asset) => asset.addToken(tokenId, amount)
      case None =>
        assets(timestamp) = LockedAsset.token(tokenId, amount)
        Some(())
    }
  }
}

object LockedBalances {
  def alph(amount: U256, timestamp: TimeStamp): LockedBalances = {
    LockedBalances(mutable.LinkedHashMap(timestamp -> LockedAsset.alph(amount)))
  }

  def token(tokenId: TokenId, amount: U256, timestamp: TimeStamp): LockedBalances = {
    LockedBalances(mutable.LinkedHashMap(timestamp -> LockedAsset.token(tokenId, amount)))
  }
}

final case class OutputBalances(
    unlocked: Balances,
    locked: mutable.ArrayBuffer[(LockupScript.Asset, LockedBalances)]
) {
  private def getLockedBalances(lockupScript: LockupScript.Asset): Option[LockedBalances] = {
    locked.collectFirst { case (ls, balances) if ls == lockupScript => balances }
  }

  def addLockedAlph(
      lockupScript: LockupScript.Asset,
      amount: U256,
      timestamp: TimeStamp
  ): Option[Unit] = {
    getLockedBalances(lockupScript) match {
      case Some(balances) => balances.addAlph(amount, timestamp)
      case None =>
        locked.addOne(lockupScript -> LockedBalances.alph(amount, timestamp))
        Some(())
    }
  }

  def addLockedToken(
      lockupScript: LockupScript.Asset,
      tokenId: TokenId,
      amount: U256,
      timestamp: TimeStamp
  ): Option[Unit] = {
    getLockedBalances(lockupScript) match {
      case Some(balances) => balances.addToken(tokenId, amount, timestamp)
      case None =>
        locked.addOne(lockupScript -> LockedBalances.token(tokenId, amount, timestamp))
        Some(())
    }
  }
}

object OutputBalances {
  def empty: OutputBalances = OutputBalances(Balances.empty, mutable.ArrayBuffer.empty)
}
