package org.alephium.flow.network.broker

import scala.collection.mutable

import org.alephium.protocol.Hash
import org.alephium.util.AVector

trait DownloadTracker {
  val downloading: mutable.HashSet[Hash] = mutable.HashSet.empty

  def needToDownload(hash: Hash): Boolean

  def download(hashes: AVector[AVector[Hash]]): AVector[Hash] = {
    val toDownload = hashes.flatMap(_.filter(needToDownload))
    toDownload.foreach(downloading += _)
    toDownload
  }
}
