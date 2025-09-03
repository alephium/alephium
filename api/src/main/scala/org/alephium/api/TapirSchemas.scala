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

import java.math.BigInteger
import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import sttp.tapir.{FieldName, Schema, Validator}
import sttp.tapir.SchemaType.{SArray, SInteger, SProduct, SProductField, SString}

import org.alephium.api.model.{Address => ApiAddress, _}
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{Hash, PublicKey, Signature}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulContract}
import org.alephium.util.{AVector, I256, TimeStamp, U256}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
trait TapirSchemasLike {
  implicit def avectorSchema[T: Schema]: Schema[AVector[T]] = Schema(
    SArray(implicitly[Schema[T]])(_.toIterable)
  )

  implicit def optionAvectorSchema[T: Schema]: Schema[Option[AVector[T]]] = Schema.schemaForOption

  implicit val addressSchema: Schema[ApiAddress]               = Schema(SString()).format("address")
  implicit val protocolAddressSchema: Schema[Address]          = Schema(SString()).format("address")
  implicit val addressAssetSchema: Schema[Address.Asset]       = Schema(SString()).format("address")
  implicit val addressContractSchema: Schema[Address.Contract] = Schema(SString()).format("address")
  implicit val byteStringSchema: Schema[ByteString]   = Schema(SString()).format("hex-string")
  implicit val pulblicKeySchema: Schema[PublicKey]    = Schema(SString()).format("public-key")
  implicit val groupIndexSchema: Schema[GroupIndex]   = Schema(SInteger()).format("group-index")
  implicit val hashSchema: Schema[Hash]               = Schema(SString()).format("32-byte-hash")
  implicit val blockHashSchema: Schema[BlockHash]     = Schema(SString()).format("block-hash")
  implicit val pubScriptSchema: Schema[LockupScript]  = Schema(SString()).format("script")
  implicit val ScriptSchema: Schema[Script]           = Schema(SString()).format("script")
  implicit val signatureSchema: Schema[Signature]     = Schema(SString()).format("signature")
  implicit val timestampSchema: Schema[TimeStamp]     = Schema(SInteger()).format("int64")
  implicit val u256Schema: Schema[U256]               = Schema(SString()).format("uint256")
  implicit val i256Schema: Schema[I256]               = Schema(SString()).format("int256")
  implicit val amountSchema: Schema[Amount]           = Schema(SString()).format("uint256")
  implicit val amountHintSchema: Schema[Amount.Hint]  = Schema(SString()).format("x.x ALPH")
  implicit val gasBoxSchema: Schema[GasBox]           = Schema(SInteger()).format("gas")
  implicit val gasPriceSchema: Schema[GasPrice]       = Schema(SString()).format("uint256")
  implicit val bigIntegerSchema: Schema[BigInteger]   = Schema(SString()).format("bigint")
  implicit val inetAddressSchema: Schema[InetAddress] = Schema(SString()).format("inet-address")
  implicit val inetSocketAddressSchema: Schema[InetSocketAddress] =
    Schema[InetSocketAddress](
      SProduct(
        List(
          SProductField(
            FieldName("addr"),
            Schema.schemaForString,
            inetAddr => Some(inetAddr.getAddress().getHostAddress())
          ),
          SProductField(
            FieldName("port"),
            Schema.schemaForInt,
            inetAddr => Some(inetAddr.getPort())
          )
        )
      )
    ).format("inet-socket-address")
  implicit val cliqueIdSchema: Schema[CliqueId]          = Schema(SString()).format("clique-id")
  implicit val mnemonicSchema: Schema[Mnemonic]          = Schema(SString())
  implicit val mnemonicSizeSchema: Schema[Mnemonic.Size] = Schema(SInteger())
  implicit val nodeVersionSchema: Schema[ReleaseVersion] = Schema(SString()).format("semver")

  implicit val statefulContractSchema: Schema[StatefulContract] =
    Schema(SString()).format("contract")

