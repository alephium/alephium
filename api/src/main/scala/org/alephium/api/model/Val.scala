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
  sealed trait Simple extends Val {
    def toVmVal: vm.Val
  }

  def from(value: vm.Val): Val = value match {
    case vm.Val.Bool(v)    => Bool(v)
    case vm.Val.I256(v)    => I256(v)
    case vm.Val.U256(v)    => U256(v)
    case vm.Val.ByteVec(v) => ByteVec(v)
    case vm.Val.Address(lockupScript) =>
      Address(model.Address.from(lockupScript))
  }

  @upickle.implicits.key("Bool")
  final case class Bool(value: Boolean) extends Simple {
    override def toVmVal: vm.Val = vm.Val.Bool(value)
  }

  @upickle.implicits.key("I256")
  final case class I256(value: util.I256) extends Simple {
    override def toVmVal: vm.Val = vm.Val.I256(value)
  }

  @upickle.implicits.key("U256")
  final case class U256(value: util.U256) extends Simple {
    override def toVmVal: vm.Val = vm.Val.U256(value)
  }

  @upickle.implicits.key("ByteVec")
  final case class ByteVec(value: ByteString) extends Simple {
    override def toVmVal: vm.Val = vm.Val.ByteVec(value)
  }

  @upickle.implicits.key("Address")
  final case class Address(value: model.Address) extends Simple {
    override def toVmVal: vm.Val = vm.Val.Address(value.lockupScript)
  }

  @upickle.implicits.key("Array")
  final case class Array(value: util.AVector[Val]) extends Val

  val True: Bool  = Bool(true)
  val False: Bool = Bool(false)
}
