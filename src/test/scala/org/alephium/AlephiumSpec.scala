package org.alephium

import io.circe.parser.parse
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.util.Hex
import org.scalatest.{FlatSpecLike, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.io.Source

trait AlephiumSpec extends FlatSpecLike with GeneratorDrivenPropertyChecks with Matchers

object AlephiumSpec {
  private val json = parse(Source.fromResource("genesis.json").mkString).right.get

  private val test = json.hcursor.downField("test")
  val testPrivateKey: ED25519PrivateKey =
    ED25519PrivateKey.unsafeFrom(Hex(test.get[String]("privateKey").right.get))
  val testPublicKey: ED25519PublicKey =
    ED25519PublicKey.unsafeFrom(Hex(test.get[String]("publicKey").right.get))
  val testBalance: Int = test.get[Int]("balance").right.get
}
