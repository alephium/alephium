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

package org.alephium.util

import java.io.{InputStreamReader, PrintWriter}
import java.nio.file.{Path, Paths}

object Files {
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  def copyFromResource(resourcePath: String, filePath: Path): Unit = {
    val in  = new InputStreamReader(getClass.getResourceAsStream(resourcePath))
    val out = new PrintWriter(filePath.toFile)

    val bufferSize = 1024
    val buffer     = Array.ofDim[Char](bufferSize)
    var len        = 0

    while ({ len = in.read(buffer); len } >= 0) {
      out.write(buffer, 0, len)
    }

    out.flush()
    out.close()
    in.close()
  }

  def homeDir: Path = Paths.get(System.getProperty("user.home"))

  def tmpDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))
}
