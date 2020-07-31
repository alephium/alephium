package org.alephium.flow.setting

import java.io.File

import com.typesafe.config.ConfigFactory

import org.alephium.util.AlephiumSpec

class AlephiumConfigSpec extends AlephiumSpec {
  it should "load api config" in {
    val path   = getClass.getResource(s"/system_test.conf.tmpl").getPath
    val file   = new File(path)
    val config = ConfigFactory.parseFile(file)
    AlephiumConfig.load(config.getConfig("alephium")).isRight is true
  }
}
