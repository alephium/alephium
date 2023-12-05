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

package org.alephium.api

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.util.AVector

object ApiUtils {
  def buildUnlockScript(
      lockupScript: LockupScript,
      pubKeys: AVector[PublicKey]
  ): Try[UnlockScript.P2MPKH] = {
    lockupScript match {
      case LockupScript.P2MPKH(pkHashes, m) =>
        if (m == pubKeys.length) {
          val indexes = pkHashes.zipWithIndex
          pubKeys
            .mapE { pub =>
              val pubHash = Hash.hash(pub.bytes)
              indexes.find { case (hash, _) => hash == pubHash } match {
                case Some((_, index)) => Right((pub, index))
                case None => Left(ApiError.BadRequest(s"Invalid public key: ${pub.toHexString}"))

              }
            }
            .map(UnlockScript.P2MPKH(_))
        } else {
          Left(
            ApiError.BadRequest(s"Invalid public key number. Expected ${m}, got ${pubKeys.length}")
          )
        }
      case _ =>
        Left(ApiError.BadRequest(s"Invalid lockup script"))
    }
  }
}
