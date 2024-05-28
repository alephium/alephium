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

package org.alephium.util

import java.util.concurrent.locks.{ReentrantLock, ReentrantReadWriteLock}

trait Lock {
  def readOnly[T](f: => T): T
  def writeOnly[T](f: => T): T
}

trait RWLock extends Lock {
  private val lock      = new ReentrantReadWriteLock()
  private val readLock  = lock.readLock()
  private val writeLock = lock.writeLock()

  // Note: functions started with _ are for testing
  def _getLock: ReentrantReadWriteLock = lock

  def readOnly[T](f: => T): T = {
    readLock.lock()
    try {
      f
    } finally {
      readLock.unlock()
    }
  }

  def writeOnly[T](f: => T): T = {
    writeLock.lock()
    try {
      f
    } finally {
      writeLock.unlock()
    }
  }
}

trait MutexLock extends Lock {
  private val lock = new ReentrantLock()

  @inline private def run[T](f: => T): T = {
    lock.lock()
    try {
      f
    } finally {
      lock.unlock()
    }
  }

  def readOnly[T](f: => T): T = {
    run(f)
  }

  def writeOnly[T](f: => T): T = {
    run(f)
  }
}

trait NoLock extends Lock {
  @inline def readOnly[T](f: => T): T  = identity(f)
  @inline def writeOnly[T](f: => T): T = identity(f)
}
