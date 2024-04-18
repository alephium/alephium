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

package org.alephium.protocol.vm

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.io.IOError
import org.alephium.protocol.ALPH
import org.alephium.protocol.model._
import org.alephium.serde.SerdeError
import org.alephium.util.{Hex, U256}
import org.alephium.util.TimeStamp

// scalastyle:off number.of.types
trait ExeFailure extends Product {
  def name: String = productPrefix
}

final case class CodeSizeTooLarge(currentSize: Int, maxSize: Int) extends ExeFailure {
  override def toString: String = {
    s"Code size $currentSize bytes is too large, max size: ${maxSize} bytes"
  }
}

final case class FieldsSizeTooLarge(currentSize: Int) extends ExeFailure {
  override def toString: String = {
    s"Fields size $currentSize bytes is too large, max size: ${maximalFieldSize} bytes"
  }
}

case object ExpectStatefulFrame     extends ExeFailure
case object StackOverflow           extends ExeFailure
case object StackUnderflow          extends ExeFailure
case object NegativeArgumentInStack extends ExeFailure

final case class TooManySignatures(extraLength: Int) extends ExeFailure {
  override def toString: String = s"$extraLength signature(s) remain unused"
}

final case class InvalidPublicKey(publicKeyBytes: ByteString) extends ExeFailure {
  override def toString: String = s"Invalid public key: ${Hex.toHexString(publicKeyBytes)}"
}

final case class SignedDataIsNot32Bytes(length: Int) extends ExeFailure {
  override def toString: String = s"Signed data bytes should have 32 bytes, get $length instead"
}

final case class InvalidSignatureFormat(signature: ByteString) extends ExeFailure {
  override def toString: String = s"Signature $signature is not in the correct format"
}

final case class InvalidSignature(
    publicKey: ByteString,
    message: ByteString,
    rawSignature: ByteString
) extends ExeFailure {
  override def toString: String =
    s"Verification failed for signature $rawSignature, public key: $publicKey, message: $message"
}

final case class InvalidTxInputIndex(index: BigInteger) extends ExeFailure {
  override def toString: String = s"Invalid tx input index $index"
}

case object NoTxInput extends ExeFailure

final case class TxInputAddressesAreNotIdentical(addresses: Set[Address.Asset]) extends ExeFailure {
  override def toString: String =
    s"Tx input addresses are not identical for `callerAddress` function: ${addresses.mkString(", ")}"
}

case object AccessTxInputAddressInContract extends ExeFailure {
  override def toString: String =
    "`txInputsSize` and `txInputAddress` functions are only allowed in TxScript"
}

case object LockTimeOverflow extends ExeFailure
final case class InvalidLockTime(timestamp: TimeStamp, blockTime: TimeStamp) extends ExeFailure {
  override def toString: String =
    s"Invalid lock time: $timestamp, it should be greater than blocktime: $blockTime"
}

final case class AbsoluteLockTimeVerificationFailed(lockUntil: TimeStamp, blockTime: TimeStamp)
    extends ExeFailure {
  override def toString: String =
    s"Absolute lock time verification failed. Lock time: $lockUntil, block time: $blockTime"
}

final case class RelativeLockTimeVerificationFailed(lockUntil: TimeStamp, blockTime: TimeStamp)
    extends ExeFailure {
  override def toString: String =
    s"Relative lock time verification failed. Lock time: $lockUntil, block time: $blockTime"
}

case object RelativeLockTimeExpectPersistedUtxo extends ExeFailure {
  override def toString: String = "UTXO for the relative lock time is not persisted yet"
}

final case class ArithmeticError(message: String) extends ExeFailure

final case class InvalidVarIndex(index: BigInteger, maxIndex: Int) extends ExeFailure {
  override def toString: String = s"Invalid var index: $index, max index is: $maxIndex"
}