  implicit val publicKeyTypeSchema: Schema[BuildTxCommon.PublicKeyType] =
    Schema(SString()).format("hex-string")

  implicit val apiKeySchema: Schema[ApiKey] =
    Schema(SString()).validate(Validator.minLength(32)).as[ApiKey]

  implicit val transactionIdSchema: Schema[TransactionId] = Schema(SString()).format("32-byte-hash")
  implicit val minerActionSchema: Schema[MinerAction]     = Schema(SString())

  implicit val buildSimpleTransferTxResultSchema: Schema[BuildSimpleTransferTxResult] =
    Schema.derived
  implicit val buildTransferTxResultSchema: Schema[BuildTransferTxResult] = Schema.derived

  implicit val tokenIdSchema: Schema[TokenId]                     = Schema.derived
  implicit val tokenSchema: Schema[Token]                         = Schema.derived
  implicit val addressAssetStateSchema: Schema[AddressAssetState] = Schema.derived
  implicit val simulationResultSchema: Schema[SimulationResult]   = Schema.derived
  implicit val buildSimpleExecuteScriptTxResultSchema: Schema[BuildSimpleExecuteScriptTxResult] =
    Schema.derived
  implicit val buildExecuteScriptTxResultSchema: Schema[BuildExecuteScriptTxResult] = Schema.derived

  implicit val buildSimpleDeployContractTxResultSchema: Schema[BuildSimpleDeployContractTxResult] =
    Schema.derived
  implicit val buildDeployContractTxResultSchema: Schema[BuildDeployContractTxResult] =
    Schema.derived

  implicit val destinationSchema: Schema[Destination]                     = Schema.derived
  implicit val outpuRefSchema: Schema[OutputRef]                          = Schema.derived
  implicit val buildTransferTxSchema: Schema[BuildTransferTx]             = Schema.derived
  implicit val buildDeployContractTxSchema: Schema[BuildDeployContractTx] = Schema.derived
  implicit val buildExecuteScriptTxSchema: Schema[BuildExecuteScriptTx]   = Schema.derived

  implicit val buildChainedTxSchema: Schema[BuildChainedTx] =
    Schema.oneOfUsingField[BuildChainedTx, String](
      _.Type,
      identity
    )(
      List(
        BuildChainedTransferTx.Type       -> Schema.derived[BuildChainedTransferTx],
        BuildChainedDeployContractTx.Type -> Schema.derived[BuildChainedDeployContractTx],
        BuildChainedExecuteScriptTx.Type  -> Schema.derived[BuildChainedExecuteScriptTx]
      ): _*
    )

  implicit lazy val valSchema: Schema[Val] = Schema.oneOfUsingField[Val, String](
    _.Type,
    identity
  )(
    List(
      ValBool.Type    -> Schema.derived[ValBool],
      ValI256.Type    -> Schema.derived[ValI256],
      ValU256.Type    -> Schema.derived[ValU256],
      ValByteVec.Type -> Schema.derived[ValByteVec],
      ValAddress.Type -> Schema.derived[ValAddress],
      ValArray.Type   -> Schema.derived[ValArray]
    ): _*
  )

  implicit val buildChainedTxResultSchema: Schema[BuildChainedTxResult] =
    Schema.oneOfUsingField[BuildChainedTxResult, String](
      _.Type,
      identity
    )(
      List(
        BuildChainedTransferTxResult.Type -> Schema.derived[BuildChainedTransferTxResult],
        BuildChainedDeployContractTxResult.Type -> Schema
          .derived[BuildChainedDeployContractTxResult],
        BuildChainedExecuteScriptTxResult.Type -> Schema.derived[BuildChainedExecuteScriptTxResult]
      ): _*
    )

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  implicit val multisigTypeSchema: Schema[MultiSigType] =
    Schema
      .string[MultiSigType]
      .validate(
        Validator.enumeration(List(MultiSigType.P2HMPK, MultiSigType.P2MPKH), v => Some(v.toString))
      )
}

object TapirSchemas extends TapirSchemasLike
