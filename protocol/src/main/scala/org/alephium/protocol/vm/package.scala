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

package org.alephium.protocol

import akka.util.ByteString

import org.alephium.protocol.model.ContractId
import org.alephium.util.Hex.HexStringSyntax
import org.alephium.util.I256

package object vm {
  type ExeResult[T] = Either[Either[IOFailure, ExeFailure], T]

  val okay: ExeResult[Unit]                       = Right(())
  def failed[T](error: ExeFailure): ExeResult[T]  = Left(Right(error))
  def ioFailed[T](error: IOFailure): ExeResult[T] = Left(Left(error))

  val opStackMaxSize: Int       = 1024
  val frameStackMaxSize: Int    = 1024
  val contractPoolMaxSize: Int  = 16 // upto 16 contracts can be loaded in one tx
  val contractFieldMaxSize: Int = 512

  private def specialContractId(eventIndex: Int, group: Int): ContractId = {
    assume(group >= 0 && group < 128)
    val contractId = Hash.unsafe(
      ByteString.fromArray(
        Array.tabulate(ContractId.length)(index =>
          if (index == ContractId.length - 1) {
            group.toByte
          } else if (index == ContractId.length - 2) {
            eventIndex.toByte
          } else {
            0
          }
        )
      )
    )
    ContractId.unsafe(contractId)
  }

  // scalastyle:off magic.number
  lazy val createContractEventIndexInt: Int = -1
  private[vm] lazy val createContractEventIdCache: Array[ContractId] =
    Array.tabulate(128)(group => specialContractId(createContractEventIndexInt, group))
  def createContractEventId(group: Int): ContractId = createContractEventIdCache(group)
  lazy val createContractEventIndex: Val.I256     = Val.I256(I256.from(createContractEventIndexInt))
  val createContractInterfaceIdPrefix: ByteString = hex"414c5048" // "ALPH"

  val destroyContractEventIndexInt: Int = -2
  private[vm] lazy val destroyContractEventIdCache: Array[(ContractId)] =
    Array.tabulate(128)(group => specialContractId(destroyContractEventIndexInt, group))
  def destroyContractEventId(group: Int): ContractId = destroyContractEventIdCache(group)
  val destroyContractEventIndex: Val.I256 = Val.I256(I256.from(destroyContractEventIndexInt))

  val debugEventIndexInt: Int = -3
  private[vm] lazy val debugEventIndexCache: Array[ContractId] =
    Array.tabulate(128)(group => specialContractId(debugEventIndexInt, group))
  def debugEventId(group: Int): ContractId = debugEventIndexCache(group)
  val debugEventIndex: Val.I256            = Val.I256(I256.from(debugEventIndexInt))
  // scalastyle:on magic.number

  type ContractStorageImmutableState = Either[ContractImmutableState, StatefulContract.HalfDecoded]
}
