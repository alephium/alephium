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

package org.alephium.protocol.message

import java.net.InetSocketAddress

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.compatible.Assertion

import org.alephium.crypto.SecP256K1Signature
import org.alephium.macros.EnumerationMacros
import org.alephium.protocol.{Hash, PublicKey, SignatureSchema}
import org.alephium.protocol.message.Payload.Code
import org.alephium.protocol.model._
import org.alephium.serde.{serialize, Serde, SerdeError}
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp, U256}

class PayloadSpec extends AlephiumSpec with NoIndexModelGenerators {
  implicit val ordering: Ordering[Code] = Ordering.by(Code.toInt(_))

  it should "index all payload types" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code]
    Code.values is AVector.from(codes)
  }

  it should "validate Hello message" in {
    val address            = new InetSocketAddress("127.0.0.1", 0)
    val (priKey1, pubKey1) = SignatureSchema.secureGeneratePriPub()
    val (priKey2, _)       = SignatureSchema.secureGeneratePriPub()
    val validBrokerInfo    = BrokerInfo.unsafe(CliqueId(pubKey1), 0, 1, address)

    val validInput  = Hello.unsafe(validBrokerInfo.interBrokerInfo, priKey1)
    val validOutput = Hello._deserialize(Hello.serde.serialize(validInput))
    validOutput.map(_.value) isE validInput

    val invalidInput  = Hello.unsafe(validBrokerInfo.interBrokerInfo, priKey2)
    val invalidOutput = Hello._deserialize(Hello.serde.serialize(invalidInput))
    invalidOutput.leftValue is a[SerdeError]

    info("invalid broker info")
    groupConfig.groups
    AVector(
      BrokerInfo.unsafe(CliqueId(pubKey1), -1, 1, address),
      BrokerInfo.unsafe(CliqueId(pubKey1), 2, 1, address),
      BrokerInfo.unsafe(CliqueId(pubKey1), 1, groupConfig.groups + 1, address),
      BrokerInfo.unsafe(CliqueId(pubKey1), 1, 2, address)
    ).foreach { invalidBrokerInfo =>
      val invalidInput  = Hello.unsafe(invalidBrokerInfo.interBrokerInfo, priKey1)
      val invalidOutput = Hello._deserialize(Hello.serde.serialize(invalidInput))
      invalidOutput.leftValue is a[SerdeError]
    }
  }

  it should "serialize/deserialize the Hello payload" in {
    import Hex._

    val publicKeyHex = hex"02a6df864a42ff65b12a46d09284213993a562a052059caa1d2fed594c369ee495"
    val signatureHex =
      hex"c2a56d568c070ed39aaac48891df094b9aaff196c2c48e57b20253a78c3c89083177fa42021855badcde3242085da559f74c7d81f250872af17eac7366374526"
    val cliqueId   = CliqueId(new PublicKey(publicKeyHex))
    val brokerInfo = BrokerInfo.unsafe(cliqueId, 0, 1, new InetSocketAddress("127.0.0.1", 0))
    val clientId   = "scala-alephium/v1.0.0/Linux"
    val signature  = new SecP256K1Signature(signatureHex)
    val hello =
      Hello.unsafe(
        clientId,
        TimeStamp.unsafe(1627484789657L),
        brokerInfo.interBrokerInfo,
        signature
      )

    verifySerde(hello) {
      // code id
      hex"00" ++
        // client id
        hex"1b7363616c612d616c65706869756d2f76312e302e302f4c696e7578" ++
        // timestamp
        hex"0000017aeda71b99" ++
        // clique id
        publicKeyHex ++
        // borker id
        hex"00" ++
        // groupNumPerBroker
        hex"01" ++
        // signature
        signatureHex
    }
  }

  it should "serialize/deserialize the Ping/Pong payload" in {
    import Hex._

    val requestId = RequestId.unsafe(1)
    val ping      = Ping(requestId, TimeStamp.unsafe(100))
    verifySerde(ping) {
      // code id
      hex"01" ++
        // request id
        hex"01" ++
        // timestamp
        hex"0000000000000064"
    }

    val pong = Pong(requestId)
    verifySerde(pong) {
      // code id
      hex"02" ++
        // request id
        hex"01"
    }
  }

  it should "serialize/deserialize the BlocksRequest/BlocksResponse payload" in {
    import Hex._

    val block1        = blockGen.sample.get
    val block2        = blockGen.sample.get
    val requestId     = RequestId.unsafe(1)
    val blocksRequest = BlocksRequest(requestId, AVector(block1.hash, block2.hash))
    verifySerde(blocksRequest) {
      // code id
      hex"03" ++
        // request id
        hex"01" ++
        // number of locators
        hex"02" ++
        // locator 1
        block1.hash.bytes ++
        // locator 2
        block2.hash.bytes
    }

    val blocksResponse = BlocksResponse(requestId, AVector(block1))
    verifySerde(blocksResponse) {
      // code id
      hex"04" ++
        // request id
        hex"01" ++
        // number of blocks
        hex"01" ++
        // block 1
        serialize(block1)
    }
  }

  it should "serialize/deserialize the HeadersRequest/HeadersResponse payload" in {
    import Hex._

    val block1         = blockGen.sample.get
    val block2         = blockGen.sample.get
    val requestId      = RequestId.unsafe(1)
    val headersRequest = HeadersRequest(requestId, AVector(block1.hash, block2.hash))
    verifySerde(headersRequest) {
      // code id
      hex"05" ++
        // request id
        hex"01" ++
        // number of locators
        hex"02" ++
        // locator 1
        block1.hash.bytes ++
        // locator 2
        block2.hash.bytes
    }

    val headersResponse = HeadersResponse(requestId, AVector(block1.header, block2.header))
    verifySerde(headersResponse) {
      // code id
      hex"06" ++
        // request id
        hex"01" ++
        // number of headers
        hex"02" ++
        // header 1
        serialize(block1.header) ++
        // header 2
        serialize(block2.header)
    }
  }

  it should "serialize/deserialize the InvRequest/InvResponse payload" in {
    import Hex._

    val block1     = blockGen.sample.get
    val block2     = blockGen.sample.get
    val requestId  = RequestId.unsafe(1)
    val invRequest = InvRequest(requestId, AVector(AVector(block1.hash, block2.hash)))
    verifySerde(invRequest) {
      // code id
      hex"07" ++
        // request id
        hex"01" ++
        // number of locator array
        hex"01" ++
        // number of locators in the first locator array
        hex"02" ++
        // locator 1
        block1.hash.bytes ++
        // locator 2
        block2.hash.bytes
    }

    val invResponse = InvResponse(requestId, AVector(AVector(block1.hash, block2.hash)))
    verifySerde(invResponse) {
      // code id
      hex"08" ++
        // request id
        hex"01" ++
        // number of hash arrays
        hex"01" ++
        // number of hashes in the first hash array
        hex"02" ++
        // hash 1
        serialize(block1.hash) ++
        // hash 2
        serialize(block2.hash)
    }
  }

  it should "serialize/deserialize the TxsRequest/TxsResponse payload" in {
    import Hex._

    val chainIndex    = ChainIndex.unsafe(0, 0)
    val chainIndexGen = Gen.const(chainIndex)
    val requestId     = RequestId.unsafe(1)
    val tx1           = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    val tx2           = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    val txsRequest    = TxsRequest(requestId, AVector((chainIndex, AVector(tx1.id, tx2.id))))
    verifySerde(txsRequest) {
      // code id
      hex"0e" ++
        // request id
        hex"01" ++
        // number of chains
        hex"01" ++
        // chain index
        hex"0000" ++
        // number of hashes
        hex"02" ++
        // tx1 hash
        serialize(tx1.id) ++
        // tx2 hash
        serialize(tx2.id)
    }

    val txsResponse = TxsResponse(requestId, AVector(tx1, tx2))
    verifySerde(txsResponse) {
      // code id
      hex"0f" ++
        // request id
        hex"01" ++
        // number of txs
        hex"02" ++
        // tx1
        serialize(tx1) ++
        // tx2
        serialize(tx2)
    }
  }

  it should "serialize/deserialize the NewBlock/NewHeader/NewInv/NewBlockHash/NewTxHashes payload" in {
    import Hex._

    val block1   = blockGen.sample.get
    val block2   = blockGen.sample.get
    val newBlock = NewBlock(block1)
    verifySerde(newBlock) {
      // code id
      hex"09" ++
        // block 1
        serialize(block1)
    }

    val newHeader = NewHeader(block1.header)
    verifySerde(newHeader) {
      // code id
      hex"0a" ++
        // header 1
        serialize(block1.header)
    }

    val newInv = NewInv(AVector(AVector(block1.hash, block2.hash)))
    verifySerde(newInv) {
      // code id
      hex"0b" ++
        // number of hash arrays
        hex"01" ++
        // number of hashes in the first hash array
        hex"02" ++
        // hash 1
        serialize(block1.hash) ++
        // hash 2
        serialize(block2.hash)
    }

    val newBlockHash = NewBlockHash(block1.hash)
    verifySerde(newBlockHash) {
      hex"0c" ++ serialize(block1.hash)
    }

    val txTemplate1 = transactionGen().sample.get.toTemplate
    val txTemplate2 = transactionGen().sample.get.toTemplate
    val chainIndex  = chainIndexGen.sample.get
    val newTxHashes = NewTxHashes(AVector((chainIndex, AVector(txTemplate1.id, txTemplate2.id))))
    verifySerde(newTxHashes) {
      // code id
      hex"0d" ++
        // number of chain
        hex"01" ++
        // chain index
        Hex.unsafe(s"0${chainIndex.from.value}0${chainIndex.to.value}") ++
        // number of hash
        hex"02" ++
        // tx1 hash
        serialize(txTemplate1.id) ++
        // tx2 hash
        serialize(txTemplate2.id)
    }

    info("NewTxHashes with invalid chain index")
    Payload
      .deserialize(
        // code id
        hex"0d" ++
          // number of chain
          hex"01" ++
          // invalid chain index
          hex"0f0f" ++
          // number of hash
          hex"02" ++
          // tx1 hash
          serialize(txTemplate1.id) ++
          // tx2 hash
          serialize(txTemplate2.id)
      )
      .leftValue is SerdeError.validation("Invalid ChainIndex in Tx payload")
  }

  it should "seder the snapshots properly" in new BlockSnapshotsFixture {
    implicit val basePath = "src/test/resources/message/payloads"

    implicit val serde = new Serde[Payload] {
      override def serialize(input: Payload)       = Payload.serialize(input)
      override def _deserialize(input: ByteString) = Payload._deserialize(input)
    }

    import Hex._

    info("Hello")

    implicit val interBrokerSerde: Serde[InterBrokerInfo] = InterBrokerInfo.serde

    val interBrokerInfo = BrokerInfo
      .unsafe(CliqueId(pubKey1), 0, 1, new InetSocketAddress("127.0.0.1", 0))
      .interBrokerInfo

    val clientId  = "scala-alephium/v9.0.0/Linux"
    val timestamp = TimeStamp.unsafe(1627484789657L)
    val signature = SignatureSchema.sign(interBrokerInfo.hash.bytes, privKey1)
    val hello     = Hello.unsafe(clientId, timestamp, interBrokerInfo, signature)

    hello.asInstanceOf[Payload].verify("hello")

    info("ping / pong")

    val chainIndex = ChainIndex.unsafe(0, 0)
    val requestId  = RequestId.unsafe(100)
    val ping       = Ping(requestId, TimeStamp.unsafe(1627484789657L))
    val pong       = Pong(requestId)

    ping.asInstanceOf[Payload].verify("ping")
    pong.asInstanceOf[Payload].verify("pong")

    info("blocks request / blocks response")

    val block1 = block()

    val tx1 = {
      val unsignedTx = unsignedTransaction(
        pubKey1,
        None,
        p2pkhOutput(
          55,
          hex"b03ce271334db24f37313cccf2d4aced9c6223d1378b1f472ec56f0b30aaac0f"
        )
      )

      inputSign(unsignedTx, privKey1)
    }

    val tx2 = {
      val tokenId1 = hex"342f94b2e48e687a3f985ac55658bcdddace8891919fc08d58b0db2255ca3822"
      val unsignedTx = unsignedTransaction(
        pubKey2,
        scriptOpt = None,
        p2pkhOutput(
          42,
          hex"c03ce271334db24f37313bbbf2d4aced9c6223d1378b1f472ec56f0b30aaac04",
          TimeStamp.unsafe(12345),
          additionalData = hex"55deff667f0096ffc024ff53d6017ff5",
          tokens = AVector((Hash.unsafe(tokenId1), U256.unsafe(2)))
        ),
        p2pkhOutput(
          100,
          hex"c6223d72ec56f13781334db24f37313fb03ce27cccf2d4aced9b1f40b30aaac0",
          tokens = AVector((Hash.unsafe(tokenId1), U256.unsafe(8)))
        )
      )

      inputSign(unsignedTx, privKey2)
    }

    val block2 = block(tx1, tx2)

    val blockRequest = BlocksRequest(requestId, AVector(block1.hash, block2.hash))
    blockRequest.asInstanceOf[Payload].verify("block-request")

    val blockResponse = BlocksResponse(requestId, AVector(block1, block2))
    blockResponse.asInstanceOf[Payload].verify("block-response")

    info("headers request / headers response")

    val headersRequest = HeadersRequest(requestId, AVector(block1.hash, block2.hash))
    headersRequest.asInstanceOf[Payload].verify("header-request")

    val headersResponse = HeadersResponse(requestId, AVector(block1.header, block2.header))
    headersResponse.asInstanceOf[Payload].verify("header-response")

    info("inventory request / inventory response")
    val invRequest = InvRequest(requestId, AVector(AVector(block1.hash, block2.hash)))
    invRequest.asInstanceOf[Payload].verify("inv-request")

    val invResponse = InvResponse(requestId, AVector(AVector(block1.hash, block2.hash)))
    invResponse.asInstanceOf[Payload].verify("inv-response")

    info("txs request / txs response")
    val txsRequest = TxsRequest(requestId, AVector((chainIndex, AVector(tx1.id, tx2.id))))
    txsRequest.asInstanceOf[Payload].verify("txs-request")

    val txsResponse = TxsResponse(requestId, AVector(tx1.toTemplate, tx2.toTemplate))
    txsResponse.asInstanceOf[Payload].verify("txs-response")

    info("new block")
    val newBlock = NewBlock(block2)
    newBlock.asInstanceOf[Payload].verify("new-block")

    info("new header")
    val newHeader = NewHeader(block2.header)
    newHeader.asInstanceOf[Payload].verify("new-header")

    info("new inventory")
    val newInv = NewInv(AVector(AVector(block1.hash, block2.hash)))
    newInv.asInstanceOf[Payload].verify("new-inv")

    info("new block hash")
    val newBlockHash = NewBlockHash(block2.hash)
    newBlockHash.asInstanceOf[Payload].verify("new-block-hash")

    info("new tx hashes")
    val newTxHashes = NewTxHashes(AVector((chainIndex, AVector(tx1.id, tx2.id))))
    newTxHashes.asInstanceOf[Payload].verify("new-tx-hashes")
  }

  private def verifySerde(payload: Payload)(blob: ByteString): Assertion = {
    Payload.serialize(payload) is blob
    Payload.deserialize(blob) isE payload
  }
}
