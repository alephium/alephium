package org.alephium.util
import java.net.InetSocketAddress

class NetworkSpec extends AlephiumSpec {

  it should "parse addresses" in {
    val input     = "127.0.0.1:1000;127.0.0.1:1001;127.0.0.1:1002;127.0.0.1:1003"
    val addresses = Network.parseAddresses(input)
    addresses.length is 4
    addresses(0) is InetSocketAddress.createUnresolved("127.0.0.1", 1000)
    addresses(1) is InetSocketAddress.createUnresolved("127.0.0.1", 1001)
    addresses(2) is InetSocketAddress.createUnresolved("127.0.0.1", 1002)
    addresses(3) is InetSocketAddress.createUnresolved("127.0.0.1", 1003)
  }
}
