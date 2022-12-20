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
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.{RandomBytes, Serde}
import org.alephium.util.Bytes

final case class ContractId private (value: Hash) extends AnyVal with RandomBytes {
  def bytes: ByteString = value.bytes

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def groupIndex(implicit config: GroupConfig): GroupIndex = {
    GroupIndex.unsafe(value.bytes.last.toInt)
  }

  def subContractId(path: ByteString, groupIndex: GroupIndex): ContractId = {
    ContractId.subContract(bytes ++ path, groupIndex)
  }

  // The last byte of the contract output ref cannot be recovered since Leman upgrade
  def inaccurateFirstOutputRef(): ContractOutputRef = {
    ContractOutputRef.inaccurateFirstOutput(this)
  }
}

object ContractId extends HashUtils[ContractId] {
  implicit val serde: Serde[ContractId] = Serde.forProduct1(ContractId.apply, t => t.value)

  lazy val zero: ContractId = ContractId(Hash.zero)
  val length: Int           = Hash.length

  def generate: ContractId = ContractId(Hash.generate)

  def from(bytes: ByteString): Option[ContractId] = {
    Hash.from(bytes).map(ContractId.apply)
  }

  private def lemanUnsafe(deprecated: ContractId, groupIndex: GroupIndex): ContractId = {
    unsafe(Hash.unsafe(deprecated.bytes.dropRight(1) ++ ByteString(groupIndex.value.toByte)))
  }

  def deprecatedFrom(txId: TransactionId, outputIndex: Int): ContractId = {
    hash(txId.bytes ++ Bytes.from(outputIndex))
  }

  def from(txId: TransactionId, outputIndex: Int, groupIndex: GroupIndex): ContractId = {
    val deprecated = deprecatedFrom(txId, outputIndex)
    lemanUnsafe(deprecated, groupIndex)
  }

  def deprecatedSubContract(preImage: ByteString): ContractId = {
    unsafe(Hash.doubleHash(preImage))
  }

  def subContract(preImage: ByteString, groupIndex: GroupIndex): ContractId = {
    val deprecated = deprecatedSubContract(preImage)
    lemanUnsafe(deprecated, groupIndex)
  }

  @inline def hash(bytes: Seq[Byte]): ContractId = ContractId(Hash.hash(bytes))

  @inline def hash(str: String): ContractId = hash(ByteString(str))

  @inline def unsafe(hash: Hash): ContractId = ContractId(hash)
}
