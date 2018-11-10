package org.alephium.util

import java.net.InetSocketAddress

object Network {
  def parseAddresses(input: String): AVector[InetSocketAddress] = {
    val addresses = input.split(";").map { address =>
      val splitIndex    = address.indexOf(':')
      val (left, right) = address.splitAt(splitIndex)
      InetSocketAddress.createUnresolved(left, right.tail.toInt)
    }
    AVector(addresses: _*)
  }
}
