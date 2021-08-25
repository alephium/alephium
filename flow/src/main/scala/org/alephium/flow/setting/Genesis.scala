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
import org.alephium.protocol.model.{Address, NetworkId}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Number, U256}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Genesis {
  def apply(networkId: NetworkId): AVector[(LockupScript.Asset, U256)] =
    networkId.id match {
      case 0 => mainnet
      case 1 => testnet
      case 2 => devnet
      case _ => AVector.empty
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("1532GfKdSQjmMN57o4Xt9v2ZnzVrphcjRpNu6ajknmY7B", ALF.alf(Number.million)),
    build("1CAmPx4Wm5zJmCp9oMYy4HTEhqB8Vez2epF9FJxQN6YQz", ALF.alf(Number.million)),
    build("1DxgAn21D1vk4mGA9uF3cY5qj3LK22PeA8WhJ4TaPkzqP", ALF.alf(Number.million)),
    build("19GCch12jtsnXCSEapK8yUaC7keJa4eYpDoLNVmKpr78k", ALF.alf(Number.million))
  )

  private val testnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("13CCnCmV1zgDj43FtDVGT7eCxtMuUeYU5T8moewy8c2bg", ALF.alf(Number.million)),
    build("1C1wgFWXfHt5LzVRdXG962rSqahjR821VYewVp7jbDvzc", ALF.alf(Number.million)),
    build("1DJa7LiwyNJosMWeDX6MejZQhwHHyh5yF4VYghMVoC2q", ALF.alf(Number.million)),
    build("1GJRkJkhhfb1mnJaKZ2MVHRVqzrDdfk4wegCTQj5fDR9T", ALF.alf(Number.million))
  )

  private val devnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("12Ffj2pjqghfzyoH6WPL6QEcjVD42g8DoE1JJu8TwLmzR", ALF.alf(Number.million)),
    build("14AUQziA5Rsg9LZCcWzy6bLQdiEWojYG9y3GA93Jeavke", ALF.alf(Number.million)),
    build("1BXVZ7TFvo6DUkoRdLirh5Zgfw6KkqM8b8KypfEacrQ6p", ALF.alf(Number.million))
  )
  // scalastyle:on magic.number

  private def build(
      addressRaw: String,
      amount: U256
  ): (LockupScript.Asset, U256) = {
    val address = Address
      .asset(addressRaw)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw"))
    (address.lockupScript, amount)
  }
}
