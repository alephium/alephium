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

package org.alephium.api.endpoints

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.Endpoints._
import org.alephium.api.UtilJson.avectorReadWriter
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}
import org.alephium.util.AVector

trait TransactionsEndpoints extends BaseEndpoints {

  private val transactionsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("transactions")
      .tag("Transactions")

  lazy val getTransactionStatus
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), TxStatus] =
    transactionsEndpoint.get
      .in("status")
      .in(query[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[TxStatus])
      .summary("Get tx status")

  lazy val getTransactionStatusLocal
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), TxStatus] =
    transactionsEndpoint.get
      .in("local-status")
      .in(query[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[TxStatus])
      .summary("Get tx status, only from the local broker")

  lazy val getTxIdFromOutputRef: BaseEndpoint[OutputRef, TransactionId] =
    transactionsEndpoint.get
      .in("tx-id-from-outputref")
      .in(outputRefQuery)
      .out(jsonBody[TransactionId])
      .summary("Get transaction id from transaction output ref")

  val decodeUnsignedTransaction: BaseEndpoint[DecodeUnsignedTx, DecodeUnsignedTxResult] =
    transactionsEndpoint.post
      .in("decode-unsigned-tx")
      .in(jsonBody[DecodeUnsignedTx])
      .out(jsonBody[DecodeUnsignedTxResult])
      .summary("Decode an unsigned transaction")

  lazy val getTransaction
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), Transaction] =
    transactionsEndpoint.get
      .in("details")
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[Transaction])
      .summary("Get transaction details")

  lazy val getRichTransaction
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), RichTransaction] =
    transactionsEndpoint.get
      .in("rich-details")
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[RichTransaction])
      .summary("Get transaction with enriched input information when node indexes are enabled.")

  lazy val getRawTransaction
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), RawTransaction] =
    transactionsEndpoint.get
      .in("raw")
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[RawTransaction])
      .summary("Get raw transaction in hex format")
  val buildTransferTransaction: BaseEndpoint[BuildTransferTx, BuildTransferTxResult] =
    transactionsEndpoint.post
      .in("build")
      .in(jsonBodyWithAlph[BuildTransferTx])
      .out(jsonBody[BuildTransferTxResult])
      .summary("Build an unsigned transfer transaction to a number of recipients")

  val buildTransferFromOneToManyGroups
      : BaseEndpoint[BuildTransferTx, AVector[BuildSimpleTransferTxResult]] =
    transactionsEndpoint.post
      .in("build-transfer-from-one-to-many-groups")
      .in(jsonBodyWithAlph[BuildTransferTx])
      .out(jsonBody[AVector[BuildSimpleTransferTxResult]])
      .summary(
        "Build unsigned transfer transactions from an address of one group to addresses of many groups. " +
          "Each target group requires a dedicated transaction or more in case large number of outputs needed to be split."
      )

  val buildMultiAddressesTransaction
      : BaseEndpoint[BuildMultiAddressesTransaction, BuildSimpleTransferTxResult] =
    transactionsEndpoint.post
      .in("build-multi-addresses")
      .in(jsonBodyWithAlph[BuildMultiAddressesTransaction])
      .out(jsonBody[BuildSimpleTransferTxResult])
      .summary(
        "Build an unsigned transaction with multiple addresses to a number of recipients"
      )

  val buildSweepAddressTransactions
      : BaseEndpoint[BuildSweepAddressTransactions, BuildSweepAddressTransactionsResult] =
    transactionsEndpoint.post
      .in("sweep-address")
      .in("build")
      .in(jsonBody[BuildSweepAddressTransactions])
      .out(jsonBody[BuildSweepAddressTransactionsResult])
      .summary(
        "Build unsigned transactions to send all unlocked ALPH and token balances of one address to another address"
      )

  val submitTransaction: BaseEndpoint[SubmitTransaction, SubmitTxResult] =
    transactionsEndpoint.post
      .in("submit")
      .in(jsonBody[SubmitTransaction])
      .out(jsonBody[SubmitTxResult])
      .summary("Submit a signed transaction")

  val buildChainedTransactions
      : BaseEndpoint[AVector[BuildChainedTx], AVector[BuildChainedTxResult]] =
    transactionsEndpoint.post
      .in("build-chained")
      .in(jsonBody[AVector[BuildChainedTx]])
      .out(jsonBody[AVector[BuildChainedTxResult]])
      .summary("Build a chain of transactions")
}
