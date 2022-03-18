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

package org.alephium.wallet.api.model

import org.alephium.crypto.wallet.Mnemonic

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class WalletRestore(
    password: String,
    mnemonic: Mnemonic,
    walletName: String,
    isMiner: Option[Boolean] = None,
    mnemonicPassphrase: Option[String] = None
)

final case class WalletRestoreResult(walletName: String)
