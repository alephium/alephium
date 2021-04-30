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
import sttp.model.Uri
import sttp.tapir.client.sttp._

import org.alephium.api.Endpoints
import org.alephium.api.model._
import org.alephium.protocol.{PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex, NetworkType}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{Duration, Hex, U256}

trait BlockFlowClient {
  def fetchBalance(address: Address): Future[Either[String, U256]]
  def prepareTransaction(
      fromKey: String,
      toAddress: Address,
      value: U256
  ): Future[Either[String, BuildTransactionResult]]
  def postTransaction(
      tx: String,
      signature: Signature,
      fromGroup: Int
  ): Future[Either[String, TxResult]]
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

    private def uriFromGroup(fromGroup: GroupIndex): Future[Either[String, Uri]] =
      fetchSelfClique().map { selfCliqueEither =>
        for {
          selfClique <- selfCliqueEither
        } yield {
          val peer = selfClique.peer(fromGroup)
          Uri(peer.address.getHostAddress, peer.restPort)
        }
      }

    private def requestFromGroup[P, A, R](
        fromGroup: GroupIndex,
        endpoint: BaseEndpoint[P, A],
        params: P
    )(f: A => R): Future[Either[String, R]] =
      uriFromGroup(fromGroup).flatMap {
        _.fold(
          error => Future.successful(Left(error)),
          uri =>
            backend
              .send(toRequestThrowDecodeFailures(endpoint, Some(uri)).apply(params))
              .map(_.body.map(f).left.map(_.detail))
        )
      }

    def fetchBalance(address: Address): Future[Either[String, U256]] =
      requestFromGroup(address.groupIndex, getBalance, address)(_.balance)

    def prepareTransaction(
        fromKey: String,
        toAddress: Address,
        value: U256
    ): Future[Either[String, BuildTransactionResult]] = {
      Hex.from(fromKey).flatMap(PublicKey.from) match {
        case None => Future.successful(Left(s"Cannot decode key $fromKey"))
        case Some(publicKey) =>
          val lockupScript = LockupScript.p2pkh(publicKey)
          requestFromGroup(
            lockupScript.groupIndex,
            buildTransaction,
            (publicKey, toAddress, None, value)
          )(identity)
      }
    }

    def postTransaction(
        tx: String,
        signature: Signature,
        fromGroup: Int
    ): Future[Either[String, TxResult]] = {
      requestFromGroup(
        GroupIndex.unsafe(fromGroup),
        sendTransaction,
        SendTransaction(tx, signature)
      )(identity)
    }

    private def fetchSelfClique(): Future[Either[String, SelfClique]] = {
      backend
        .send(
          toRequestThrowDecodeFailures(getSelfClique, Some(defaultUri)).apply(())
        )
        .map(_.body.left.map(_.detail))
    }
  }
}