final case class InvalidMutFieldIndex(index: BigInteger, mutFieldsLength: Int) extends ExeFailure {
  override def toString: String =
    s"Invalid mutable field index: $index, mutable fields length is: $mutFieldsLength"
}

final case class InvalidImmFieldIndex(index: Int, immFieldsLength: Int) extends ExeFailure {
  override def toString: String =
    s"Invalid immutable field index: $index, immutable fields length is: $immFieldsLength"
}

case object InvalidFieldLength extends ExeFailure

case object TooManyFields extends ExeFailure {
  override def toString: String = "Contract can not have more than 255 fields"
}
case object InvalidMutFieldType extends ExeFailure

case object EmptyMethods extends ExeFailure {
  override def toString: String = "Contract should have at least one method"
}

final case class InvalidType(expected: Val.Type, got: Val) extends ExeFailure {
  override def toString: String = s"Invalid type, expected: $expected, got: $got"
}

case object InvalidMethod                    extends ExeFailure
case object InvalidMethodModifierBeforeLeman extends ExeFailure
case object InvalidMethodModifierBeforeRhone extends ExeFailure
case object InvalidMethodModifierSinceRhone  extends ExeFailure

final case class InvalidMethodIndex(index: Int, methodLength: Int) extends ExeFailure {
  override def toString: String = s"Invalid method index $index, method length: $methodLength"
}

final case class InvalidMethodArgLength(got: Int, expect: Int) extends ExeFailure {
  override def toString: String = s"Invalid number of method arguments, got: $got, expect: $expect"
}

case object InvalidReturnLength extends ExeFailure

final case class InvalidExternalMethodReturnLength(expected: Int, got: Int) extends ExeFailure {
  override def toString: String =
    s"Invalid number of external method return values, got: $got, expect: $expected"
}

case object InvalidArgLength extends ExeFailure

final case class InvalidExternalMethodArgLength(expected: Int, got: Int) extends ExeFailure {
  override def toString: String =
    s"Invalid number of external method arguments, got: $got, expect: $expected"
}

case object InvalidLengthForEncodeInstr extends ExeFailure

case object ExternalPrivateMethodCall extends ExeFailure {
  override def toString: String = "Private method can not be called from outside of the contract"
}

case object AssertionFailed    extends ExeFailure
case object InvalidInstrOffset extends ExeFailure
case object PcOverflow         extends ExeFailure

case object NonEmptyReturnForMainFunction extends ExeFailure {
  override def toString: String = "Main function should not have return value"
}

final case class InvalidConversion(from: Val, to: Val.Type) extends ExeFailure {
  override def toString: String = s"Invalid conversion from $from to $to"
}

final case class SerdeErrorCreateContract(error: SerdeError) extends ExeFailure {
  override def toString: String = s"Deserialization error while creating contract: $error"
}

final case class NonExistContract(address: Address.Contract) extends ExeFailure {
  override def toString: String =
    s"Contract $address does not exist"
}

case object ContractDestructionShouldNotBeCalledFromSelf extends ExeFailure {
  override def toString: String =
    "`destroySelf` function should not be called from the same contract"
}

final case class PayToContractAddressNotInCallerTrace(address: Address.Contract)
    extends ExeFailure {
  override def toString: String =
    s"Pay to contract address $address is not allowed when this contract address is not in the call stack"
}

case object InvalidAddressTypeInContractDestroy extends ExeFailure {
  override def toString: String = "Pay to contract is not allowed before Leman upgrade"
}

case object ExpectNonPayableMethod extends ExeFailure {
  override def toString: String = "Expect non payable method in AssetScript"
}

case object ExpectStatefulContractObj extends ExeFailure {
  override def toString: String =
    "`callerInitialStateHash` or `callerCodeHash` functions can only be called from the contract"
}

case object NoBalanceAvailable extends ExeFailure {
  override def toString: String = {
    s"No balance available. To fix, enable approved assets with `@using(preapprovedAssets = true)`, " +
      s"or utilize contract assets with `@using(assetsInContract = true)`, " +
      s"or use both with `@using(preapprovedAssets = true, assetsInContract = true)`"
  }
}

