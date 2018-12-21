package org.alephium.flow.storage

import java.nio.file.Path

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader
import org.alephium.serde._
import org.rocksdb.{Options, RocksDB, RocksDBException}

object Database {
  def open(path: Path, options: Options): DBResult[Database] = {
    execute {
      RocksDB.loadLibrary()
      val db = RocksDB.open(options, path.toString)
      new Database(db)
    }
  }

  def dESTROY(path: Path, options: Options): DBResult[Unit] = execute {
    RocksDB.destroyDB(path.toString, options)
  }

  @inline
  private def execute[T](f: => T): DBResult[T] = {
    executeF(Right(f))
  }

  @inline
  private def executeF[T](f: => DBResult[T]): DBResult[T] = {
    try f
    catch {
      case e: RocksDBException => Left(RocksDBError(e))
    }
  }
}

class Database private (db: RocksDB) {
  import Database._

  def close(): DBResult[Unit] = execute {
    db.close()
  }

  def getOpt(key: ByteString): DBResult[Option[ByteString]] = execute {
    val result = db.get(key.toArray)
    if (result == null) None else Some(ByteString.fromArrayUnsafe(result))
  }

  def get(key: ByteString): DBResult[ByteString] = executeF {
    val result = db.get(key.toArray)
    if (result == null) Left(KeyNotFound)
    else Right(ByteString.fromArrayUnsafe(result))
  }

  def exists(key: ByteString): DBResult[Boolean] = execute {
    val result = db.get(key.toArray)
    result != null
  }

  def put(key: ByteString, value: ByteString): DBResult[Unit] = execute {
    db.put(key.toArray, value.toArray)
  }

  def delete(key: ByteString): DBResult[Unit] = execute {
    db.delete(key.toArray)
  }

  def getHeaderOpt(hash: Keccak256): DBResult[Option[BlockHeader]] = {
    getOpt(hash.bytes).right.flatMap { dataOpt =>
      dataOpt.fold[DBResult[Option[BlockHeader]]](Right(None)) { data =>
        deserialize[BlockHeader](data) match {
          case Left(e)       => Left(DeError(e))
          case Right(header) => Right(Some(header))
        }
      }
    }
  }

  def getHeader(hash: Keccak256): DBResult[BlockHeader] = {
    get(hash.bytes).right.flatMap { data =>
      deserialize[BlockHeader](data).left.map(DeError)
    }
  }

  def putHeader(header: BlockHeader): DBResult[Unit] = {
    put(header.hash.bytes, serialize(header))
  }

  def deleteHeader(hash: Keccak256): DBResult[Unit] = {
    delete(hash.bytes)
  }
}

sealed trait DBError
object DBError {
  def from(exception: Exception): DBError = exception match {
    case e: RocksDBException => RocksDBError(e)
    case e: SerdeError       => DeError(e)
    case e: Exception        => Other(e)
  }
}

case class RocksDBError(exception: RocksDBException) extends DBError {
  override def toString: String = exception.toString
}
case class DeError(serdeError: SerdeError) extends DBError {
  override def toString: String = serdeError.toString
}
case class Other(e: Exception) extends DBError {
  override def toString: String = e.toString
}
case object KeyNotFound extends DBError {
  override def toString: String = "Key not found"
}
