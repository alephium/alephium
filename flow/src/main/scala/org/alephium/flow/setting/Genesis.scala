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
import org.alephium.util.{AVector, U256}

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
    build0("M1HG1adtFdyw6Cw5LdJFtcxj5Sc79Rg1iV5WwyhAaQcxPo", ALF.alf(U256.Million).get, Mainnet),
    build0("M15hxs6Rj3Spho32a6Nhvr8FqmtqdoyXvxWxfEwcxEQKCL", ALF.alf(U256.Million).get, Mainnet),
    build0("M1Cxio4bhgrFXQsVLGGTK9iR4Uzk1qihtTy6B5zACdVhf8", ALF.alf(U256.Million).get, Mainnet),
    build0("M18qok6gsyGm4NcQL25JTFsUyN65SxNB9a6PqCjVHSuZsX", ALF.alf(U256.Million).get, Mainnet)
  )

  private val testnet: AVector[(LockupScript, U256)] = AVector(
    build1("T1AHLBD9WjNS83aX4pTxQDYiMXgcnSxXx3TCDZ1JrRK8tK", 100, Testnet),
    build1("T1E6WcDQHkXutd6vCiMpeBZtwD1UWGwS8U5MfGevQwS16v", 100, Testnet),
    build1("T1H1YgkDMm9pQiwJ489fmixDwji1GNCgXUp2MyHyULmmq1", 100, Testnet),
    build1("T15NDEYK6c5iARwVtQDx5K1Mi8xDZZH6HRiCphLMAxUgNQ", 100, Testnet)
  )

  private val devnet: AVector[(LockupScript, U256)] = AVector(
    build1("D1FVtCAYNzaByeBD6o3hzGNrcUzVMFbjRTioZQo93SHwVs", 100, Devnet),
    build1("D14XRkG89wntfY1ucHraiz6wCQCdmK36DwY6iRqmwq19pr", 100, Devnet),
    build1("D19xZMmeAnEPkwYLR2b4PS6ML3n6mvYAYzrZT9upegA6G8", 100, Devnet)
  )
  // scalastyle:on magic.number

  private def build0(addressRaw: String,
                     amount: U256,
                     networkType: NetworkType): (LockupScript, U256) = {
    val address = Address
      .fromBase58(addressRaw, networkType)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw for $networkType"))
    (address.lockupScript, amount)
  }

  private def build1(addressRaw: String,
                     amount: Long,
                     networkType: NetworkType): (LockupScript, U256) = {
    build0(addressRaw, U256.unsafe(amount), networkType)
  }
}
