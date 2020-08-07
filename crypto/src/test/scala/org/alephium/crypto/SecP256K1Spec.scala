package org.alephium.crypto

import java.io.ByteArrayInputStream
import java.math.BigInteger

import akka.util.ByteString
import org.bouncycastle.asn1.{ASN1Integer, ASN1StreamParser, DLSequence}

import org.alephium.util.{AlephiumSpec, AVector}
import org.alephium.util.Hex._

class SecP256K1Spec extends AlephiumSpec {
  def nonCanonical(signature: SecP256K1Signature): SecP256K1Signature = {
    val (r, s) = signature.bytes.splitAt(32)
    val ss     = SecP256K1.params.getN.subtract(new BigInteger(1, s.toArray))
    SecP256K1Signature.unsafe(
      r ++ (ByteString.fromArrayUnsafe(ss.toByteArray).dropWhile(_ equals 0.toByte)))
  }

  it should "derive public key" in {
    def test(rawPrivateKey: ByteString, rawPublicKey: ByteString) = {
      val privateKey = SecP256K1PrivateKey.unsafe(rawPrivateKey)
      val publicKey  = SecP256K1PublicKey.unsafe(rawPublicKey)
      privateKey.publicKey is publicKey
    }

    // see https://en.bitcoin.it/wiki/Technical_background_of_Bitcoin_addresses
    test(
      hex"18E14A7B6A307F426A94F8114701E7C8E774E7F9A47E2C2035DB29A206321725",
      hex"0250863ad64a87ae8a2fe83c1af1a8403cb53f53e486d8511dad8a04887e5b2352"
    )
  }

  it should "be verified with proper public key" in {
    forAll { (message1: AVector[Byte], message2: AVector[Byte]) =>
      whenever(message1 != message2) {
        val (sk1, pk1) = SecP256K1.generatePriPub()
        val (_, pk2)   = SecP256K1.generatePriPub()
        val signature  = SecP256K1.sign(message1, sk1)

        SecP256K1.verify(message1, signature, pk1) is true
        SecP256K1.verify(message2, signature, pk1) is false
        SecP256K1.verify(message1, signature, pk2) is false
        SecP256K1.verify(message1, nonCanonical(signature), pk1) is false
      }
    }
  }

  def der2Compact(der: ByteString): SecP256K1Signature = {
    val bis    = new ByteArrayInputStream(der.toArray)
    val parser = new ASN1StreamParser(bis)
    val seq    = parser.readObject().toASN1Primitive.asInstanceOf[DLSequence]
    val r      = seq.getObjectAt(0).toASN1Primitive.asInstanceOf[ASN1Integer]
    val s      = seq.getObjectAt(1).toASN1Primitive.asInstanceOf[ASN1Integer]
    SecP256K1Signature.from(r.getValue, s.getValue)
  }

  it should "pass test vectors from bitcointalk" in {
    // bitcoin-s: https://bitcointalk.org/index.php?topic=285142.msg3299061#msg3299061
    val cases = Seq(
      (
        hex"0000000000000000000000000000000000000000000000000000000000000001",
        "Satoshi Nakamoto",
        hex"3045022100934b1ea10a4b3c1757e2b0c017d0b6143ce3c9a7e6a4a49860d7a6ab210ee3d802202442ce9d2b916064108014783e923ec36b49743e2ffa1c4496f01a512aafd9e5"
      ),
      (
        hex"0000000000000000000000000000000000000000000000000000000000000001",
        "Everything should be made as simple as possible, but not simpler.",
        hex"3044022033a69cd2065432a30f3d1ce4eb0d59b8ab58c74f27c41a7fdb5696ad4e6108c902206f807982866f785d3f6418d24163ddae117b7db4d5fdf0071de069fa54342262"
      ),
      (
        hex"0000000000000000000000000000000000000000000000000000000000000001",
        "All those moments will be lost in time, like tears in rain. Time to die...",
        hex"30450221008600dbd41e348fe5c9465ab92d23e3db8b98b873beecd930736488696438cb6b0220547fe64427496db33bf66019dacbf0039c04199abb0122918601db38a72cfc21"
      ),
      (
        hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364140",
        "Satoshi Nakamoto",
        hex"3045022100fd567d121db66e382991534ada77a6bd3106f0a1098c231e47993447cd6af2d002206b39cd0eb1bc8603e159ef5c20a5c8ad685a45b06ce9bebed3f153d10d93bed5"
      ),
      (
        hex"f8b8af8ce3c7cca5e300d33939540c10d45ce001b8f252bfbc57ba0342904181",
        "Alan Turing",
        hex"304402207063ae83e7f62bbb171798131b4a0564b956930092b33b07b395615d9ec7e15c022058dfcc1e00a35e1572f366ffe34ba0fc47db1e7189759b9fb233c5b05ab388ea"
      ),
      (hex"e91671c46231f833a6406ccbea0e3e392c76c167bac1cb013f6f1013980455c2",
       "There is a computer disease that anybody who works with computers knows about. It's a very serious disease and it interferes completely with the work. The trouble with computers is that you 'play' with them!",
       hex"3045022100b552edd27580141f3b2a5463048cb7cd3e047b97c9f98076c32dbdf85a68718b0220279fa72dd19bfae05577e06c7c0c1900c371fcd5893f7e1d56a37d30174671f6")
    )

    cases.foreach {
      case (rawPrivateKey, rawMessage, derSignature) =>
        val privateKey = SecP256K1PrivateKey.unsafe(rawPrivateKey)
        val message    = Sha256.hash(rawMessage).bytes
        val signature  = der2Compact(derSignature)

        SecP256K1.sign(message, privateKey) is signature
        SecP256K1.verify(message, signature, privateKey.publicKey) is true
    }
  }
}
