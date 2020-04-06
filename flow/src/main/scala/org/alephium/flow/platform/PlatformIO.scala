package org.alephium.flow.platform

import org.alephium.flow.io.Storages

trait PlatformIO {
  def storages: Storages
}
