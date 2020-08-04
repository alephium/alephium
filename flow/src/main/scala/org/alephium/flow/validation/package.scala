package org.alephium.flow

import org.alephium.io.IOError

package object validation {
  private[validation] type BlockValidationError = Either[IOError, InvalidBlockStatus]
  private[validation] type TxValidationError    = Either[IOError, InvalidTxStatus]

  private[validation] type ValidationResult[Invalid <: InvalidStatus, T] =
    Either[Either[IOError, Invalid], T]

  private[validation] type HeaderValidationResult[T] = ValidationResult[InvalidHeaderStatus, T]
  private[validation] type TxValidationResult[T]     = ValidationResult[InvalidTxStatus, T]
  private[validation] type BlockValidationResult[T]  = ValidationResult[InvalidBlockStatus, T]
}
