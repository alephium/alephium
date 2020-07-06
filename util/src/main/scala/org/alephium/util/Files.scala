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
