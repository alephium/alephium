package org.alephium.flow.network.broker

import scala.collection.mutable

import org.alephium.protocol.Hash

trait DownloadTracker {
  def downloading: mutable.HashSet[Hash]
}
