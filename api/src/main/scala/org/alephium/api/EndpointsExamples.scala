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
import java.net.InetSocketAddress

import akka.util.ByteString
import sttp.tapir.EndpointIO.Example

import org.alephium.api.model._
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util._

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
// scalastyle:off magic.number
trait EndpointsExamples extends ErrorExamples {

  private val networkId = NetworkId(0)
  private val lockupScript =
    LockupScript.asset("1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n").get
  private val publicKey = PublicKey
    .from(Hex.unsafe("d1b70d2226308b46da297486adb6b4f1a8c1842cb159ac5ec04f384fe2d6f5da28"))
    .get
  private val unlockScript: UnlockScript = UnlockScript.p2pkh(publicKey)
  val address                            = Address.Asset(lockupScript)
  val contractAddress = Address.Contract(
    LockupScript.p2c(
      Hash.unsafe(Hex.unsafe("109b05391a240a0d21671720f62fe39138aaca562676053900b348a51e11ba25"))
    )
  )
  private val cliqueId          = CliqueId(publicKey)
  private val port              = 12344
  private val minerApiPort      = 12355
  private val wsPort            = 12366
  private val restPort          = 12377
  private val inetSocketAddress = new InetSocketAddress("1.2.3.4", port)
  private val inetAddress       = inetSocketAddress.getAddress
  private val peerAddress       = PeerAddress(inetAddress, restPort, wsPort, minerApiPort)
  private val peers             = AVector(peerAddress)
  private val twoAlph           = Amount(ALPH.oneAlph.mulUnsafe(U256.Two))
  private def alph(value: Int)  = Amount(ALPH.oneAlph.mulUnsafe(U256.unsafe(value)))
  private val height            = 42
  val balance                   = alph(10)
  val halfBalance               = alph(5)
  val signature = Signature
    .from(
      Hex.unsafe(
        "9e1a35b2931bd04e6780d01c36e3e5337941aa80f173cfe4f4e249c44ab135272b834c1a639db9c89d673a8a30524042b0469672ca845458a5a0cf2cad53221b"
      )
    )
    .get
  private val hash =
    Hash.from(Hex.unsafe("798e9e137aec7c2d59d9655b4ffa640f301f628bf7c365083bb255f6aa5f89ef")).get
  private val blockHash = BlockHash
    .from(Hex.unsafe("bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5"))
    .get
  val hexString  = "0ecd20654c2e2be708495853e8da35c664247040c00bd10b9b13"
  private val ts = TimeStamp.unsafe(1611041396892L)
  val txId =
    Hash.from(Hex.unsafe("503bfb16230888af4924aa8f8250d7d348b862e267d75d3147f1998050b6da69")).get
  val contractId =
    Hash.from(Hex.unsafe("1a21d30793fdf47bf07694017d0d721e94b78dffdc9c8e0b627833b66e5c75d8")).get
  private val tokens = AVector(
    Token(Hash.hash("token1"), alph(42).value),
    Token(Hash.hash("token2"), alph(1000).value)
  )
  val defaultDestinations = AVector(Destination(address, twoAlph, None, None))
  val moreSettingsDestinations = AVector(
    Destination(address, twoAlph, Some(tokens), Some(ts))
  )
  private val outputRef = OutputRef(hint = 23412, key = hash)

  private val tx = Tx(
    txId,
    AVector(Input.Asset(outputRef, serialize(unlockScript))),
    AVector(Output.Asset(amount = balance, address, tokens, ts, ByteString.empty)),
    minimalGas.value,
    defaultGasPrice.value
  )

  private val utxo = UTXO(
    outputRef,
    balance,
    tokens,
    ts,
    ByteString.empty
  )

  private val blockEntry = BlockEntry(
    blockHash,
    timestamp = ts,
    chainFrom = 1,
    chainTo = 2,
    height,
    deps = AVector(blockHash, blockHash),
    transactions = AVector(tx)
  )

  private val blockCandidate = BlockCandidate(
    fromGroup = 1,
    toGroup = 0,
    headerBlob = Hex.unsafe("aaaa"),
    target = BigInteger.ONE.shiftLeft(18),
    txsBlob = Hex.unsafe("bbbbbbbbbb")
  )

  private val blockSolution = BlockSolution(
    blockBlob = Hex.unsafe("bbbbbbbb"),
    miningCount = U256.unsafe(1000)
  )

  private val blockHeaderEntry = BlockHeaderEntry(
    hash = blockHash,
    timestamp = ts,
    chainFrom = 1,
    chainTo = 2,
    height = height,
    deps = AVector(blockHash, blockHash)
  )

  implicit val minerActionExamples: List[Example[MinerAction]] = List(
    Example[MinerAction](MinerAction.StartMining, Some("Start mining"), None),
    Example[MinerAction](MinerAction.StopMining, Some("Stop mining"), None)
  )

