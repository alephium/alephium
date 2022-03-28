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

  //scalastyle:off magic.number
  val createContractEventIndex: Val  = Val.I256(I256.from(-1))
  val destroyContractEventIndex: Val = Val.I256(I256.from(-2))
  //scalastyle:on magic.number
}
