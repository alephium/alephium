package org.alephium.protocol.config

trait GroupConfig {

  def groups: Int

  def chainNum: Int = groups * groups

  def depsNum: Int = 2 * groups - 1
}
