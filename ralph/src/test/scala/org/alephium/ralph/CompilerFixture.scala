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

package org.alephium.ralph

import scala.util.Random

import akka.util.ByteString

import org.alephium.protocol.vm._
import org.alephium.util._

trait CompilerFixture { _: AlephiumSpec =>
  def replace(code: String): String = code.replace("$", "")
  def replaceFirst(code: String): String = {
    val i = index(code)
    code.substring(0, i) + code.substring(i + 1)
  }
  def index(code: String): Int = code.indexOf("$")

  def testContractError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, compileContract(_))
  }

  def testContractFullError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, compileContractFull(_))
  }

  def testMultiContractError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, Compiler.compileMultiContract(_))
  }

  def testTxScriptError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, Compiler.compileTxScript(_))
  }

  def testErrorT[T](
      code: String,
      message: String,
      f: String => Either[Compiler.Error, T]
  ): Compiler.Error = {
    val startIndex = index(code)
    val newCode    = replaceFirst(code)
    val endIndex   = index(newCode)
    val error      = f(replace(newCode)).leftValue

    error.message is message
    error.position is startIndex
    error.foundLength is (endIndex - startIndex)

    error
  }

  def methodSelectorOf(signature: String): MethodSelector = {
    MethodSelector(Method.Selector(DjbHash.intHash(ByteString.fromString(signature))))
  }

  def compileContract(input: String, index: Int = 0): Either[Compiler.Error, StatefulContract] =
    compileContractFull(input, index).map(_.debugCode)

  def compileContractFull(
      input: String,
      index: Int = 0
  ): Either[Compiler.Error, CompiledContract] = {
    try {
      Compiler.compileMultiContract(input) match {
        case Right(multiContract) =>
          var result = multiContract.genStatefulContract(index)(CompilerOptions.Default)
          if (Random.nextBoolean()) {
            multiContract.contracts.foreach(_.reset())
            result = multiContract.genStatefulContract(index)(CompilerOptions.Default)
          }
          Right(result)
        case Left(error) => throw error
      }
    } catch {
      case e: Compiler.Error => Left(e)
    }
  }
}
