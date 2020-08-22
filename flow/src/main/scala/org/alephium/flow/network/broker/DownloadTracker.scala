package org.alephium.flow.network.broker

import scala.collection.mutable

import org.alephium.protocol.Hash

trait DownloadTracker {
  val downloading: mutable.HashSet[Hash] = mutable.HashSet.empty
}
