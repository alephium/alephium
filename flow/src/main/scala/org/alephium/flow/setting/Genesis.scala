// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.setting

import org.alephium.protocol.ALF
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.model.NetworkType.{Devnet, Mainnet, Testnet}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Number, U256}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Genesis {
  def apply(networkType: NetworkType): AVector[(LockupScript.Asset, U256)] =
    networkType match {
      case Mainnet => mainnet
      case Devnet  => devnet
      case Testnet => testnet
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("M1532GfKdSQjmMN57o4Xt9v2ZnzVrphcjRpNu6ajknmY7B", ALF.alf(Number.million), Mainnet),
    build("M1CAmPx4Wm5zJmCp9oMYy4HTEhqB8Vez2epF9FJxQN6YQz", ALF.alf(Number.million), Mainnet),
    build("M1DxgAn21D1vk4mGA9uF3cY5qj3LK22PeA8WhJ4TaPkzqP", ALF.alf(Number.million), Mainnet),
    build("M19GCch12jtsnXCSEapK8yUaC7keJa4eYpDoLNVmKpr78k", ALF.alf(Number.million), Mainnet)
  )

  private val testnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("T13CCnCmV1zgDj43FtDVGT7eCxtMuUeYU5T8moewy8c2bg", ALF.alf(Number.million), Testnet),
    build("T1C1wgFWXfHt5LzVRdXG962rSqahjR821VYewVp7jbDvzc", ALF.alf(Number.million), Testnet),
    build("T1DJa7LiwyNJosMWeDX6MejZQhwHHyh5yF4VYghMVoC2q", ALF.alf(Number.million), Testnet),
    build("T1GJRkJkhhfb1mnJaKZ2MVHRVqzrDdfk4wegCTQj5fDR9T", ALF.alf(Number.million), Testnet)
  )

  private val devnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("D12Ffj2pjqghfzyoH6WPL6QEcjVD42g8DoE1JJu8TwLmzR", ALF.alf(Number.million), Devnet),
    build("D14AUQziA5Rsg9LZCcWzy6bLQdiEWojYG9y3GA93Jeavke", ALF.alf(Number.million), Devnet),
    build("D1BXVZ7TFvo6DUkoRdLirh5Zgfw6KkqM8b8KypfEacrQ6p", ALF.alf(Number.million), Devnet)
  )
  // scalastyle:on magic.number

  private def build(
      addressRaw: String,
      amount: U256,
      networkType: NetworkType
  ): (LockupScript.Asset, U256) = {
    val address = Address
      .asset(addressRaw, networkType)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw for $networkType"))
    (address.lockupScript, amount)
  }
}
