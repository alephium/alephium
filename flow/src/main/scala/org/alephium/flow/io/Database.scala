package org.alephium.flow.io

import java.nio.file.Path

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{BlockHeader, TxOutput, TxOutputPoint}
import org.alephium.serde._
import org.rocksdb.{Options, RocksDB, RocksDBException}

object Database {
  def open(path: Path, options: Options): IOResult[Database] = execute {
    openUnsafe(path, options)
  }

  def openUnsafe(path: Path, options: Options): Database = {
    RocksDB.loadLibrary()
    val db = RocksDB.open(options, path.toString)
    new Database(path, db)
  }

  def dESTROY(path: Path, options: Options): IOResult[Unit] = execute {
    RocksDB.destroyDB(path.toString, options)
  }

  def dESTROY(db: Database): IOResult[Unit] = execute {
    dESTROYUnsafe(db)
  }

  def dESTROYUnsafe(db: Database): Unit = {
    RocksDB.destroyDB(db.path.toString, new Options())
  }

  @inline
  private def execute[T](f: => T): IOResult[T] = {
    try Right(f)
    catch {
      case e: RocksDBException => Left(RocksDBExpt(e))
    }
  }
}

class Database private (val path: Path, db: RocksDB) {
  import Database._

  def close(): IOResult[Unit] = execute {
    db.close()
  }

  def closeUnsafe(): Unit = db.close()

  def getOpt[V: Serde](key: ByteString): IOResult[Option[V]] = execute {
    getOptUnsafe[V](key)
  }

  def getOptUnsafe[V: Serde](key: ByteString): Option[V] = {
    val result = db.get(key.toArray)
    if (result == null) None
    else {
      val data = ByteString.fromArrayUnsafe(result)
      deserialize[V](data) match {
        case Left(e)  => throw e
        case Right(v) => Some(v)
      }
    }
  }

  def get[V: Serde](key: ByteString): IOResult[V] = execute {
    getUnsafe[V](key)
  }

  def getUnsafe[V: Serde](key: ByteString): V = {
    val result = db.get(key.toArray)
    if (result == null) throw RocksDBExpt.keyNotFound.e
    else {
      val data = ByteString.fromArrayUnsafe(result)
      deserialize[V](data) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }
  }

  def exists(key: ByteString): IOResult[Boolean] = execute {
    existsUnsafe(key)
  }

  def existsUnsafe(key: ByteString): Boolean = {
    val result = db.get(key.toArray)
    result != null
  }

  def put[V: Serde](key: ByteString, value: V): IOResult[Unit] = execute {
    putUnsafe(key, value)
  }

  def putUnsafe[V: Serde](key: ByteString, value: V): Unit = {
    db.put(key.toArray, serialize(value).toArray)
  }

  // TODO: should we check the existence of the key?
  def delete(key: ByteString): IOResult[Unit] = execute {
    deleteUnsafe(key)
  }

  // TODO: should we check the existence of the key?
  def deleteUnsafe(key: ByteString): Unit = {
    db.delete(key.toArray)
  }

  def getHeaderOpt(hash: Keccak256): IOResult[Option[BlockHeader]] =
    getOpt(hash.bytes)

  def getHeaderOptUnsafe(hash: Keccak256): Option[BlockHeader] =
    getOptUnsafe[BlockHeader](hash.bytes)

  def getHeader(hash: Keccak256): IOResult[BlockHeader] =
    get[BlockHeader](hash.bytes)

  def getHeaderUnsafe(hash: Keccak256): BlockHeader =
    getUnsafe[BlockHeader](hash.bytes)

  def putHeader(header: BlockHeader): IOResult[Unit] =
    put[BlockHeader](header.hash.bytes, header)

  def putHeaderUnsafe(header: BlockHeader): Unit =
    putUnsafe[BlockHeader](header.hash.bytes, header)

  def deleteHeader(hash: Keccak256): IOResult[Unit] = {
    delete(hash.bytes)
  }

  def deleteHeaderUnsafe(hash: Keccak256): Unit = {
    deleteUnsafe(hash.bytes)
  }

  def getUTXOOpt(outputPoint: TxOutputPoint): IOResult[Option[TxOutput]] =
    getOpt[TxOutput](serialize(outputPoint))

  def getUTXOOptUnsafe(outputPoint: TxOutputPoint): Option[TxOutput] =
    getOptUnsafe[TxOutput](serialize(outputPoint))

  def getUTXO(outputPoint: TxOutputPoint): IOResult[TxOutput] =
    get[TxOutput](serialize(outputPoint))

  def getUTXOUnsafe(txOutputPoint: TxOutputPoint): TxOutput =
    getUnsafe[TxOutput](serialize(txOutputPoint))

  def putUTXO(outputPoint: TxOutputPoint, output: TxOutput): IOResult[Unit] =
    put[TxOutput](serialize(outputPoint), output)

  def putUTXOUnsafe(outputPoint: TxOutputPoint, output: TxOutput): Unit =
    putUnsafe[TxOutput](serialize(outputPoint), output)

  def deleteUTXO(txOutputPoint: TxOutputPoint): IOResult[Unit] =
    delete(serialize(txOutputPoint))

  def deleteUTXOUnsafe(txOutputPoint: TxOutputPoint): Unit =
    deleteUnsafe(serialize(txOutputPoint))
}
