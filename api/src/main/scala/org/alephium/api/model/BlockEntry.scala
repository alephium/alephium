package org.alephium.api.model

import org.alephium.protocol.config.{ChainsConfig, GroupConfig}
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.util.{AVector, TimeStamp}

final case class BlockEntry(
      hash: String,
      timestamp: TimeStamp,
      chainFrom: Int,
      chainTo: Int,
      height: Int,
      deps: AVector[String],
      transactions: Option[AVector[Tx]]
  )
  object BlockEntry {

    def from(header: BlockHeader, height: Int)(implicit config: GroupConfig): BlockEntry = {
      BlockEntry(
        hash         = header.hash.toHexString,
        timestamp    = header.timestamp,
        chainFrom    = header.chainIndex.from.value,
        chainTo      = header.chainIndex.to.value,
        height       = height,
        deps         = header.blockDeps.map(_.toHexString),
        transactions = None
      )
    }

    def from(block: Block, height: Int)(implicit config: GroupConfig,
                                        chainsConfig: ChainsConfig): BlockEntry =
      from(block.header, height)
        .copy(transactions = Some(block.transactions.map(Tx.from(_, chainsConfig.networkType))))

  }
