package org.alephium.flow.platform

import org.alephium.flow.io.Storages

trait PlatformIO extends Storages.Config {
  def storages: Storages
}
