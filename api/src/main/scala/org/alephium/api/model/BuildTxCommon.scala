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

package org.alephium.api.model

import akka.util.ByteString

import org.alephium.api.{badRequest, Try}
import org.alephium.crypto.{
  BIP340SchnorrPublicKey,
  ED25519PublicKey,
  SecP256K1PublicKey,
  SecP256R1PublicKey
}
import org.alephium.protocol.PublicKey
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{
  GasBox,
  GasPrice,
  LockupScript,
  PublicKeyLike,
  StatefulScript,
  UnlockScript
}
import org.alephium.serde.deserialize
import org.alephium.util.{AVector, Hex, TimeStamp, U256}

trait BuildTxCommon {
  def gasAmount: Option[GasBox]

  def gasPrice: Option[GasPrice]

  def targetBlockHash: Option[BlockHash]
}

object BuildTxCommon {
  sealed trait PublicKeyType
  object Default       extends PublicKeyType // SecP256K1
  object BIP340Schnorr extends PublicKeyType
  object GLSecP256K1   extends PublicKeyType
  object GLSecP256R1   extends PublicKeyType
  object GLED25519     extends PublicKeyType
  object GLWebAuthn    extends PublicKeyType

  trait FromPublicKey {
    def fromPublicKey: ByteString
    def fromPublicKeyType: Option[PublicKeyType]

