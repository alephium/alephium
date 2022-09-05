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

import org.alephium.protocol.Hash
import org.alephium.serde.{RandomBytes, Serde}
import org.alephium.util.Bytes.byteStringOrdering

final case class TransactionId(value: Hash) extends RandomBytes {
  def bytes: ByteString = value.bytes
}

object TransactionId {
  implicit val serde: Serde[TransactionId] = Serde.forProduct1(TransactionId.apply, t => t.value)
  implicit val transactionIdOrder: Ordering[TransactionId] = Ordering.by(_.value.bytes)

  val zero: TransactionId = TransactionId(Hash.zero)
  val length: Int         = Hash.length

  def random: TransactionId   = TransactionId(Hash.random)
  def generate: TransactionId = TransactionId(Hash.generate)

  def from(bytes: ByteString): Option[TransactionId] = {
    Hash.from(bytes).map(TransactionId.apply)
  }

  def hash(bytes: ByteString): TransactionId = {
    TransactionId(Hash.hash(bytes))
  }

  def hash(str: String): TransactionId = {
    TransactionId(Hash.hash(str))
  }
}
