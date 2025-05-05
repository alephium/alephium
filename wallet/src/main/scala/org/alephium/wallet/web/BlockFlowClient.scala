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

package org.alephium.wallet.web

import scala.concurrent.{ExecutionContext, Future}

import sttp.model.{StatusCode, Uri}

import org.alephium.api.{ApiError, Endpoints}
import org.alephium.api.model._
import org.alephium.http.EndpointSender
import org.alephium.protocol.{PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, AddressLike, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util.{AVector, Duration, TimeStamp}

trait BlockFlowClient {
  def fetchBalance(
      addressLike: AddressLike
  ): Future[Either[ApiError[_ <: StatusCode], (Amount, Amount)]]
  def prepareTransaction(
      fromPublicKey: PublicKey,
      destinations: AVector[Destination],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      utxosLimit: Option[Int]
  ): Future[Either[ApiError[_ <: StatusCode], BuildTransferTxResult]]
  def prepareSweepActiveAddressTransaction(
      fromPublicKey: PublicKey,
      address: Address.Asset,
      lockTime: Option[TimeStamp],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      utxosLimit: Option[Int]
  ): Future[Either[ApiError[_ <: StatusCode], BuildSweepAddressTransactionsResult]]
  def postTransaction(
      tx: String,
      signature: Signature,
      fromGroup: Int
  ): Future[Either[ApiError[_ <: StatusCode], SubmitTxResult]]
}

object BlockFlowClient {
  def apply(
      defaultUri: Uri,
      blockflowFetchMaxAge: Duration,
      maybeApiKey: Option[ApiKey],
      endpointSender: EndpointSender
  )(implicit
      groupConfig: GroupConfig,
      executionContext: ExecutionContext
  ): BlockFlowClient =
    new Impl(defaultUri, blockflowFetchMaxAge, maybeApiKey, endpointSender)

  private class Impl(
      defaultUri: Uri,
      val blockflowFetchMaxAge: Duration,
      val maybeApiKey: Option[ApiKey],
      endpointSender: EndpointSender
  )(implicit
      val groupConfig: GroupConfig,
      executionContext: ExecutionContext
  ) extends BlockFlowClient
      with Endpoints {

    override def apiKeys: AVector[ApiKey] = AVector.empty

    private def uriFromGroup(
        fromGroup: GroupIndex
    ): Future[Either[ApiError[_ <: StatusCode], Uri]] =
      fetchSelfClique().map { selfCliqueEither =>
        for {
          selfClique <- selfCliqueEither
        } yield {
          val peer = selfClique.peer(fromGroup)
          Uri(peer.address.getHostAddress, peer.restPort)
        }
      }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    private def requestFromGroup[P, A](
        fromGroup: GroupIndex,
        endpoint: BaseEndpoint[P, A],
        params: P
    ): Future[Either[ApiError[_ <: StatusCode], A]] = {
      uriFromGroup(fromGroup).flatMap {
        _.fold(
          e => Future.successful(Left(e)),
          uri => endpointSender.send(endpoint, params, uri)
        )
      }
    }

    def fetchBalance(
        addressLike: AddressLike
    ): Future[Either[ApiError[_ <: StatusCode], (Amount, Amount)]] =
      addressLike.lockupScriptResult match {
        case LockupScript.CompleteLockupScript(_: LockupScript.P2C) =>
          Future.successful(
            Left(
              ApiError.BadRequest(s"Expect asset address, but was contract address: $addressLike")
            )
          )
        case LockupScript.CompleteLockupScript(lockupScript) =>
          getBalance(addressLike, lockupScript.groupIndex)
        case LockupScript.HalfDecodedP2PK(publicKey) =>
          getBalance(addressLike, publicKey.defaultGroup)
      }

    private def getBalance(
        addressLike: AddressLike,
        groupIndex: GroupIndex
    ): Future[Either[ApiError[_ <: StatusCode], (Amount, Amount)]] = {
      requestFromGroup(groupIndex, getBalance, (addressLike, Some(true))).map(
        _.map(res => (res.balance, res.lockedBalance))
      )
    }

    def prepareTransaction(
        fromPublicKey: PublicKey,
        destinations: AVector[Destination],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[ApiError[_ <: StatusCode], BuildTransferTxResult]] = {
      val lockupScript = LockupScript.p2pkh(fromPublicKey)
      requestFromGroup(
        lockupScript.groupIndex,
        buildTransferTransaction,
        BuildTransferTx(
          fromPublicKey.bytes,
          None,
          destinations,
          None,
          gas,
          gasPrice
        )
      )
    }

    def prepareSweepActiveAddressTransaction(
        fromPublicKey: PublicKey,
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[ApiError[_ <: StatusCode], BuildSweepAddressTransactionsResult]] = {
      val lockupScript = LockupScript.p2pkh(fromPublicKey)
      requestFromGroup(
        lockupScript.groupIndex,
        buildSweepAddressTransactions,
        BuildSweepAddressTransactions(
          fromPublicKey,
          address,
          None,
          lockTime,
          gas,
          gasPrice
        )
      )
    }

    def postTransaction(
        tx: String,
        signature: Signature,
        fromGroup: Int
    ): Future[Either[ApiError[_ <: StatusCode], SubmitTxResult]] = {
      requestFromGroup(
        GroupIndex.unsafe(fromGroup),
        submitTransaction,
        SubmitTransaction(tx, signature)
      )
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    private def fetchSelfClique(): Future[Either[ApiError[_ <: StatusCode], SelfClique]] = {
      endpointSender.send(getSelfClique, (), defaultUri)
    }
  }
}
