package org.alephium.flow.network.sync

import scala.collection.mutable

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.Hash
import org.alephium.util.{AVector, BaseActor}

trait DownloadTracker extends BaseActor {
  def blockflow: BlockFlow

  val downloading: mutable.HashSet[Hash] = mutable.HashSet.empty

  def needToDownload(hash: Hash): Boolean =
    !(blockflow.containsUnsafe(hash) || downloading.contains(hash))

  def download(hashes: AVector[AVector[Hash]]): Unit = {
    val toDownload = hashes.flatMap(_.filter(needToDownload))
    toDownload.foreach(downloading.addOne)
    sender() ! BrokerHandler.DownloadBlocks(toDownload)
  }

  def finalized(hash: Hash): Unit = {
    downloading.remove(hash)
    ()
  }
}
