package org.alephium.protocol.config

trait GroupConfigFixture { self =>
  def groups: Int

  implicit lazy val groupConfig: GroupConfig = new GroupConfig {
    def groups: Int = self.groups
  }
}

object GroupConfigFixture {
  trait Default extends GroupConfigFixture {
    val groups: Int = 3
  }
}
