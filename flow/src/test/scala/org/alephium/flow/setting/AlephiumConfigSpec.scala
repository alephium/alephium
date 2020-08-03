package org.alephium.flow.setting

import java.io.File
import java.net.InetSocketAddress

import scala.collection.immutable.ArraySeq

import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import org.alephium.util.AlephiumSpec

class AlephiumConfigSpec extends AlephiumSpec {
  it should "load alephium config" in {
    val path   = getClass.getResource(s"/system_test.conf.tmpl").getPath
    val file   = new File(path)
    val config = ConfigFactory.parseFile(file)
    AlephiumConfig.load(config.getConfig("alephium")).isRight is true
  }

  it should "load bootstrap config" in {
    import PureConfigUtils._

    case class Bootstrap(addresses: ArraySeq[InetSocketAddress])

    val expected = Bootstrap(
      ArraySeq(new InetSocketAddress("localhost", 1234), new InetSocketAddress("localhost", 4321)))

    ConfigSource
      .string("""{ addresses = ["localhost:1234", "localhost:4321"] }""")
      .load[Bootstrap] isE expected

    ConfigSource
      .string("""{ addresses = "localhost:1234,localhost:4321" }""")
      .load[Bootstrap] isE expected

    ConfigSource
      .string("""{ addresses = "" }""")
      .load[Bootstrap] isE Bootstrap(ArraySeq.empty)
  }
}
