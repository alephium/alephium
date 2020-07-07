package org.alephium.util

import akka.util.ByteString

object DjbHash {
  // scalastyle:off magic.number
  def intHash(bytes: ByteString): Int = {
    var hash = 5381
    bytes.foreach { byte =>
      hash = ((hash << 5) + hash) + (byte & 0xFF)
    }
    hash
  }
  // scalastyle:on magic.number
}
