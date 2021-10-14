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

package org.alephium.api.model

import akka.util.ByteString

import org.alephium.protocol.model
import org.alephium.protocol.vm
import org.alephium.util

sealed trait Val

object Val {

  def from(value: vm.Val): Val = value match {
    case vm.Val.Bool(v)    => Bool(v)
    case vm.Val.I256(v)    => I256(v)
    case vm.Val.U256(v)    => U256(v)
    case vm.Val.ByteVec(v) => ByteVec(v)
    case vm.Val.Address(lockupScript) =>
      Address(model.Address.from(lockupScript))
  }

  @upickle.implicits.key("bool")
  final case class Bool(value: Boolean) extends Val

  @upickle.implicits.key("i256")
  final case class I256(value: util.I256) extends Val

  @upickle.implicits.key("u256")
  final case class U256(value: util.U256) extends Val

  @upickle.implicits.key("bytevec")
  final case class ByteVec(value: ByteString) extends Val

  @upickle.implicits.key("address")
  final case class Address(value: model.Address) extends Val

  val True: Bool  = Bool(true)
  val False: Bool = Bool(false)
}
