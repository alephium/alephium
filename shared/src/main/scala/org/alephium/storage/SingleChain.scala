package org.alephium.storage
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.Block

trait SingleChain extends BlockPool {

  def maxHeight: Int

  def add(block: Block, weight: Int): Boolean
}

sealed trait AddBlockResult

object AddBlockResult {
  case object Success                          extends AddBlockResult
  case object AlreadyExisted                   extends AddBlockResult
  case class MissingDeps(deps: Seq[Keccak256]) extends AddBlockResult
}
