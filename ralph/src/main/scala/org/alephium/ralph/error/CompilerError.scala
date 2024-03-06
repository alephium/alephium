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

package org.alephium.ralph.error

import fastparse.Parsed

import org.alephium.ralph.{Ast, SourceIndex}

/** Typed compiler errors. */
sealed trait CompilerError extends Product {
  def message: String =
    productPrefix
}

object CompilerError {

  // scalastyle:off null
  def apply(message: String, sourceIndex: Option[SourceIndex]): Default =
    Default(message, sourceIndex, null)
  // scalastyle:on null

  /** String only error message. */
  case object `an I256 or U256 value` extends CompilerError

  /** Formattable types can be converted to formatted error messages. */
  sealed trait FormattableError extends Exception with CompilerError {
    def title: String

    def position: Int

    def foundLength: Int

    def fileURI: Option[java.net.URI]

    /** Implement footer to have this String added to the footer of the formatted error message.
      *
      * [[FastParseError]] uses this to display traced log. Other error messages can use this to
      * display suggestions/hints for each error type.
      */
    def footer: Option[String] = None

    def toFormatter(program: String): CompilerErrorFormatter =
      CompilerErrorFormatter(this, program)

    def format(program: String): String =
      toFormatter(program).format(None)
  }

  /** ****** Section: Syntax Errors ******
    */
  sealed trait SyntaxError extends FormattableError {
    def title: String =
      "Syntax error"
  }

  object FastParseError {
    def apply(failure: Parsed.Failure): CompilerError.FastParseError =
      FastParseErrorUtil(failure.trace())
  }

  /** Errors produced by FastParse. */
  final case class FastParseError(
      position: Int,
      override val message: String,
      found: String,
      tracedMsg: String,
      program: String,
      fileURI: Option[java.net.URI]
  ) extends SyntaxError {
    override def foundLength: Int =
      found.length

    override def footer: Option[String] =
      Some(tracedMsg)

    def toFormatter(): CompilerErrorFormatter =
      super.toFormatter(program)
  }

  final case class `Expected an I256 value`(
      position: Int,
      found: BigInt,
      fileURI: Option[java.net.URI]
  ) extends SyntaxError {
    override def foundLength: Int =
      found.toString().length
  }

  final case class `Expected an U256 value`(
      position: Int,
      found: BigInt,
      fileURI: Option[java.net.URI]
  ) extends SyntaxError {
    override def foundLength: Int =
      found.toString().length
  }

  final case class `Expected an immutable variable`(position: Int, fileURI: Option[java.net.URI])
      extends SyntaxError {
    override def foundLength: Int =
      3 // "mut".length
  }

  final case class `Expected main statements`(
      typeId: Ast.TypeId,
      position: Int,
      fileURI: Option[java.net.URI]
  ) extends SyntaxError {
    override def message: String =
      s"""Expected main statements for type `${typeId.name}`"""

    override def foundLength: Int =
      1
  }

  final case class `Expected non-empty asset(s) for address`(
      position: Int,
      fileURI: Option[java.net.URI]
  ) extends SyntaxError {
    override def foundLength: Int =
      1
  }

  final case class `Expected else statement`(position: Int, fileURI: Option[java.net.URI])
      extends SyntaxError {
    override def foundLength: Int =
      1

    override def message: String =
      "Expected `else` statement"

    override def footer: Option[String] =
      Some(
        "Description: `if/else` expressions require both `if` and `else` statements to be complete."
      )
  }

  final case class ExpectedEndOfInput(found: Char, position: Int, fileURI: Option[java.net.URI])
      extends SyntaxError {
    override def foundLength: Int =
      1

    override def message: String =
      s"Expected end of input but found unexpected character '$found'"

    override def footer: Option[String] =
      Some(
        "Help: Ralph programs should end with a closing brace `}` to indicate the end of code block."
      )
  }

  /** ****** Section: Type Errors ******
    */
  sealed trait TypeError extends FormattableError {
    def title: String =
      "Type error"
  }

  final case class `Invalid byteVec`(byteVec: String, position: Int, fileURI: Option[java.net.URI])
      extends TypeError {
    override def foundLength: Int =
      byteVec.length
  }

  final case class `Invalid number`(number: String, position: Int, fileURI: Option[java.net.URI])
      extends TypeError {
    override def foundLength: Int =
      number.length
  }

  final case class `Invalid contract address`(
      address: String,
      position: Int,
      fileURI: Option[java.net.URI]
  ) extends TypeError {
    override def foundLength: Int =
      address.length
  }

  final case class `Invalid address`(address: String, position: Int, fileURI: Option[java.net.URI])
      extends TypeError {
    override def foundLength: Int =
      address.length
  }

  /** ****** Section: Default Error ****** This error is used when a specific error is not
    * available.
    */
  final case class Default(
      override val message: String,
      sourceIndex: Option[SourceIndex],
      cause: Throwable
  ) extends Exception(message, cause)
      with FormattableError {
    def title: String =
      "Compilation error"
    override val position: Int =
      sourceIndex.map(_.index).getOrElse(0)
    override val foundLength: Int =
      sourceIndex.map(_.width).getOrElse(0)
    override def fileURI: Option[java.net.URI] =
      sourceIndex.flatMap(_.fileURI)
  }
  object Default {
    // scalastyle:off null
    def apply(message: String, sourceIndex: Option[SourceIndex]): Default =
      Default(message, sourceIndex, null)
    // scalastyle:on null
  }
}
