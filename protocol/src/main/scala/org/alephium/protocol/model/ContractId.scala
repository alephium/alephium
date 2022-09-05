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

package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto.HashUtils
import org.alephium.protocol.Hash
import org.alephium.serde.{RandomBytes, Serde}
import org.alephium.util.Bytes

final case class ContractId private (value: Hash) extends AnyVal with RandomBytes {
  def bytes: ByteString = value.bytes

  def subContractId(path: ByteString): ContractId = {
    ContractId(Hash.doubleHash(bytes ++ path))
  }

  def firstOutputRef(): ContractOutputRef = {
    ContractOutputRef.firstOutput(this)
  }
}

object ContractId extends HashUtils[ContractId] {
  implicit val serde: Serde[ContractId] = Serde.forProduct1(ContractId.apply, t => t.value)

  val zero: ContractId = ContractId(Hash.zero)
  val length: Int      = Hash.length

  def generate: ContractId = ContractId(Hash.generate)

  def from(bytes: ByteString): Option[ContractId] = {
    Hash.from(bytes).map(ContractId.apply)
  }

  def from(txId: TransactionId, outputIndex: Int): ContractId = {
    hash(txId.bytes ++ Bytes.from(outputIndex))
  }

  @inline def hash(bytes: Seq[Byte]): ContractId = ContractId(Hash.hash(bytes))

  @inline def hash(str: String): ContractId = hash(ByteString(str))
}
