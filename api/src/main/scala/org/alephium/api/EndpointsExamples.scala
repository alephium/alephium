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

import sttp.tapir.EndpointIO.Example

import org.alephium.api.model._
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util._

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
// scalastyle:off magic.number
trait EndpointsExamples extends ErrorExamples {

  private val networkType = NetworkType.Mainnet
  private val lockupScript =
    LockupScript.fromBase58("1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n").get
  private val publicKey = PublicKey
    .from(Hex.unsafe("d1b70d2226308b46da297486adb6b4f1a8c1842cb159ac5ec04f384fe2d6f5da28"))
    .get
  private val unlockScript: UnlockScript = UnlockScript.p2pkh(publicKey)
  private val address                    = Address(networkType, lockupScript)
  private val cliqueId                   = CliqueId(publicKey)
  private val port                       = 12344
  private val minerApiPort               = 12355
  private val wsPort                     = 12366
  private val restPort                   = 12377
  private val inetSocketAddress          = new InetSocketAddress("1.2.3.4", port)
  private val inetAddress                = inetSocketAddress.getAddress
  private val peerAddress                = PeerAddress(inetAddress, restPort, wsPort, minerApiPort)
  private val peers                      = AVector(peerAddress)
  private val balance                    = ALF.alf(U256.unsafe(1)).get
  private val height                     = 42
  private val signature = Signature
    .from(
      Hex.unsafe(
        "9e1a35b2931bd04e6780d01c36e3e5337941aa80f173cfe4f4e249c44ab135272b834c1a639db9c89d673a8a30524042b0469672ca845458a5a0cf2cad53221b"
      )
    )
    .get
  private def hash =
    Hash.from(Hex.unsafe("798e9e137aec7c2d59d9655b4ffa640f301f628bf7c365083bb255f6aa5f89ef")).get
  private def blockHash = BlockHash
    .from(Hex.unsafe("bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5"))
    .get
  private val hexString = "0ecd20654c2e2be708495853e8da35c664247040c00bd10b9b13"
  private val ts        = TimeStamp.unsafe(1611041396892L)
  private val txId =
    Hash.from(Hex.unsafe("503bfb16230888af4924aa8f8250d7d348b862e267d75d3147f1998050b6da69")).get
  private val tokens = AVector(
    Token(Hash.hash("token1"), U256.unsafe(42)),
    Token(Hash.hash("token2"), U256.unsafe(1000))
  )

  private val tx = Tx(
    txId,
    AVector(Input(OutputRef(scriptHint = 23412, key = hash), Some(serialize(unlockScript)))),
    AVector(Output(amount = balance, address, tokens, Some(ts)))
  )

  private val blockEntry = BlockEntry(
    blockHash,
    timestamp = ts,
    chainFrom = 1,
    chainTo = 2,
    height,
    deps = AVector(blockHash, blockHash),
    transactions = Some(AVector(tx))
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

  val mnemonicSizes: String = Mnemonic.Size.list.toSeq.map(_.value).mkString(", ")

  val minerActionExamples: List[Example[MinerAction]] = List(
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
        isMining = true
      )
    )

  implicit val selfCliqueExamples: List[Example[SelfClique]] =
    simpleExample(
      SelfClique(
        cliqueId,
        networkType,
        numZerosAtLeastInHash = 18,
        peers,
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

  implicit val txsExamples: List[Example[AVector[Tx]]] =
    simpleExample(
      AVector(
        tx
      )
    )

  implicit val fetchResponseExamples: List[Example[FetchResponse]] =
    simpleExample(FetchResponse(AVector(blockEntry)))

  implicit val blockEntryExamples: List[Example[BlockEntry]] =
    simpleExample(blockEntry)

  implicit val blockEntryTemplateExamples: List[Example[BlockCandidate]] =
    simpleExample(blockCandidate)

  implicit val blockSolutionExamples: List[Example[BlockSolution]] =
    simpleExample(blockSolution)

  implicit val balanceExamples: List[Example[Balance]] =
    simpleExample(Balance(balance, balance.divUnsafe(U256.Two), utxoNum = 3))

  implicit val groupExamples: List[Example[Group]] =
    simpleExample(Group(group = 2))

  implicit val hashesAtHeightExamples: List[Example[HashesAtHeight]] =
    simpleExample(HashesAtHeight(headers = AVector(blockHash, blockHash, blockHash)))

  implicit val chainInfoExamples: List[Example[ChainInfo]] =
    simpleExample(ChainInfo(currentHeight = height))

  implicit val buildTransactionExamples: List[Example[BuildTransaction]] =
    simpleExample(
      BuildTransaction(
        publicKey,
        AVector(Destination(address, U256.Two)),
        Some(ts),
        Some(GasBox.unsafe(1)),
        Some(GasPrice(U256.One))
      )
    )

  implicit val buildTransactionResultExamples: List[Example[BuildTransactionResult]] =
    simpleExample(BuildTransactionResult(unsignedTx = hexString, hash, fromGroup = 2, toGroup = 1))

  implicit val sendTransactionExamples: List[Example[SendTransaction]] =
    simpleExample(SendTransaction(unsignedTx = hexString, signature))

  implicit val txResultExamples: List[Example[TxResult]] =
    simpleExample(TxResult(txId, fromGroup = 2, toGroup = 1))

  implicit val txStatusExamples: List[Example[TxStatus]] =
    List[Example[TxStatus]](
      Example(Confirmed(blockHash, 0, 1, 2, 3), None, None),
      Example(MemPooled, None, Some("Tx is still in mempool")),
      Example(NotFound, None, Some("Cannot find tx with the id"))
    )

  implicit val compileExamples: List[Example[Compile]] =
    simpleExample(
      Compile(
        address,
        `type` = "contract",
        code =
          "TxContract Foo(bar: ByteVec) {\npub payable fn baz(amount: U256) -> () {\nissueToken!(amount)\n}}",
        state = Some("#0ef875c5a01c48ec4c0332b1036cdbfabca2d71622b67c29ee32c0dce74f2dc7")
      )
    )

  implicit val compileResultExamples: List[Example[CompileResult]] =
    simpleExample(CompileResult(code = hexString))

  implicit val buildContractExamples: List[Example[BuildContract]] =
    simpleExample(BuildContract(publicKey, code = hexString))

  implicit val buildContractResultExamples: List[Example[BuildContractResult]] =
    simpleExample(
      BuildContractResult(unsignedTx = hexString, hash = hash, fromGroup = 2, toGroup = 1)
    )

  implicit val sendContractExamples: List[Example[SendContract]] =
    simpleExample(SendContract(code = hexString, tx = hexString, signature, fromGroup = 2))

  implicit val exportFileExamples: List[Example[ExportFile]] =
    simpleExample(ExportFile("exported-blocks-file"))

  implicit val minerAddressesExamples: List[Example[MinerAddresses]] =
    simpleExample(MinerAddresses(AVector(address)))

  implicit val booleanExamples: List[Example[Boolean]] =
    simpleExample(true)
}
// scalastyle:on magic.number
