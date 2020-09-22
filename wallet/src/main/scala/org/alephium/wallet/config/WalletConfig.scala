package org.alephium.wallet.config

import java.nio.file.Path

import akka.http.scaladsl.model.Uri

import org.alephium.protocol.model.NetworkType

final case class WalletConfig(port: Int,
                              secretDir: Path,
                              networkType: NetworkType,
                              blockflow: WalletConfig.BlockFlow)

object WalletConfig {
  final case class BlockFlow(host: String, port: Int, groups: Int) {
    val uri: Uri = Uri(s"http://$host:$port")
  }
}
