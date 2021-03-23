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
  def apply(networkType: NetworkType): AVector[(LockupScript, U256)] =
    networkType match {
      case Mainnet => mainnet
      case Devnet  => devnet
      case Testnet => testnet
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(LockupScript, U256)] = AVector(
    build("M17CgKt3PfMx16RqQmgs4na8vJnrsiCQPxnoGg9k3wQe2V", ALF.alf(Number.million), Mainnet),
    build("M1EKok85mnVphzXF6RoFfg29xjaaBvaUoL4zU8s8JZHuj3", ALF.alf(Number.million), Mainnet),
    build("M1Jt9rGcqqpC1HBoXYgDcefY6y7dPQdDtKXWJEd375U8H", ALF.alf(Number.million), Mainnet),
    build("M1HJLSRbyEJhvVNxgiAanCH36PZDfbgpDkL9MSoPT9uZp9", ALF.alf(Number.million), Mainnet)
  )

  private val testnet: AVector[(LockupScript, U256)] = AVector(
    build("T1DSVCXhFC8sFjXzuXWKcRfDfBuet6MPSoknijfKghqrq8", ALF.alf(Number.million), Testnet),
    build("T1Bz5Lri6ensLeYQaPujQKBnRSuxjwCRDsToHyavDxP6Jh", ALF.alf(Number.million), Testnet),
    build("T14RX14eXnNhE8XoB18msvzbTxVjEgpoAEPWGW1MzMseyk", ALF.alf(Number.million), Testnet),
    build("T1CVKEQ33CKfBoVx9S8RQALW3tGD4YN1J6n6428oqNkYzJ", ALF.alf(Number.million), Testnet)
  )

  private val devnet: AVector[(LockupScript, U256)] = AVector(
    build("D17B4ErFknfmCg381b52k8sKbsXS8RFD7piVpPBB1T2Y4Z", ALF.alf(Number.million), Devnet),
    build("D15f8wM4hVa9SCfzZGUTKCDF4jY29EsrCipjVkjJ35A54J", ALF.alf(Number.million), Devnet),
    build("D12EHAuV329gDw5Lu8bBXGj6CqKUTFZR52qNnnpGt5CiA6", ALF.alf(Number.million), Devnet)
  )
  // scalastyle:on magic.number

  private def build(
      addressRaw: String,
      amount: U256,
      networkType: NetworkType
  ): (LockupScript, U256) = {
    val address = Address
      .fromBase58(addressRaw, networkType)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw for $networkType"))
    (address.lockupScript, amount)
  }
}
