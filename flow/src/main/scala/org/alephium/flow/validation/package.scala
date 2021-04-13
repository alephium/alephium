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

package org.alephium.flow

import org.alephium.io.IOError

package object validation {
  type BlockValidationError = Either[IOError, InvalidBlockStatus]
  type TxValidationError    = Either[IOError, InvalidTxStatus]

  type ValidationResult[Invalid <: InvalidStatus, T] =
    Either[Either[IOError, Invalid], T]

  type HeaderValidationResult[T] = ValidationResult[InvalidHeaderStatus, T]
  type TxValidationResult[T]     = ValidationResult[InvalidTxStatus, T]
  type BlockValidationResult[T]  = ValidationResult[InvalidBlockStatus, T]
}
