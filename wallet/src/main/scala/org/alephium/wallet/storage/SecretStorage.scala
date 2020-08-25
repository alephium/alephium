package org.alephium.wallet.storage

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Path}
import java.util.UUID

import scala.io.Source

import akka.util.ByteString
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._

import org.alephium.crypto.AES
import org.alephium.crypto.wallet.BIP32
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.wallet.Constants
import org.alephium.wallet.circe.byteStringCodec

trait SecretStorage {
  def lock(): Unit
  def unlock(password: String): Either[String, Unit]
  def getPrivateKey(): Option[ExtendedPrivateKey]
}

object SecretStorage {

  def apply(seed: ByteString, password: String, secretDir: Path): SecretStorage = {

    val encryption = AES.encrypt(seed, password)

    val uuid = UUID.nameUUIDFromBytes(encryption.encrypted.toArray)

    Files.createDirectories(secretDir)
    val file      = new File(s"$secretDir/$uuid.json")
    val outWriter = new PrintWriter(file)

    // scalastyle:off regex
    outWriter.write(encryption.asJson.noSpaces)
    // scalastyle:on
    outWriter.close()

    new Impl(file)
  }

  private class Impl(file: File) extends SecretStorage {

    private var privateKey: Option[ExtendedPrivateKey] = None

    override def lock(): Unit = {
      privateKey = None
    }

    override def unlock(password: String): Either[String, Unit] = {
      val rawFile = Source.fromFile(file).getLines().mkString
      for {
        encrypted <- decode[AES.Encrypted](rawFile).left.map(_.getMessage)
        seed      <- AES.decrypt(encrypted, password).toEither.left.map(_.getMessage)
      } yield {
        privateKey = BIP32.btcMasterKey(seed).derive(Constants.path.toSeq)
      }
    }

    override def getPrivateKey(): Option[ExtendedPrivateKey] = privateKey
  }
  implicit val codec: Codec[AES.Encrypted] = deriveCodec[AES.Encrypted]
}
