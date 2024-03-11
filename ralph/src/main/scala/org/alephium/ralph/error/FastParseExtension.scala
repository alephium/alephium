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

import fastparse.{P, Pass}

object FastParseExtension {

  /** Throws [[CompilerError.ExpectedEndOfInput]] if the last character is not the end-of-input.
    *
    * FastParse's default equivalent is `fastparse.End`.
    */
  def endOfInput(fileURI: Option[java.net.URI])(implicit ctx: P[_]): P[Unit] = {
    val index = ctx.index
    if (ctx.input.isReachable(index)) {
      val character = ctx.input.slice(index, index + 1).head
      throw CompilerError.ExpectedEndOfInput(character, index, fileURI)
    } else {
      Pass(())
    }
  }
}