final case class NotEnoughApprovedBalance(
    lockupScript: LockupScript,
    tokenId: TokenId,
    expected: U256,
    got: U256
) extends ExeFailure {
  override def toString: String = {
    val token = if (tokenId == TokenId.alph) "ALPH" else tokenId.toHexString
    s"Not enough approved balance for address ${Address.from(lockupScript)}, tokenId: $token, expected: $expected, got: $got"
  }
}

final case class NoAssetsApproved(address: Address.Asset) extends ExeFailure {
  override def toString: String = s"No assets approved from address ${address.toBase58}"
}

case object BalanceOverflow extends ExeFailure

final case class NoAlphBalanceForTheAddress(address: Address) extends ExeFailure {
  override def toString: String = s"No ALPH balance for the address ${address.toBase58}"
}

final case class NoTokenBalanceForTheAddress(tokenId: TokenId, address: Address)
    extends ExeFailure {
  override def toString: String =
    s"No balance for token ${tokenId.toHexString} for the address ${address.toBase58}"
}

case object InvalidBalances                    extends ExeFailure
case object BalanceErrorWhenSwitchingBackFrame extends ExeFailure

final case class LowerThanContractMinimalBalance(address: Address, amount: U256)
    extends ExeFailure {
  override def toString: String =
    s"Contract output contains ${ALPH.prettifyAmount(amount)}," +
      s"less than contract minimal balance ${ALPH.prettifyAmount(minimalAlphInContract)}"
}

case object UnableToPayGasFee extends ExeFailure {
  override def toString: String = "Not enough ALPH in the transaction to pay for gas fee"
}

final case class InvalidOutputBalances(
    lockupScript: LockupScript,
    tokenSize: Int,
    attoAlphAmount: U256
) extends ExeFailure {
  override def toString: String = {
    val address         = Address.from(lockupScript)
    val tokenDustAmount = dustUtxoAmount.mulUnsafe(U256.unsafe(tokenSize))
    val totalDustAmount = if (attoAlphAmount == U256.Zero) {
      tokenDustAmount
    } else {
      if (attoAlphAmount < tokenDustAmount) {
        tokenDustAmount
      } else {
        tokenDustAmount.addUnsafe(dustUtxoAmount)
      }
    }
    s"Invalid ALPH balance for address $address, expected ${ALPH.prettifyAmount(totalDustAmount)}, " +
      s"got ${ALPH.prettifyAmount(attoAlphAmount)}, you need to transfer more ALPH to this address"
  }
}

final case class InvalidTokenNumForContractOutput(address: Address, tokenNum: Int)
    extends ExeFailure {
  override def toString: String =
    s"Invalid token number for contract ${address.toBase58}: $tokenNum, max token number is $maxTokenPerContractUtxo"
}

case object InvalidTokenId                              extends ExeFailure
case object InvalidContractId                           extends ExeFailure
case object ExpectAContract                             extends ExeFailure
case object OutOfGas                                    extends ExeFailure
case object GasOverflow                                 extends ExeFailure
case object GasOverPaid                                 extends ExeFailure
case object ContractPoolOverflow                        extends ExeFailure
case object ContractFieldOverflow                       extends ExeFailure
final case class ContractLoadDisallowed(id: ContractId) extends ExeFailure
case object ContractAssetAlreadyInUsing extends ExeFailure {
  override def toString: String = {
    s"Contract assets can only be loaded once per transaction, to prevent reentrancy attacks! Rhone upgrade will support method-level reentrancy protection."
  }
}
case object ContractAssetAlreadyFlushed extends ExeFailure
case object FunctionReentrancy extends ExeFailure {
  override def toString: String = {
    s"A function using contract assets can only be called once per transaction, to prevent reentrancy attacks!"
  }
}

