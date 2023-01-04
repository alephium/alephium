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

import scala.annotation.tailrec
import scala.util.Random

import org.alephium.protocol.{Hash, PrivateKey, PublicKey, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript

class GroupIndex(val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"

  @tailrec
  final def generateKey(implicit config: GroupConfig): (PrivateKey, PublicKey) = {
    val (privateKey, publicKey) = SignatureSchema.secureGeneratePriPub()
    val lockupScript            = LockupScript.p2pkh(Hash.hash(publicKey.bytes))
    if (lockupScript.groupIndex == this) {
      (privateKey, publicKey)
    } else {
      generateKey
    }
  }
}

object GroupIndex {
  val Zero: GroupIndex = new GroupIndex(0)

  def unsafe(group: Int)(implicit config: GroupConfig): GroupIndex = {
    assume(validate(group))
    new GroupIndex(group)
  }

  def from(group: Int)(implicit config: GroupConfig): Option[GroupIndex] = {
    if (validate(group)) {
      Some(new GroupIndex(group))
    } else {
      None
    }
  }

  @inline
  def validate(group: Int)(implicit config: GroupConfig): Boolean =
    0 <= group && group < config.groups

  def random(implicit config: GroupConfig): GroupIndex = {
    unsafe(Random.nextInt(config.groups))
  }
}
