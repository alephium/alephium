package org.alephium.protocol.config

trait GroupConfigFixture { self =>
  def groups: Int

  implicit val config = new GroupConfig {
    def groups: Int = self.groups
  }
}
