package org.alephium.flow.setting

import org.alephium.protocol.model.{Address, Block, NetworkType}
import org.alephium.protocol.model.NetworkType.Mainnet
import org.alephium.util.{AVector, U64}

trait Genesis {
  def broker: BrokerSetting
  def consensus: ConsensusSetting
  def genesisBalances: AVector[(Address, U64)]
  lazy val genesisBlocks: AVector[AVector[Block]] = {
    Configs.loadBlockFlow(genesisBalances)(broker, consensus)
  }
}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Genesis {
  def apply(networkType: NetworkType): AVector[(Address, U64)] =
    networkType match {
      case Mainnet => mainnet
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(Address, U64)] = AVector(
    build("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3", 100, Mainnet),
    build("1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc", 100, Mainnet),
    build("1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og", 100, Mainnet),
    build("131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob", 100, Mainnet)
  )
  // scalastyle:on magic.number

  private def build(address: String, amount: Long, networkType: NetworkType): (Address, U64) =
    (Address.fromBase58(address, networkType).get, U64.unsafe(amount))
}
