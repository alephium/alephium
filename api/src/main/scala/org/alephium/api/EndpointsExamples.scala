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

import java.net.InetSocketAddress

import sttp.tapir.EndpointIO.Example

import org.alephium.api.model._
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util._

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
// scalastyle:off magic.number
trait EndpointsExamples {

  private val networkType = NetworkType.Mainnet
  private val lockupScript =
    LockupScript.fromBase58("1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n").get
  private val unlockScript: UnlockScript = UnlockScript.p2pkh(PublicKey.generate)
  private val address                    = Address(networkType, lockupScript)
  private val cliqueId                   = CliqueId.generate
  private val port                       = 12344
  private val wsPort                     = 12366
  private val restPort                   = 12377
  private val inetSocketAddress          = new InetSocketAddress("1.2.3.4", port)
  private val inetAddress                = inetSocketAddress.getAddress
  private val peerAddress                = PeerAddress(inetAddress, restPort, wsPort)
  private val peers                      = AVector(peerAddress)
  private val balance                    = ALF.alf(U256.unsafe(1)).get
  private val height                     = 42
  private val signature                  = Signature.generate
  private def hash                       = Hash.generate
  private def blockHash                  = BlockHash.generate
  private val hexString                  = "0ecd20654c2e2be708495853e8da35c664247040c00bd10b9b13"

  private val blockEntry = BlockEntry(
    blockHash,
    timestamp = TimeStamp.now(),
    chainFrom = 1,
    chainTo   = 2,
    height,
    deps         = AVector(blockHash, blockHash),
    transactions = None
  )

  val mnemonicSizes: String = Mnemonic.Size.list.toSeq.map(_.value).mkString(", ")

  def simpleExample[T](t: T): List[Example[T]] = List(Example(t, None, None))

  implicit val selfCliqueExamples: List[Example[SelfClique]] =
    simpleExample(
      SelfClique(cliqueId,
                 networkType,
                 numZerosAtLeastInHash = 18,
                 peers,
                 synced            = true,
                 groupNumPerBroker = 1,
                 groups            = 2))

  implicit val interCliquePeerInfosExamples: List[Example[AVector[InterCliquePeerInfo]]] =
    simpleExample(
      AVector(InterCliquePeerInfo(cliqueId, brokerId = 1, inetSocketAddress, isSynced = true)))

  implicit val txsExamples: List[Example[AVector[Tx]]] =
    simpleExample(
      AVector(
        Tx(
          hash,
          AVector(Input(OutputRef(scriptHint = 23412, key = hash), Some(serialize(unlockScript)))),
          AVector(Output(amount = balance, address, Some(TimeStamp.unsafe(1234))))
        )))

  implicit val fetchResponseExamples: List[Example[FetchResponse]] =
    simpleExample(FetchResponse(AVector(blockEntry)))

  implicit val blockEntryExamples: List[Example[BlockEntry]] =
    simpleExample(blockEntry)

  implicit val balanceExamples: List[Example[Balance]] =
    simpleExample(Balance(balance, utxoNum = 3))

  implicit val groupExamples: List[Example[Group]] =
    simpleExample(Group(group = 2))

  implicit val hashesAtHeightExamples: List[Example[HashesAtHeight]] =
    simpleExample(HashesAtHeight(headers = AVector(blockHash, blockHash, blockHash)))

  implicit val chainInfoExamples: List[Example[ChainInfo]] =
    simpleExample(ChainInfo(currentHeight = height))

  implicit val buildTransactionResultExamples: List[Example[BuildTransactionResult]] =
    simpleExample(BuildTransactionResult(unsignedTx = hexString, hash, fromGroup = 2, toGroup = 1))

  implicit val sendTransactionExamples: List[Example[SendTransaction]] =
    simpleExample(SendTransaction(unsignedTx = hexString, signature))

  implicit val txResultExamples: List[Example[TxResult]] =
    simpleExample(TxResult(txId = Hash.generate, fromGroup = 2, toGroup = 1))

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
      ))

  implicit val compileResultExamples: List[Example[CompileResult]] =
    simpleExample(CompileResult(code = hexString))

  implicit val buildContractExamples: List[Example[BuildContract]] =
    simpleExample(BuildContract(fromKey = PublicKey.generate, code = hexString))

  implicit val buildContractResultExamples: List[Example[BuildContractResult]] =
    simpleExample(
      BuildContractResult(unsignedTx = hexString, hash = hash, fromGroup = 2, toGroup = 1))

  implicit val sendContractExamples: List[Example[SendContract]] =
    simpleExample(SendContract(code = hexString, tx = hexString, signature, fromGroup = 2))

  implicit val exportFileExamples: List[Example[ExportFile]] =
    simpleExample(ExportFile("exported-blocks-file"))

  implicit val minerAddressesExamples: List[Example[MinerAddresses]] =
    simpleExample(MinerAddresses(AVector(address)))

  implicit val booleanExamples: List[Example[Boolean]] =
    simpleExample(true)

  implicit val badRequestExamples: List[Example[ApiModel.Error]] =
    simpleExample(ApiModel.Error(-32700, "Parse error"))
}
// scalastyle:on magic.number