    def isGrouplessAddress: Boolean = fromPublicKeyType match {
      case Some(BuildTxCommon.GLSecP256K1) => true
      case Some(BuildTxCommon.GLSecP256R1) => true
      case Some(BuildTxCommon.GLWebAuthn)  => true
      case Some(BuildTxCommon.GLED25519)   => true
      case _                               => false
    }

    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def getLockPair(
        groupIndex: Option[GroupIndex] = None
    )(implicit config: GroupConfig): Try[(LockupScript.Asset, UnlockScript)] =
      fromPublicKeyType match {
        case Some(BuildTxCommon.GLSecP256K1) =>
          grouplessLockPair(
            fromPublicKey,
            groupIndex,
            "SecP256K1",
            SecP256K1PublicKey.from(_).map(PublicKeyLike.SecP256K1.apply)
          )
        case Some(BuildTxCommon.GLSecP256R1) =>
          grouplessLockPair(
            fromPublicKey,
            groupIndex,
            "SecP256R1",
            SecP256R1PublicKey.from(_).map(PublicKeyLike.SecP256R1.apply)
          )
        case Some(BuildTxCommon.GLED25519) =>
          grouplessLockPair(
            fromPublicKey,
            groupIndex,
            "ED25519",
            ED25519PublicKey.from(_).map(PublicKeyLike.ED25519.apply)
          )
        case Some(BuildTxCommon.GLWebAuthn) =>
          grouplessLockPair(
            fromPublicKey,
            groupIndex,
            "WebAuthn",
            SecP256R1PublicKey.from(_).map(PublicKeyLike.WebAuthn.apply)
          )
        case Some(BuildTxCommon.BIP340Schnorr) => schnorrLockPair(fromPublicKey, groupIndex)
        case _                                 => p2pkhLockPair(fromPublicKey, groupIndex)
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def p2pkhLockPair(
      fromPublicKey: ByteString,
      groupOpt: Option[GroupIndex]
  )(implicit config: GroupConfig): Try[(LockupScript.Asset, UnlockScript)] = {
    PublicKey.from(fromPublicKey) match {
      case Some(publicKey) =>
        val lockupScript = LockupScript.p2pkh(publicKey)
        if (groupOpt.exists(_ != lockupScript.groupIndex)) {
          Left(
            badRequest(
              s"Mismatch between group in request (${groupOpt.get.value}) and SecP256K1 public key: ${Hex
                  .toHexString(fromPublicKey)}"
            )
          )
        } else {
          Right(lockupScript -> UnlockScript.p2pkh(publicKey))
        }
      case None =>
        Left(badRequest(s"Invalid SecP256K1 public key: ${Hex.toHexString(fromPublicKey)}"))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def schnorrLockPair(
      fromPublicKey: ByteString,
      groupOpt: Option[GroupIndex]
  )(implicit config: GroupConfig): Try[(LockupScript.Asset, UnlockScript)] = {
    BIP340SchnorrPublicKey.from(fromPublicKey) match {
      case Some(publicKey) =>
        val address = SchnorrAddress(publicKey)
        if (groupOpt.exists(_ != address.lockupScript.groupIndex)) {
          Left(
            badRequest(
              s"Mismatch between group in request (${groupOpt.get.value}) and BIP340Schnorr public key: ${Hex
                  .toHexString(fromPublicKey)}"
            )
          )
        } else {
          Right(address.lockupScript -> address.unlockScript)
        }
      case None =>
        Left(badRequest(s"Invalid BIP340Schnorr public key: ${Hex.toHexString(fromPublicKey)}"))
    }
  }

  def grouplessLockPair(
      fromPublicKey: ByteString,
      groupOpt: Option[GroupIndex],
      publicKeyType: String,
      convertToPublicKeyLike: ByteString => Option[PublicKeyLike]
  )(implicit config: GroupConfig): Try[(LockupScript.Asset, UnlockScript)] = {
    convertToPublicKeyLike(fromPublicKey) match {
      case Some(publicKeyLike) =>
        val group        = groupOpt.getOrElse(publicKeyLike.defaultGroup)
        val lockupScript = LockupScript.p2pk(publicKeyLike, group)
        Right(lockupScript -> UnlockScript.P2PK)
      case None =>
        Left(badRequest(s"Invalid $publicKeyType public key: ${Hex.toHexString(fromPublicKey)}"))
    }
  }

  def getAlphAndTokenAmounts(
      attoAlphAmount: Option[Amount],
      tokensAmount: Option[AVector[Token]]
  ): Either[String, (Option[U256], AVector[(TokenId, U256)])] = {
    val alphAmountOpt = attoAlphAmount.map(_.value)
    tokensAmount match {
      case None => Right((alphAmountOpt, AVector.empty))
      case Some(tokens) =>
        val amounts = tokens.foldE((alphAmountOpt, Map.empty[TokenId, U256])) {
          case ((Some(alphAmount), tokenList), Token(TokenId.alph, tokenAmount)) =>
            alphAmount
              .add(tokenAmount)
              .toRight("ALPH amount overflow")
              .map(v => (Some(v), tokenList))
          case ((None, tokenList), Token(TokenId.alph, tokenAmount)) =>
            Right((Some(tokenAmount), tokenList))
          case ((alphOpt, tokenList), Token(tokenId, tokenAmount)) =>
            if (tokenList.contains(tokenId)) {
              tokenList(tokenId)
                .add(tokenAmount)
                .toRight(s"Token $tokenId amount overflow")
                .map(v => (alphOpt, tokenList + (tokenId -> v)))
            } else {
              Right((alphOpt, tokenList + (tokenId -> tokenAmount)))
            }
        }
        amounts.map(v => (v._1, AVector.from(v._2.view.filter(_._2.nonZero))))
    }
  }

  def getTokenIssuanceInfo(
      issueTokenAmount: Option[Amount],
      issueTokenTo: Option[Address.Asset]
  ): Either[String, Option[(U256, Option[Address.Asset])]] = {
    (issueTokenAmount, issueTokenTo) match {
      case (None, Some(_)) =>
        Left("`issueTokenTo` is specified, but `issueTokenAmount` is not specified")
      case _ =>
        Right(issueTokenAmount.map { amount =>
          (amount.value, issueTokenTo)
        })
    }
  }

  final case class ScriptTxAmounts(
      approvedAlph: U256,
      estimatedAlph: U256,
      tokens: AVector[(TokenId, U256)]
  )

  object ScriptTxAmounts {
    def from(
        approvedAlph: U256,
        tokens: AVector[(TokenId, U256)]
    ): Either[String, ScriptTxAmounts] = {
      val estimatedTxOutputsLength = tokens.length + 1
      // Allocate extra dust amounts for potential fixed outputs as well as generated outputs
      val estimatedTotalDustAmount =
        dustUtxoAmount.mulUnsafe(U256.unsafe(estimatedTxOutputsLength * 2))
      approvedAlph
        .add(estimatedTotalDustAmount)
        .map { estimatedAlph =>
          ScriptTxAmounts(approvedAlph, estimatedAlph, tokens)
        }
        .toRight("ALPH amount overflow")
    }
  }

  trait ExecuteScriptTx extends BuildTxCommon {
    def bytecode: ByteString
    def attoAlphAmount: Option[Amount]
    def tokens: Option[AVector[Token]]
    def gasEstimationMultiplier: Option[Double]

    def getAmounts: Either[String, ScriptTxAmounts] = {
      BuildTxCommon.getAlphAndTokenAmounts(attoAlphAmount, tokens).flatMap {
        case (alphAmount, tokens) =>
          ScriptTxAmounts.from(alphAmount.getOrElse(U256.Zero), tokens)
      }
    }

    var statefulScript: Option[StatefulScript] = None
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    def decodeStatefulScript(): Either[String, StatefulScript] = {
      if (statefulScript.isDefined) {
        Right(statefulScript.get)
      } else {
        deserialize[StatefulScript](bytecode)
          .map { script =>
            statefulScript = Some(script)
            script
          }
          .left
          .map(serdeError => serdeError.getMessage)
      }
    }
  }

  trait DeployContractTx extends BuildTxCommon {
    def bytecode: ByteString
    def initialAttoAlphAmount: Option[Amount]
    def initialTokenAmounts: Option[AVector[Token]]
    def issueTokenAmount: Option[Amount]
    def issueTokenTo: Option[Address.Asset]

    def decodeBytecode(): Try[BuildDeployContractTx.Code] = {
      deserialize[BuildDeployContractTx.Code](bytecode).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
    }

    def getAmounts(implicit
        networkConfig: NetworkConfig
    ): Either[String, (U256, ScriptTxAmounts)] = {
      val hardfork = networkConfig.getHardFork(TimeStamp.now())
      for {
        amounts <- BuildTxCommon.getAlphAndTokenAmounts(initialAttoAlphAmount, initialTokenAmounts)
        contractDeposit <- getInitialAttoAlphAmount(amounts._1, hardfork)
        approvedAlph <- contractDeposit
          .add(issueTokenTo.map(_ => dustUtxoAmount).getOrElse(U256.Zero))
          .toRight("ALPH amount overflow")
        result <- ScriptTxAmounts.from(approvedAlph, amounts._2)
      } yield (contractDeposit, result)
    }
  }

  def getInitialAttoAlphAmount(
      amountOption: Option[U256],
      hardfork: HardFork
  ): Either[String, U256] = {
    val minimalContractDeposit = minimalContractStorageDeposit(hardfork)
    amountOption match {
      case Some(amount) =>
        if (amount >= minimalContractDeposit) { Right(amount) }
        else {
          Left(
            s"Expect ${Amount.toAlphString(minimalContractDeposit)} deposit to deploy a new contract"
          )
        }
      case None => Right(minimalContractDeposit)
    }
  }
}

trait GasInfo {
  def gasAmount: GasBox

  def gasPrice: GasPrice

  def gasFee: U256 = gasPrice * gasAmount
}

trait TransactionInfo {
  def txId: TransactionId

  def unsignedTx: String
}
