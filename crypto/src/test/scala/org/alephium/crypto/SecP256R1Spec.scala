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

package org.alephium.crypto

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.serde.byteAVectorSerde
import org.alephium.util.{AlephiumSpec, AVector}
import org.alephium.util.Hex._

class SecP256R1Spec extends AlephiumSpec {
  def nonCanonical(signature: SecP256R1Signature): SecP256R1Signature = {
    val (r, s) = signature.bytes.splitAt(32)
    val ss     = SecP256R1.params.getN.subtract(new BigInteger(1, s.toArray))
    SecP256R1Signature.unsafe(
      r ++ (ByteString.fromArrayUnsafe(ss.toByteArray).dropWhile(_ equals 0.toByte))
    )
  }

  it should "be verified with proper public key" in {
    forAll { (_message1: AVector[Byte], _message2: AVector[Byte]) =>
      whenever(_message1 != _message2) {
        val message1 = Blake2b.hash(_message1).bytes
        val message2 = Blake2b.hash(_message2).bytes

        val (sk1, pk1) = SecP256R1.secureGeneratePriPub()
        val (_, pk2)   = SecP256R1.secureGeneratePriPub()
        val signature  = SecP256R1.sign(message1, sk1)

        SecP256R1.verify(message1, signature, pk1) is true
        SecP256R1.verify(message2, signature, pk1) is false
        SecP256R1.verify(message1, signature, pk2) is false
        SecP256R1.verify(message1, nonCanonical(signature), pk1) is false
      }
    }
  }

  it should "pass test vectors" in {
    val cases = Seq(
      (
        hex"96043f7246a96fa51d682d49fa4073fe4327ce476be02e4e98deada4310515a8",
        hex"02fb5e0253bcb5ab2d6f0e9d75057bfbc7de99e41bdff7d4655226db59d720767c",
        hex"366f955e21ac22b87033cb477f523e6ae5685746e3e80854ffce6458edcd7d91224e157f0987d01eedcb0fcfc1ef25d502daa290b5f19d8e5b375e0a9a901b00752f82f3e6c7c564d747b10b1e54e77cbbb3c358d9175161ee7e122cf9ecc3b6169ce4f9ab6cfff105d08443251d7e63c1221c844392a92868e0409b6cf61d33",
        hex"ecf15f82aef834d378cc8d639c97e1b0c2f2b603b647dd29c3ef8c513c337039404303fbdf711aee1bd0d96ed7fc883b5e53392b58d5b7b229f20b469ac8f994"
      ),
      (
        hex"eb630003a660befa68e13575d3e75f297b5b0e99188a826487361232b2000c13",
        hex"02b64afa86da5bcaaf4a631e604ad0de85f5a41eed5d25b8779850829da9680b79",
        hex"209712e56e33b265a3b4ecae2a1fc5bb38bdfcc64ab65f5e105f1081571fa68c6030c94ae8facc3eb1830e51c5f9b44e38bdb4df2de0d4413fc5001ea595f16cdae67583f8b23601826f452f4fbc305d500b7f3c89eb6f23e3f28c0a41af50c545dbd9f320dda9bd3f050bd5689a121cfe18169de43be074fd7b58339e66a248",
        hex"5994fe1422ff077a9e864b4f0407aacb48659b11c1f7043ffc089347a79b1b7d0467a101260ce83d6a334d19b7f53505849fa2eca25d5d4adce457ae88ed8849"
      ),
      (
        hex"f179d6b548cc6288169096491ceb64f61eaf1fe19f69e2c2c9fc321e727894be",
        hex"02e8af271825a87894ea7b6ae70cbaf1274052a961a3ca1031ce4725c7c2dc6a92",
        hex"8ad2f0b3069d4ffc6233d0be74b06a2c388bf8cf2655893e2397021930e76af84908c669f5587c2c3ba0805b19d821fee6b4a8e21a0499a0ff5bf804f1297f2453d1209a59ea2fe66f50aad1c28c72df65e170fbf2168a94cd51e0f5dda1d714310e3344475e3b7c739d839a1fe0e997056f769ac0a5153ae14d79ff76f85b35",
        hex"7f1302190c970d1578d922580cd0769b2a6d5b043f8830b258b501e41c03de752810fd9471bc341124d72c22c91ba6c8cfd188d9c9b5f186b1a5b8707d42d28e"
      ),
      (
        hex"ad6f4898197e3d62817ac596f7774dd46342f6fc393dc3f23a8f9f69e9a7bac0",
        hex"028d2fd3f8f8497f25bb2979806ed92fdc73f4c9084ddfd4689f871b364df2d88c",
        hex"9c6db358f2452e98157c4952a37ee2d18cb9252ffeead4981fa206bb40dab903037f0d8037976ddbf412b0d205c4ab41b2d440c607ac15e6819ba32e46d3982f089591ae121d16cb03a15fc9bfdfce6da3161b149b31a30c95501843943bbd550378ce3827788c9e0ab4b781920924388072cb455fd0856afcbf0979ba33ec7e",
        hex"52e15b23f9033843e96a27e2131adcae1d0c8d82d49097df2b52f82ea21b6a0a030673a2bd482371b00a775a4d44cd91298aaae52278a8214d83faf0ff515e55"
      ),
      (
        hex"a75dda773e9bc1df270cd26ed52343664519e4ec93d5e5d71a5fcf7a27e4f6ac",
        hex"03377225f9991a680f73bf4284c72ca0acf9bdbd4d1982ecd6adb378c926da3994",
        hex"7de87e753aafdce225af528600c6a9cbdea25fd35b01fb76ed3e9f0f50b1a213b6f1b47db74f4b4743a10314d13a505f45245a047a0a2849af37f7376c9bf145210c2d472b1eba51a86b48158f932a52b2ff04f70d3749c6223805422df7100be30ff327c856c22b6b386a820976b881156563c249a05f943750b3e834247c9e",
        hex"883de5eb992115bd558370aa6fd00ca39859c8f8c4a7f786f262fc75305857de604e14e1bafa71b202b41fa6dd25486d417d6edadcddc027c565088b5c38f232"
      ),
      (
        hex"6e3882948b8e38aa2916979800238bf2035020384d7013f08e79a3e84cbaf703",
        hex"039b0f023210f79897e1e97b3e96f96b3808991f415597d33cb1b0db9ff61a276b",
        hex"57f7c2cde57da633f940c68fd486395fd415ccb9c4859fcc73c060042629a9308c71cfd67cc07d7e90e4f808c5fd3c35469f76f0fc3521cf71f817f42b10add3c544d884abe43a03c50ee594784adf42396585dad08696875de515ff72535ff0f880ba7634ca70b8ffedd163ccc867ce6d7c565005de06e979907cc9ab59ce8e",
        hex"9dacf56063500341b1be8be584afaa9e79c873f018b1b9633d150083275cd4036afe81bb67c3f9cfd4e06d8925db3dd0a2cae9fdb20e6d71e697492efe415504"
      )
    )

    cases.foreach { case (rawPrivateKey, rawPublicKey, rawMessage, rawSignature) =>
      val privateKey = SecP256R1PrivateKey.unsafe(rawPrivateKey)
      privateKey.publicKey is SecP256R1PublicKey.unsafe(rawPublicKey)
      val messageHash = Blake2b.hash(rawMessage)
      val signature   = SecP256R1.sign(messageHash, privateKey)

      signature.bytes is rawSignature
      SecP256R1.verify(messageHash.bytes, signature, privateKey.publicKey) is true
    }
  }
}