  implicit val misbehaviorActionExamples: List[Example[MisbehaviorAction]] =
    simpleExample(
      MisbehaviorAction.Unban(AVector(inetAddress))
    )

  implicit val nodeInfoExamples: List[Example[NodeInfo]] =
    simpleExample(
      NodeInfo(
        ReleaseVersion(0, 0, 1),
        NodeInfo.BuildInfo(
          "1.2.3",
          "47c01136d52cdf29062f6a3598a36ebc1e4dc57e"
        )
      )
    )

  implicit val getBlockHeaderEntryExample: List[Example[BlockHeaderEntry]] =
    simpleExample(blockHeaderEntry)

  implicit val selfCliqueExamples: List[Example[SelfClique]] =
    simpleExample(
      SelfClique(
        cliqueId,
        networkId,
        numZerosAtLeastInHash = 18,
        peers,
        selfReady = true,
        synced = true,
        groupNumPerBroker = 1,
        groups = 2
      )
    )

  implicit val interCliquePeerInfosExamples: List[Example[AVector[InterCliquePeerInfo]]] =
    simpleExample(
      AVector(
        InterCliquePeerInfo(
          cliqueId,
          brokerId = 1,
          groupNumPerBroker = 1,
          inetSocketAddress,
          isSynced = true
        )
      )
    )

  implicit val discoveredNeighborExamples: List[Example[AVector[BrokerInfo]]] =
    simpleExample(AVector(BrokerInfo.unsafe(cliqueId, 1, 1, inetSocketAddress)))

  implicit val misbehaviorsExamples: List[Example[AVector[PeerMisbehavior]]] =
    simpleExample(AVector(PeerMisbehavior(inetAddress, PeerStatus.Penalty(42))))

  implicit val txExamples: List[Example[Tx]] = simpleExample(tx)

  implicit val fetchResponseExamples: List[Example[FetchResponse]] =
    simpleExample(FetchResponse(AVector(AVector(blockEntry))))

  implicit val unconfirmedTransactionsExamples: List[Example[AVector[UnconfirmedTransactions]]] =
    simpleExample(AVector(UnconfirmedTransactions(0, 1, AVector(tx))))

  implicit val blockEntryExamples: List[Example[BlockEntry]] =
    simpleExample(blockEntry)

  implicit val blockEntryTemplateExamples: List[Example[BlockCandidate]] =
    simpleExample(blockCandidate)

  implicit val blockSolutionExamples: List[Example[BlockSolution]] =
    simpleExample(blockSolution)

  implicit val balanceExamples: List[Example[Balance]] = List(
    defaultExample(
      Balance(balance, balance.hint, halfBalance, halfBalance.hint, utxoNum = 3, None)
    ),
    moreSettingsExample(
      Balance(
        balance,
        balance.hint,
        halfBalance,
        halfBalance.hint,
        utxoNum = 3,
        Some("Result might not include all utxos and is maybe unprecise")
      )
    )
  )

  implicit val utxosExamples: List[Example[UTXOs]] = List(
    defaultExample(UTXOs(AVector(utxo), None)),
    moreSettingsExample(UTXOs(AVector(utxo), Some("Result might not contains all utxos")))
  )

  implicit val groupExamples: List[Example[Group]] =
    simpleExample(Group(group = 2))

  implicit val hashesAtHeightExamples: List[Example[HashesAtHeight]] =
    simpleExample(HashesAtHeight(headers = AVector(blockHash, blockHash, blockHash)))

  implicit val chainInfoExamples: List[Example[ChainInfo]] =
    simpleExample(ChainInfo(currentHeight = height))

  implicit val buildTransactionExamples: List[Example[BuildTransaction]] = List(
    defaultExample(
      BuildTransaction(
        publicKey,
        defaultDestinations,
        None,
        None,
        None
      )
    ),
    moreSettingsExample(
      BuildTransaction(
        publicKey,
        moreSettingsDestinations,
        Some(AVector(outputRef)),
        Some(minimalGas),
        Some(defaultGasPrice)
      )
    )
  )

  implicit val buildSweepAllTransactionExamples: List[Example[BuildSweepAllTransaction]] = List(
    defaultExample(
      BuildSweepAllTransaction(
        publicKey,
        address,
        None,
        None,
        None
      )
    ),
    moreSettingsExample(
      BuildSweepAllTransaction(
        publicKey,
        address,
        Some(ts),
        Some(minimalGas),
        Some(defaultGasPrice)
      )
    )
  )

  implicit val buildTransactionResultExamples: List[Example[BuildTransactionResult]] =
    simpleExample(BuildTransactionResult(unsignedTx = hexString, hash, fromGroup = 2, toGroup = 1))

