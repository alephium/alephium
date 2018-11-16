package org.alephium.util

import java.net.InetSocketAddress

object Network {
  def parseAddresses(input: String): AVector[InetSocketAddress] = {
    if (input.isEmpty) AVector.empty
    else {
      val addresses = input.split(";").map { address =>
        val splitIndex    = address.indexOf(':')
        val (left, right) = address.splitAt(splitIndex)
        new InetSocketAddress(left, right.tail.toInt)
      }
      AVector(addresses: _*)
    }
  }
}
