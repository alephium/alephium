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

sealed trait Val {
  def flattenSize(): Int
  val `type`: String
}

object Val {
  sealed trait Primitive extends Val {
    def toVmVal: vm.Val
    def flattenSize(): Int = 1
  }

  def from(value: vm.Val): Val = value match {
    case vm.Val.Bool(v)    => ValBool(v)
    case vm.Val.I256(v)    => ValI256(v)
    case vm.Val.U256(v)    => ValU256(v)
    case vm.Val.ByteVec(v) => ValByteVec(v)
    case vm.Val.Address(lockupScript) =>
      ValAddress(model.Address.from(lockupScript))
  }

  val True: ValBool  = ValBool(true)
  val False: ValBool = ValBool(false)
}

@upickle.implicits.key("Bool")
final case class ValBool(value: Boolean) extends Val.Primitive {
  override def toVmVal: vm.Val = vm.Val.Bool(value)
  val `type`: String           = ValBool.`type`
}
object ValBool {
  val `type` = "Bool"
}

@upickle.implicits.key("I256")
final case class ValI256(value: util.I256) extends Val.Primitive {
  override def toVmVal: vm.Val = vm.Val.I256(value)
  val `type`: String           = ValI256.`type`
}
object ValI256 {
  val `type` = "I256"
}

@upickle.implicits.key("U256")
final case class ValU256(value: util.U256) extends Val.Primitive {
  override def toVmVal: vm.Val = vm.Val.U256(value)
  val `type`: String           = ValU256.`type`
}
object ValU256 {
  val `type` = "U256"
}

@upickle.implicits.key("ByteVec")
final case class ValByteVec(value: ByteString) extends Val.Primitive {
  override def toVmVal: vm.Val = vm.Val.ByteVec(value)
  val `type`: String           = ValByteVec.`type`
}
object ValByteVec {
  val `type` = "ByteVec"
}

@upickle.implicits.key("Address")
final case class ValAddress(value: model.Address) extends Val.Primitive {
  override def toVmVal: vm.Val = vm.Val.Address(value.lockupScript)
  val `type`: String           = ValAddress.`type`
}
object ValAddress {
  val `type` = "Address"
}

@upickle.implicits.key("Array")
final case class ValArray(value: util.AVector[Val]) extends Val {
  def flattenSize(): Int = value.sumBy(_.flattenSize())
  val `type`: String     = ValArray.`type`
}
object ValArray {
  val `type` = "Array"
}
