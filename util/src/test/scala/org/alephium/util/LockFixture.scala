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

import java.util.concurrent.locks.ReentrantReadWriteLock

import org.scalatest.Assertion
import org.scalatest.concurrent.Conductors

trait LockFixture extends AlephiumSpec with Conductors {
  trait WithLock {
    val locked: Array[Boolean] = Array.fill(10)(true)

    def wait(i: Int): Unit   = while (locked(i)) Thread.sleep(10)
    def unlock(i: Int): Unit = locked(i) = false
    def lock(i: Int): Unit   = locked(i) = true

    private def resetLocked(): Unit = (0 until 10).foreach(i => lock(i))

    private def checkTryLock(
        rwl: ReentrantReadWriteLock
    )(readLock: Boolean, writeLock: Boolean): Unit = {
      rwl.readLock().tryLock() is readLock
      if (readLock) rwl.readLock().unlock()
      rwl.writeLock().tryLock() is writeLock
      if (writeLock) rwl.writeLock().unlock()
    }

    def checkReadLock[T](
        rwl: ReentrantReadWriteLock
    )(initial: T, f: => T, expected: T, timeoutMillis: Long = 100): Assertion = {
      checkLockUsed(rwl)(initial, f, expected, timeoutMillis)
      checkNoWriteLock(rwl)(initial, f, expected)
    }

    def checkWriteLock[T](
        rwl: ReentrantReadWriteLock
    )(initial: T, f: => T, expected: T, timeoutMillis: Long = 100): Assertion = {
      val conductor = new Conductor
      val readLock  = rwl.readLock()

      @volatile var result = initial
      checkTryLock(rwl)(true, true)

      conductor.threadNamed("t0") {
        readLock.lock()
        unlock(0)
        wait(1)
        Thread.sleep(timeoutMillis)
        result is initial
        readLock.unlock()
        wait(2)
        result is expected
        ()
      }

      conductor.threadNamed("t1") {
        wait(0)
        checkTryLock(rwl)(true, false)
        unlock(1)
        result = f
        unlock(2)
      }

      conductor.whenFinished {
        checkTryLock(rwl)(true, true)
        resetLocked()
        result is expected
      }
    }

    def checkNoWriteLock[T](
        rwl: ReentrantReadWriteLock
    )(initial: T, f: => T, expected: T): Assertion = {
      val conductor = new Conductor
      val readLock  = rwl.readLock()

      @volatile var result = initial
      checkTryLock(rwl)(true, true)

      conductor.threadNamed("t0") {
        result is initial
        readLock.lock()
        unlock(0)
        wait(1)
        readLock.unlock()
      }

      conductor.threadNamed("t1") {
        wait(0)
        Thread.sleep(10)
        checkTryLock(rwl)(true, false)
        result = f
        unlock(1)
      }

      conductor.whenFinished {
        checkTryLock(rwl)(true, true)
        resetLocked()
        result is expected
      }
    }

    def checkLockUsed[T](
        rwl: ReentrantReadWriteLock
    )(initial: T, f: => T, expected: T, timeoutMillis: Long = 100): Assertion = {
      val conductor = new Conductor
      val writeLock = rwl.writeLock()

      @volatile var result = initial
      checkTryLock(rwl)(true, true)

      conductor.threadNamed("t0") {
        writeLock.lock()
        unlock(0)
        Thread.sleep(timeoutMillis)
        result is initial
        wait(1)
        writeLock.unlock()
        wait(2)
        result is expected
        ()
      }

      conductor.threadNamed("t1") {
        wait(0)
        checkTryLock(rwl)(false, false)
        unlock(1)
        result = f
        unlock(2)
      }

      conductor.whenFinished {
        checkTryLock(rwl)(true, true)
        resetLocked()
        result is expected
      }
    }
  }
}
