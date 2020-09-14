package org.alephium.flow.setting

import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.model.NetworkType.{Devnet, Mainnet, Testnet}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, U64}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Genesis {
  def apply(networkType: NetworkType): AVector[(LockupScript, U64)] =
    networkType match {
      case Mainnet => mainnet
      case Devnet  => devnet
      case Testnet => testnet
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(LockupScript, U64)] = AVector(
    build("M1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3", 100, Mainnet),
    build("M1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc", 100, Mainnet),
    build("M1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og", 100, Mainnet),
    build("M131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob", 100, Mainnet)
  )

  private val devnet: AVector[(LockupScript, U64)] = AVector(
    build("D15eh7Qe3CC9YgQcY3bpfZ9z6mSsU1mDuKKY4ox5ZNx51E", 100, Devnet),
    build("D14GrSzgcEcJVRCjA8WE6VPaagr4csBvbuQUfpaCfDJnnF", 100, Devnet),
    build("D13zsJa9zidziyzjxkwgsVePxBbgb1hBUCdYyoRRHR8epm", 100, Devnet),
    build("D1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n", 100, Devnet)
  )

  private val testnet: AVector[(LockupScript, U64)] = AVector(
    build("T1J9XcQ5FsFfihNYMzdYKXoiZBTzsHQifzu7CKQfZPbwt1", 100, Testnet),
    build("T16Q9sJkSYW66HKeai8sJeEo2buKLdwnmvY7VXtZFVDCoT", 100, Testnet),
    build("T15phYy54YWvsLbnUcn9xQAp82PgKXWRKfFUmDUYC13Ecm", 100, Testnet),
    build("T17ad4SSso1f3trkUfmi1YHkNnEo7qnF6SA83tdNJD2Saa", 100, Testnet)
  )
  // scalastyle:on magic.number

  private def build(addressRaw: String,
                    amount: Long,
                    networkType: NetworkType): (LockupScript, U64) = {
    val address = Address
      .fromBase58(addressRaw, networkType)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw for $networkType"))
    (address.lockupScript, U64.unsafe(amount))
  }
}
