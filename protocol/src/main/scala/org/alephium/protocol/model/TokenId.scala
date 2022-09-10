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
import org.alephium.util.Bytes.byteStringOrdering

final case class TokenId private (value: Hash) extends AnyVal with RandomBytes {
  def bytes: ByteString = value.bytes
}

object TokenId extends HashUtils[TokenId] {
  implicit val serde: Serde[TokenId]           = Serde.forProduct1(TokenId.apply, t => t.value)
  implicit val tokenIdOrder: Ordering[TokenId] = Ordering.by(_.bytes)

  def length: Int = Hash.length

  def generate: TokenId = TokenId(Hash.generate)

  def from(contractId: ContractId): TokenId = {
    TokenId(contractId.value)
  }

  def from(bytes: ByteString): Option[TokenId] = {
    Hash.from(bytes).map(TokenId.apply)
  }

  @inline def hash(bytes: Seq[Byte]): TokenId = TokenId(Hash.hash(bytes))

  @inline def hash(str: String): TokenId = hash(ByteString(str))

  @inline def unsafe(hash: Hash): TokenId = TokenId(hash)

  def zero: TokenId = TokenId(Hash.zero)
}