final case class ContractAssetUnloaded(address: Address.Contract) extends ExeFailure {
  override def toString: String = {
    s"Assets for contract $address is not loaded, please annotate the function with `@using(assetsInContract = true)`"
  }
}

case object EmptyContractAsset extends ExeFailure {
  override def toString: String =
    s"The contract's asset(s) have been used up, but a minimum of ${ALPH.prettifyAmount(dustUtxoAmount)} ALPH is required"
}

case object NoCaller extends ExeFailure {
  override def toString: String = "The current method does not have a caller"
}

final case class NegativeTimeStamp(millis: Long) extends ExeFailure {
  override def toString: String = s"Negative timestamp $millis"
}

final case class InvalidBlockTarget(value: BigInteger) extends ExeFailure {
  override def toString: String = s"Invalid block target $value"
}

case object InvalidBytesSliceArg extends ExeFailure
case object InvalidBytesSize     extends ExeFailure
case object InvalidSizeForZeros  extends ExeFailure

final case class SerdeErrorByteVecToAddress(bytes: ByteString, error: SerdeError)
    extends ExeFailure {
  override def toString: String =
    s"Failed to deserialize ${Hex.toHexString(bytes)} to address: $error"
}

case object FailedInRecoverEthAddress extends ExeFailure

case object UnexpectedRecursiveCallInMigration extends ExeFailure {
  override def toString: String =
    "Can not migrate a contract that is calling its own migration method"
}

case object UnableToMigratePreLemanContract extends ExeFailure

final case class InvalidAssetAddress(address: Address) extends ExeFailure {
  override def toString: String = s"Invalid asset address ${address.toBase58}"
}

final case class ContractAlreadyExists(address: Address.Contract) extends ExeFailure {
  override def toString: String = s"Contract $address already exists"
}
case object NoBlockHashAvailable extends ExeFailure
case object DebugIsNotSupportedForMainnet extends ExeFailure {
  override def toString: String = "Debug is not supported for mainnet"
}
case object DebugMessageIsEmpty extends ExeFailure
case object ZeroContractId extends ExeFailure {
  override def toString: String = s"Can not create contract with id ${ContractId.zero.toHexString}"
}
case object BurningAlphNotAllowed extends ExeFailure {
  override def toString: String = "Burning ALPH is not allowed for `burnToken` function"
}

final case class UncaughtKeyNotFoundError(error: IOError.KeyNotFound) extends ExeFailure
final case class UncaughtSerdeError(error: IOError.Serde)             extends ExeFailure

sealed trait BreakingInstr                                                  extends ExeFailure
final case class InactiveInstr[-Ctx <: StatelessContext](instr: Instr[Ctx]) extends BreakingInstr
final case class PartiallyActiveInstr[-Ctx <: StatelessContext](instr: Instr[Ctx])
    extends BreakingInstr

final case class InvalidErrorCode(errorCode: U256) extends ExeFailure
final case class AssertionFailedWithErrorCode(contractIdOpt: Option[ContractId], errorCode: Int)
    extends ExeFailure {
  override def toString: String = {
    contractIdOpt match {
      case Some(contractId) =>
        val contractAddressString = Address.contract(contractId).toBase58
        s"Assertion Failed in Contract @ $contractAddressString, Error Code: $errorCode"
      case None =>
        s"Assertion Failed in TxScript, Error Code: $errorCode"
    }
  }
}

sealed trait IOFailure extends Product {
  def error: IOError
  def name: String = productPrefix
}
final case class IOErrorUpdateState(error: IOError)         extends IOFailure
final case class IOErrorRemoveContract(error: IOError)      extends IOFailure
final case class IOErrorRemoveContractAsset(error: IOError) extends IOFailure
final case class IOErrorLoadContract(error: IOError)        extends IOFailure
final case class IOErrorMigrateContract(error: IOError)     extends IOFailure
final case class IOErrorWriteLog(error: IOError)            extends IOFailure
