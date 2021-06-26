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

import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.model.{StatusCode, Uri}
import sttp.tapir.client.sttp._

import org.alephium.api.{ApiError, Endpoints}
import org.alephium.api.model._
import org.alephium.protocol.{PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex, NetworkType}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util.{AVector, Duration, Hex, TimeStamp, U256}

trait BlockFlowClient {
  def fetchBalance(address: Address): Future[Either[ApiError[_ <: StatusCode], U256]]
  def prepareTransaction(
      fromKey: String,
      destinations: AVector[Destination],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice]
  ): Future[Either[ApiError[_ <: StatusCode], BuildTransactionResult]]
  def prepareSweepAllTransaction(
      fromKey: String,
      address: Address,
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
  def apply(defaultUri: Uri, networkType: NetworkType, blockflowFetchMaxAge: Duration)(implicit
      groupConfig: GroupConfig,
      executionContext: ExecutionContext
  ): BlockFlowClient =
    new Impl(defaultUri, networkType, blockflowFetchMaxAge)

  private class Impl(
      defaultUri: Uri,
      val networkType: NetworkType,
      val blockflowFetchMaxAge: Duration
  )(implicit
      val groupConfig: GroupConfig,
      executionContext: ExecutionContext
  ) extends BlockFlowClient
      with Endpoints
      with SttpClientInterpreter {

    private val backend = AsyncHttpClientFutureBackend()

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

    private def requestFromGroup[P, A](
        fromGroup: GroupIndex,
        endpoint: BaseEndpoint[P, A],
        params: P
    ): Future[Either[ApiError[_ <: StatusCode], A]] =
      uriFromGroup(fromGroup).flatMap {
        _.fold(
          e => Future.successful(Left(e)),
          uri =>
            backend
              .send(toRequestThrowDecodeFailures(endpoint, Some(uri)).apply(params))
              .map(_.body)
        )
      }

    def fetchBalance(address: Address): Future[Either[ApiError[_ <: StatusCode], U256]] =
      requestFromGroup(address.groupIndex, getBalance, address).map(_.map(_.balance))

    def prepareTransaction(
        fromKey: String,
        destinations: AVector[Destination],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice]
    ): Future[Either[ApiError[_ <: StatusCode], BuildTransactionResult]] = {
      Hex.from(fromKey).flatMap(PublicKey.from) match {
        case None => Future.successful(Left(ApiError.BadRequest(s"Cannot decode key $fromKey")))
        case Some(publicKey) =>
          val lockupScript = LockupScript.p2pkh(publicKey)
          requestFromGroup(
            lockupScript.groupIndex,
            buildTransaction,
            BuildTransaction(
              publicKey,
              destinations,
              gas,
              gasPrice
            )
          )
      }
    }

    def prepareSweepAllTransaction(
        fromKey: String,
        address: Address,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice]
    ): Future[Either[ApiError[_ <: StatusCode], BuildTransactionResult]] = {
      Hex.from(fromKey).flatMap(PublicKey.from) match {
        case None => Future.successful(Left(ApiError.BadRequest(s"Cannot decode key $fromKey")))
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
        sendTransaction,
        SendTransaction(tx, signature)
      )
    }

    private def fetchSelfClique(): Future[Either[ApiError[_ <: StatusCode], SelfClique]] = {
      val x = backend
        .send(
          toRequestThrowDecodeFailures(getSelfClique, Some(defaultUri)).apply(())
        )
      x.map(_.body)
    }
  }
}
