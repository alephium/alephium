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

import fastparse.P

/** Typed compiler errors.
  */
sealed trait CompilerError extends Product {
  def message: String =
    productPrefix
}

object CompilerError {

  /** Creates a failed parser result.
    *
    * @param error
    *   the parser error or the error cause.
    * @param index
    *   location where this error occurred. `0` being the first character.
    * @param cut
    *   if true, disables back-tracking.
    * @param ctx
    *   FastParser context.
    *
    * @return
    *   A failed parser run.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(error: CompilerError, index: Int, cut: Boolean = true)(implicit
      ctx: P[_]
  ): P[Nothing] = {
    // `ctx.freshFailure()` can be used here but it clears the stack when `verboseFailures = true`.
    // Currently there is no obvious need to clear this stack.
    ctx.isSuccess = false
    // `verboseFailures = false` is equivalent to cut in this case.
    // Actual cut syntax `~/` used in parsers gets applied by macros to emit relevant code which will not work here.
    // Setting `ctx.verboseFailures = false` tells FastParse to stop collecting stack
    // for previous successful parsers and that `verboseFailures` is valid only for this `CompilerError`.
    // To enable collecting stack information set `cut = false`.
    if (cut) ctx.verboseFailures = false
    // set the error message
    ctx.setMsg(index, () => error.message)
    // add error message to stack
    ctx.failureStack = (error.message, index) :: ctx.failureStack
    // set the error index and return as failure.
    ctx.augmentFailure(index = index)
  }

  case object `an I256 or U256 value` extends CompilerError
  case object `an I256 value`         extends CompilerError
  case object `an U256 value`         extends CompilerError

  // FIXME: Naming this object as `an immutable variable` reports the following error:
  //        `object name does not match the regular expression '[A-Z][A-Za-z]*'.`
  //        Which naming is preferred?
  case object AnImmutableVariable extends CompilerError {
    override def message: String =
      "an immutable variable"
  }

  final case class NoMainStatementDefined(typeId: Ast.TypeId) extends CompilerError {
    override def message: String =
      s"""main statements for type `${typeId.name}`"""
  }

}
