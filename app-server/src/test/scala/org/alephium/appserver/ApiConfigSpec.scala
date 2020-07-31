package org.alephium.appserver

import java.io.File

import com.typesafe.config.ConfigFactory

import org.alephium.util.AlephiumSpec

class ApiConfigSpec extends AlephiumSpec {
  it should "load api config" in {
    val path   = getClass.getResource(s"/system_test.conf.tmpl").getPath
    val file   = new File(path)
    val config = ConfigFactory.parseFile(file)
    ApiConfig.load(config.getConfig("alephium.api")).isRight is true
  }
}
