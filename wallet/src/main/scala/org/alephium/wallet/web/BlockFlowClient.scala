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
import sttp.tapir.client.sttp._

import org.alephium.api.{ApiError, Endpoints}
import org.alephium.api.model._
import org.alephium.http.EndpointSender
import org.alephium.protocol.{PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, ChainId, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util.{AVector, Duration, Hex, TimeStamp, U256}

trait BlockFlowClient {
  def fetchBalance(address: Address.Asset): Future[Either[ApiError[_ <: StatusCode], U256]]
  def prepareTransaction(
      fromPublicKey: String,
      destinations: AVector[Destination],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice]
  ): Future[Either[ApiError[_ <: StatusCode], BuildTransactionResult]]
  def prepareSweepAllTransaction(
      fromPublicKey: String,
      address: Address.Asset,
      lockTime: Option[TimeStamp],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice]
  ): Future[Either[ApiError[_ <: StatusCode], BuildTransactionResult]]
  def postTransaction(
      tx: String,
      signature: Signature,
      fromGroup: Int
  ): Future[Either[ApiError[_ <: StatusCode], TxResult]]
}

object BlockFlowClient {
  def apply(
      defaultUri: Uri,
      chainId: ChainId,
      blockflowFetchMaxAge: Duration,
      maybeApiKey: Option[ApiKey]
  )(implicit
      groupConfig: GroupConfig,
      executionContext: ExecutionContext
  ): BlockFlowClient =
    new Impl(defaultUri, chainId, blockflowFetchMaxAge, maybeApiKey)

  private class Impl(
      defaultUri: Uri,
      val chainId: ChainId,
      val blockflowFetchMaxAge: Duration,
      val maybeApiKey: Option[ApiKey]
  )(implicit
      val groupConfig: GroupConfig,
      executionContext: ExecutionContext
  ) extends BlockFlowClient
      with Endpoints
      with EndpointSender
      with SttpClientInterpreter {

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
          uri => send(endpoint, params, uri)
        )
      }
    }

    def fetchBalance(address: Address.Asset): Future[Either[ApiError[_ <: StatusCode], U256]] =
      requestFromGroup(address.groupIndex, getBalance, address).map(_.map(_.balance))

    def prepareTransaction(
        fromPublicKey: String,
        destinations: AVector[Destination],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice]
    ): Future[Either[ApiError[_ <: StatusCode], BuildTransactionResult]] = {
      Hex.from(fromPublicKey).flatMap(PublicKey.from) match {
        case None =>
          Future.successful(Left(ApiError.BadRequest(s"Cannot decode key $fromPublicKey")))
        case Some(publicKey) =>
          val lockupScript = LockupScript.p2pkh(publicKey)
          requestFromGroup(
            lockupScript.groupIndex,
            buildTransaction,
            BuildTransaction(
              publicKey,
              destinations,
              None, // FIXME
              gas,
              gasPrice
            )
          )
      }
    }

    def prepareSweepAllTransaction(
        fromPublicKey: String,
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice]
    ): Future[Either[ApiError[_ <: StatusCode], BuildTransactionResult]] = {
      Hex.from(fromPublicKey).flatMap(PublicKey.from) match {
        case None =>
          Future.successful(Left(ApiError.BadRequest(s"Cannot decode key $fromPublicKey")))
        case Some(publicKey) =>
          val lockupScript = LockupScript.p2pkh(publicKey)
          requestFromGroup(
            lockupScript.groupIndex,
            buildSweepAllTransaction,
            BuildSweepAllTransaction(
              publicKey,
              address,
              lockTime,
              gas,
              gasPrice
            )
          )
      }
    }

    def postTransaction(
        tx: String,
        signature: Signature,
        fromGroup: Int
    ): Future[Either[ApiError[_ <: StatusCode], TxResult]] = {
      requestFromGroup(
        GroupIndex.unsafe(fromGroup),
        submitTransaction,
        SubmitTransaction(tx, signature)
      )
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    private def fetchSelfClique(): Future[Either[ApiError[_ <: StatusCode], SelfClique]] = {
      send(getSelfClique, (), defaultUri)
    }
  }
}