  implicit val submitTransactionExamples: List[Example[SubmitTransaction]] =
    simpleExample(SubmitTransaction(unsignedTx = hexString, signature))

  implicit val buildMultisigAddressExample: List[Example[BuildMultisigAddress]] =
    simpleExample(
      BuildMultisigAddress(
        AVector(publicKey, publicKey),
        1
      )
    )

  implicit val buildMultisigAddressResultExample: List[Example[BuildMultisigAddress.Result]] =
    simpleExample(
      BuildMultisigAddress.Result(
        address
      )
    )

  implicit val buildMultisigTransactionExamples: List[Example[BuildMultisig]] = List(
    defaultExample(
      BuildMultisig(
        address,
        AVector(publicKey),
        defaultDestinations,
        None,
        None
      )
    ),
    moreSettingsExample(
      BuildMultisig(
        address,
        AVector(publicKey),
        moreSettingsDestinations,
        Some(minimalGas),
        Some(defaultGasPrice)
      )
    )
  )

  implicit val submitMultisigTransactionExamples: List[Example[SubmitMultisig]] =
    simpleExample(SubmitMultisig(unsignedTx = hexString, AVector(signature)))

  implicit val decodeTransactionExamples: List[Example[DecodeTransaction]] =
    simpleExample(DecodeTransaction(unsignedTx = hexString))

  implicit val txResultExamples: List[Example[TxResult]] =
    simpleExample(TxResult(txId, fromGroup = 2, toGroup = 1))

  implicit val txStatusExamples: List[Example[TxStatus]] =
    List[Example[TxStatus]](
      Example(Confirmed(blockHash, 0, 1, 2, 3), None, None),
      Example(MemPooled, None, Some("Tx is still in mempool")),
      Example(NotFound, None, Some("Cannot find tx with the id"))
    )

  implicit val compileScriptExamples: List[Example[Compile.Script]] =
    simpleExample(
      Compile.Script(
        code =
          s"TxScript Main { pub payable fn main() -> () { let token = Token(#36cdbfabca2d71622b6) token.withdraw(@${address.toBase58}, 1024) } }"
      )
    )

  implicit val compileContractExamples: List[Example[Compile.Contract]] =
    simpleExample(
      Compile.Contract(
        code =
          "TxContract Foo(bar: ByteVec) {\npub payable fn baz(amount: U256) -> () {\nissueToken!(amount)\n}}"
      )
    )

  implicit val compileResultExamples: List[Example[CompileResult]] =
    simpleExample(CompileResult(code = hexString))

  implicit val buildContractExamples: List[Example[BuildContract]] = List(
    defaultExample(BuildContract(publicKey, code = hexString)),
    moreSettingsExample(
      BuildContract(
        publicKey,
        hexString,
        Some(minimalGas),
        Some(defaultGasPrice),
        Some("#0ef875c5a01c48ec4c0332b1036cdbfabca2d71622b67c29ee32c0dce74f2dc7"),
        Some(twoAlph)
      )
    )
  )

  implicit val buildScriptExamples: List[Example[BuildScript]] = List(
    defaultExample(BuildScript(publicKey, code = hexString)),
    moreSettingsExample(
      BuildScript(
        publicKey,
        hexString,
        Some(minimalGas),
        Some(defaultGasPrice)
      )
    )
  )

  implicit val buildContractResultExamples: List[Example[BuildContractResult]] =
    simpleExample(
      BuildContractResult(
        unsignedTx = hexString,
        hash = hash,
        contractId = contractId,
        fromGroup = 2,
        toGroup = 2
      )
    )

  implicit val buildScriptResultExamples: List[Example[BuildScriptResult]] =
    simpleExample(
      BuildScriptResult(
        unsignedTx = hexString,
        hash = hash,
        fromGroup = 2,
        toGroup = 2
      )
    )

  implicit val contractStateExamples: List[Example[ContractStateResult]] =
    simpleExample(
      ContractStateResult(
        AVector(
          Val.I256(I256.from(-10)),
          Val.U256(U256.unsafe(10)),
          Val.True,
          Val.Address(contractAddress),
          Val.ByteVec(U256.Ten.toBytes)
        )
      )
    )

  implicit val exportFileExamples: List[Example[ExportFile]] =
    simpleExample(ExportFile("exported-blocks-file"))

  implicit val addressExamples: List[Example[Address.Asset]] =
    simpleExample(address)

  implicit val minerAddressesExamples: List[Example[MinerAddresses]] =
    simpleExample(MinerAddresses(AVector(address)))

  implicit val booleanExamples: List[Example[Boolean]] =
    simpleExample(true)

  implicit val verifySignatureExamples: List[Example[VerifySignature]] =
    simpleExample(VerifySignature(Hex.unsafe(hexString), signature, publicKey))
}
// scalastyle:on magic.number
