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

import org.alephium.crypto.{Blake3, HashUtils}
import org.alephium.serde.{RandomBytes, Serde}

final case class BlockHash private (value: Blake3) extends AnyVal with RandomBytes {
  def bytes: ByteString = value.bytes
}

object BlockHash extends HashUtils[BlockHash] {
  implicit val serde: Serde[BlockHash] = Serde.forProduct1(BlockHash.apply, t => t.value)

  val zero: BlockHash = BlockHash(Blake3.zero)
  val length: Int     = Blake3.length

  def generate: BlockHash = BlockHash(Blake3.generate)

  def from(bytes: ByteString): Option[BlockHash] = {
    Blake3.from(bytes).map(BlockHash.apply)
  }

  def hash(bytes: Seq[Byte]): BlockHash = ???
  def hash(string: String): BlockHash   = ???

  def unsafe(str: ByteString): BlockHash = BlockHash(Blake3.unsafe(str))
}
