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
    build("M1HG1adtFdyw6Cw5LdJFtcxj5Sc79Rg1iV5WwyhAaQcxPo", ALF.alf(Number.million), Mainnet),
    build("M15hxs6Rj3Spho32a6Nhvr8FqmtqdoyXvxWxfEwcxEQKCL", ALF.alf(Number.million), Mainnet),
    build("M1Cxio4bhgrFXQsVLGGTK9iR4Uzk1qihtTy6B5zACdVhf8", ALF.alf(Number.million), Mainnet),
    build("M18qok6gsyGm4NcQL25JTFsUyN65SxNB9a6PqCjVHSuZsX", ALF.alf(Number.million), Mainnet)
  )

  private val testnet: AVector[(LockupScript, U256)] = AVector(
    build("T1AHLBD9WjNS83aX4pTxQDYiMXgcnSxXx3TCDZ1JrRK8tK", ALF.alf(Number.million), Testnet),
    build("T1E6WcDQHkXutd6vCiMpeBZtwD1UWGwS8U5MfGevQwS16v", ALF.alf(Number.million), Testnet),
    build("T1H1YgkDMm9pQiwJ489fmixDwji1GNCgXUp2MyHyULmmq1", ALF.alf(Number.million), Testnet),
    build("T15NDEYK6c5iARwVtQDx5K1Mi8xDZZH6HRiCphLMAxUgNQ", ALF.alf(Number.million), Testnet)
  )

  private val devnet: AVector[(LockupScript, U256)] = AVector(
    build("D1FVtCAYNzaByeBD6o3hzGNrcUzVMFbjRTioZQo93SHwVs", ALF.alf(Number.million), Devnet),
    build("D14XRkG89wntfY1ucHraiz6wCQCdmK36DwY6iRqmwq19pr", ALF.alf(Number.million), Devnet),
    build("D19xZMmeAnEPkwYLR2b4PS6ML3n6mvYAYzrZT9upegA6G8", ALF.alf(Number.million), Devnet)
  )
  // scalastyle:on magic.number

  private def build(addressRaw: String,
                    amount: U256,
                    networkType: NetworkType): (LockupScript, U256) = {
    val address = Address
      .fromBase58(addressRaw, networkType)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw for $networkType"))
    (address.lockupScript, amount)
  }
}
