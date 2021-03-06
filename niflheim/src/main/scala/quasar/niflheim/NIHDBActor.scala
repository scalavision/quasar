/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.niflheim

import quasar.common.CPath
import quasar.precog.common._
import quasar.precog.util._

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{AskSupport, GracefulStopSupport}
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import org.slf4s.Logging

import quasar.blueeyes.json._
import quasar.blueeyes.json.serialization._
import quasar.blueeyes.json.serialization.DefaultSerialization._
import quasar.blueeyes.json.serialization.IsoSerialization._
import quasar.blueeyes.json.serialization.Extractor._

import scalaz._
import scalaz.effect.IO
import scalaz.Validation._
import scalaz.syntax.monad._

import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic._

import scala.collection.immutable.SortedMap

import shapeless._

case class Insert(batch: Seq[NIHDB.Batch], responseRequested: Boolean)
case class InsertSegments(offset: Long, length: Int, segments: List[Segment], responseRequested: Boolean)

case object GetSnapshot

case class Block(id: Long, segments: Seq[Segment], stable: Boolean)

case object GetStatus
case class Status(cooked: Int, pending: Int, rawSize: Int)

case object GetStructure
case class Structure(columns: Set[(CPath, CType)])

sealed trait InsertResult
case class Inserted(offset: Long, size: Int) extends InsertResult
case object Skipped extends InsertResult

case object Cook
case object Quiesce

object NIHDB {
  import scala.concurrent.ExecutionContext.Implicits.global   // TODO!!!!

  case class Batch(offset: Long, values: Seq[JValue])

  final val projectionIdGen = new AtomicInteger()

  final def create(
      chef: ActorRef,
      baseDir: File,
      cookThreshold: Int,
      timeout: FiniteDuration,
      txLogScheduler: ScheduledExecutorService)(
      implicit actorSystem: ActorSystem)
      : IO[Validation[Error, NIHDB]] =
    NIHDBActor.create(chef, baseDir, cookThreshold, timeout, txLogScheduler) map { _ map { actor => new NIHDBImpl(actor, timeout) } }

  final def open(chef: ActorRef, baseDir: File, cookThreshold: Int, timeout: FiniteDuration, txLogScheduler: ScheduledExecutorService)(implicit actorSystem: ActorSystem): IO[Option[Validation[Error, NIHDB]]] =
    NIHDBActor.open(chef, baseDir, cookThreshold, timeout, txLogScheduler) map { _ map { _ map { actor => new NIHDBImpl(actor, timeout) } } }
}

trait NIHDB {
  def insert(batch: Seq[NIHDB.Batch]): IO[Unit]

  def insertVerified(batch: Seq[NIHDB.Batch]): Future[InsertResult]

  /**
   * Please don't mix segment insertion with value insertion on the same dataset. The results may be
   * somewhat odd and *definitely* out of order. Also note that segment insertion doesn't obey the
   * same atomicity guarantees as value insertion (naturally, since it bypasses the append log).
   * External atomicity guarantees should be used to avoid corruption in all cases.
   */
  def insertSegmentsVerified(offset: Long, length: Int, segments: List[Segment]): Future[InsertResult]

  def getSnapshot(): Future[NIHDBSnapshot]

  def getBlockAfter(id: Option[Long], cols: Option[Set[ColumnRef]]): Future[Option[Block]]

  def getBlock(id: Option[Long], cols: Option[Set[CPath]]): Future[Option[Block]]

  def length: Future[Long]

  def projectionId: Int

  def status: Future[Status]

  def structure: Future[Set[ColumnRef]]

  /**
   * Returns the total number of defined objects for a given `CPath` *mask*.
   * Since this punches holes in our rows, it is not simply the length of the
   * block. Instead we count the number of rows that have at least one defined
   * value at each path (and their children).
   */
  def count(paths0: Option[Set[CPath]]): Future[Long]

  /**
   * Forces the chef to cook the current outstanding commit log.  This should only
   * be called in the event that an ingestion is believed to be 100% complete, since
   * it will result in a "partial" block (i.e. a block that is not of maximal length).
   * Note that the append log is visible to snapshots, meaning that this function
   * should be unnecessary in nearly all circumstances.
   */
  def cook: Future[Unit]

  def quiesce: Future[Unit]

  def close: Future[Unit]
}

private[niflheim] class NIHDBImpl private[niflheim] (actor: ActorRef, timeout: Timeout)(implicit executor: ExecutionContext) extends NIHDB with GracefulStopSupport with AskSupport {
  private implicit val impFiniteDuration = timeout

  val projectionId = NIHDB.projectionIdGen.getAndIncrement

  def insert(batch: Seq[NIHDB.Batch]): IO[Unit] =
    IO(actor ! Insert(batch, false))

  def insertVerified(batch: Seq[NIHDB.Batch]): Future[InsertResult] =
    (actor ? Insert(batch, true)).mapTo[InsertResult]

  def insertSegmentsVerified(offset: Long, length: Int, segments: List[Segment]): Future[InsertResult] =
    (actor ? InsertSegments(offset, length, segments, true)).mapTo[InsertResult]

  def getSnapshot(): Future[NIHDBSnapshot] =
    (actor ? GetSnapshot).mapTo[NIHDBSnapshot]

  def getBlockAfter(id: Option[Long], cols: Option[Set[ColumnRef]]): Future[Option[Block]] =
    getSnapshot().map(_.getBlockAfter(id, cols))

  def getBlock(id: Option[Long], cols: Option[Set[CPath]]): Future[Option[Block]] =
    getSnapshot().map(_.getBlock(id, cols))

  def length: Future[Long] =
    getSnapshot().map(_.count())

  def status: Future[Status] =
    (actor ? GetStatus).mapTo[Status]

  def structure: Future[Set[ColumnRef]] =
    getSnapshot().map(_.structure)

  def count(paths0: Option[Set[CPath]]): Future[Long] =
    getSnapshot().map(_.count(paths0))

  def cook: Future[Unit] =
    (actor ? Cook).mapTo[Unit]

  def quiesce: Future[Unit] =
    (actor ? Quiesce).mapTo[Unit]

  def close: Future[Unit] =
    gracefulStop(actor, timeout.duration).map(_ => ())
}

private[niflheim] object NIHDBActor extends Logging {
  final val descriptorFilename = "NIHDBDescriptor.json"
  final val cookedSubdir = "cooked_blocks"
  final val rawSubdir = "raw_blocks"
  final val lockName = "NIHDBProjection"

  final def create(chef: ActorRef, baseDir: File, cookThreshold: Int, timeout: FiniteDuration, txLogScheduler: ScheduledExecutorService)(implicit actorSystem: ActorSystem): IO[Validation[Error, ActorRef]] = {
    val descriptorFile = new File(baseDir, descriptorFilename)
    val currentState: IO[Validation[Error, ProjectionState]] =
      if (descriptorFile.exists) {
        ProjectionState.fromFile(descriptorFile)
      } else {
        val state = ProjectionState.empty
        for {
          _ <- IO { log.info("No current descriptor found for " + baseDir + ", creating fresh descriptor") }
          _ <- ProjectionState.toFile(state, descriptorFile)
        } yield {
          success(state)
        }
      }

    currentState map { _ map { s => actorSystem.actorOf(Props(new NIHDBActor(s, baseDir, chef, cookThreshold, txLogScheduler))) } }
  }

  final def readDescriptor(baseDir: File): IO[Option[Validation[Error, ProjectionState]]] = {
    val descriptorFile = new File(baseDir, descriptorFilename)
    if (descriptorFile.exists) {
      ProjectionState.fromFile(descriptorFile) map { Some(_) }
    } else {
      log.warn("No projection found at " + baseDir)
      IO { None }
    }
  }

  final def open(chef: ActorRef, baseDir: File, cookThreshold: Int, timeout: FiniteDuration, txLogScheduler: ScheduledExecutorService)(implicit actorSystem: ActorSystem): IO[Option[Validation[Error, ActorRef]]] = {
    val currentState: IO[Option[Validation[Error, ProjectionState]]] = readDescriptor(baseDir)

    currentState map { _ map { _ map { s => actorSystem.actorOf(Props(new NIHDBActor(s, baseDir, chef, cookThreshold, txLogScheduler))) } } }
  }

  private case class BlockState(cooked: List[CookedReader], pending: Map[Long, StorageReader], rawLog: RawHandler)
  private class State(val txLog: CookStateLog, var blockState: BlockState, var currentBlocks: SortedMap[Long, StorageReader])
}

private[niflheim] class NIHDBActor private (private var currentState: ProjectionState, baseDir: File, chef: ActorRef, cookThreshold: Int, txLogScheduler: ScheduledExecutorService)
    extends Actor
    with Logging {

  import NIHDBActor._

  assert(cookThreshold > 0)
  assert(cookThreshold < (1 << 16))

  private[this] val workLock = FileLock(baseDir, lockName)

  private[this] val cookedDir = new File(baseDir, cookedSubdir)
  private[this] val rawDir    = new File(baseDir, rawSubdir)
  private[this] val descriptorFile = new File(baseDir, descriptorFilename)

  private[this] val cookSequence = new AtomicLong

  private[this] var actorState: Option[State] = None
  private def state = {
    import scalaz.syntax.effect.id._
    actorState getOrElse open.flatMap(_.tap(s => IO { actorState = Some(s) })).unsafePerformIO
  }

  private def initDirs(f: File) = IO {
    if (!f.isDirectory) {
      if (!f.mkdirs) {
        throw new Exception("Failed to create dir: " + f)
      }
    }
  }

  private def initActorState = IO {
    log.debug("Opening log in " + baseDir)
    val txLog = new CookStateLog(baseDir, txLogScheduler)
    log.debug("Current raw block id = " + txLog.currentBlockId)

    // We'll need to update our current thresholds based on what we read out of any raw logs we open
    var maxOffset = currentState.maxOffset

    val currentRawFile = rawFileFor(txLog.currentBlockId)
    val (currentLog, rawLogOffsets) = if (currentRawFile.exists) {
      val (handler, offsets, ok) = RawHandler.load(txLog.currentBlockId, currentRawFile)
      if (!ok) {
        log.warn("Corruption detected and recovery performed on " + currentRawFile)
      }
      (handler, offsets)
    } else {
      (RawHandler.empty(txLog.currentBlockId, currentRawFile), Seq.empty[Long])
    }

    rawLogOffsets.sortBy(- _).headOption.foreach { newMaxOffset =>
      maxOffset = maxOffset max newMaxOffset
    }

    val pendingCooks = txLog.pendingCookIds.map { id =>
      val (reader, offsets, ok) = RawHandler.load(id, rawFileFor(id))
      if (!ok) {
        log.warn("Corruption detected and recovery performed on " + currentRawFile)
      }
      maxOffset = math.max(maxOffset, offsets.max)
      (id, reader)
    }.toMap

    this.currentState = currentState.copy(maxOffset = maxOffset)

    // Restore the cooked map
    val cooked = currentState.readers(cookedDir)

    val blockState = BlockState(cooked, pendingCooks, currentLog)
    val currentBlocks = computeBlockMap(blockState)

    log.debug("Initial block state = " + blockState)

    // Re-fire any restored pending cooks
    blockState.pending.foreach {
      case (id, reader) =>
        log.debug("Restarting pending cook on block %s:%d".format(baseDir, id))
        chef ! Prepare(id, cookSequence.getAndIncrement, cookedDir, reader, () => ())
    }

    new State(txLog, blockState, currentBlocks)
  }

  private def open = actorState.map(IO(_)) getOrElse {
    for {
      _ <- initDirs(cookedDir)
      _ <- initDirs(rawDir)
      state <- initActorState
    } yield state
  }

  private def cook(responseRequested: Boolean) = IO {
    state.blockState.rawLog.close()
    val toCook = state.blockState.rawLog
    val newRaw = RawHandler.empty(toCook.id + 1, rawFileFor(toCook.id + 1))

    state.blockState = state.blockState.copy(pending = state.blockState.pending + (toCook.id -> toCook), rawLog = newRaw)
    state.txLog.startCook(toCook.id)

    val target = sender
    val onComplete = if (responseRequested)
      () => target ! (())
    else
      () => ()

    chef ! Prepare(toCook.id, cookSequence.getAndIncrement, cookedDir, toCook, onComplete)
  }

  private def quiesce = IO {
    actorState foreach { s =>
      log.debug("Releasing resources for projection in " + baseDir)
      s.blockState.rawLog.close()
      s.txLog.close()
      ProjectionState.toFile(currentState, descriptorFile)
      actorState = None
    }
  }

  private def close = {
    IO(log.debug("Closing projection in " + baseDir)) >> quiesce
  } except { case t: Throwable =>
    IO { log.error("Error during close", t) }
  } ensuring {
    IO { workLock.release() }
  }

  override def postStop() = {
    close.unsafePerformIO
  }

  def getSnapshot(): NIHDBSnapshot = NIHDBSnapshot(state.currentBlocks)

  private def rawFileFor(seq: Long) = new File(rawDir, "%06x.raw".format(seq))

  private def computeBlockMap(current: BlockState) = {
    val allBlocks: List[StorageReader] = (current.cooked ++ current.pending.values :+ current.rawLog)
    SortedMap(allBlocks.map { r => r.id -> r }.toSeq: _*)
  }

  override def receive = {
    case GetSnapshot =>
      sender ! getSnapshot()

    case Spoilt(_, _, onComplete) =>
      onComplete()

    case Cooked(id, _, _, file, onComplete) =>
      // This could be a replacement for an existing id, so we
      // ned to remove/close any existing cooked block with the same
      // ID
      //TODO: LENSES!!!!!!!~
      state.blockState = state.blockState.copy(
        cooked = CookedReader.load(cookedDir, file) :: state.blockState.cooked.filterNot(_.id == id),
        pending = state.blockState.pending - id
      )

      state.currentBlocks = computeBlockMap(state.blockState)

      currentState = currentState.copy(
        cookedMap = currentState.cookedMap + (id -> file.getPath)
      )

      log.debug("Cook complete on %d".format(id))

      ProjectionState.toFile(currentState, descriptorFile).unsafePerformIO
      state.txLog.completeCook(id)
      onComplete()

    case Insert(batch, responseRequested) =>
      if (batch.isEmpty) {
        log.warn("Skipping insert with an empty batch on %s".format(baseDir.getCanonicalPath))
        if (responseRequested) sender ! Skipped
      } else {
        val (skipValues, keepValues) = batch.partition(_.offset <= currentState.maxOffset)
        if (keepValues.isEmpty) {
          log.warn("Skipping entirely seen batch of %d rows prior to offset %d".format(batch.flatMap(_.values).size, currentState.maxOffset))
          if (responseRequested) sender ! Skipped
        } else {
          val values = keepValues.flatMap(_.values)
          val offset = keepValues.map(_.offset).max

          log.debug("Inserting %d rows, skipping %d rows at offset %d for %s".format(values.length, skipValues.length, offset, baseDir.getCanonicalPath))
          state.blockState.rawLog.write(offset, values)

          // Update the producer thresholds for the rows. We know that ids only has one element due to the initial check
          currentState = currentState.copy(maxOffset = offset)

          if (state.blockState.rawLog.length >= cookThreshold) {
            log.debug("Starting cook on %s after threshold exceeded".format(baseDir.getCanonicalPath))
            cook(false).unsafePerformIO
          }

          log.debug("Insert complete on %d rows at offset %d for %s".format(values.length, offset, baseDir.getCanonicalPath))
          if (responseRequested) sender ! Inserted(offset, values.length)
        }
      }

    case InsertSegments(offset, length, segments, responseRequested) =>
      if (length == 0) {
        log.warn("Skipping insert with an empty batch on %s".format(baseDir.getCanonicalPath))
        if (responseRequested) sender ! Skipped
      } else if (offset < currentState.maxOffset) {
        log.warn("Skipping insert with already-inserted offset on %s".format(baseDir.getCanonicalPath))
        if (responseRequested) sender ! Skipped
      } else {
        log.debug("Inserting from segments %d rows at offset %d for %s".format(length, offset, baseDir.getCanonicalPath))

        {
          state.blockState.rawLog.close()
          state.blockState = state.blockState.copy(rawLog = RawHandler.empty(offset + 1, rawFileFor(offset + 1)))
        }

        currentState = currentState.copy(maxOffset = offset)
        state.txLog.startCook(offset)

        val target = sender
        val onComplete = if (responseRequested)
          () => target ! Inserted(offset, length)
        else
          () => ()

        cookedDir.mkdir()   // I don't get why we need this, but we do
        chef ! PrepareSegments(offset, cookSequence.getAndIncrement, cookedDir, offset, length, segments, onComplete)
      }

    case Cook =>
      cook(true).unsafePerformIO

    case GetStatus =>
      sender ! Status(state.blockState.cooked.length, state.blockState.pending.size, state.blockState.rawLog.length)

    case Quiesce =>
      quiesce.unsafePerformIO
      sender ! (())
  }
}

private[niflheim] case class ProjectionState(maxOffset: Long, cookedMap: Map[Long, String]) {
  def readers(baseDir: File): List[CookedReader] =
    cookedMap.map {
      case (id, metadataFile) =>
        CookedReader.load(baseDir, new File(metadataFile))
    }.toList
}

private[niflheim] object ProjectionState {
  import Extractor.Error

  def empty = ProjectionState(-1L, Map.empty)

  // FIXME: Add version for this format
  val v1Schema = "maxOffset" :: "cookedMap" :: HNil

  implicit val stateDecomposer = decomposer[ProjectionState](v1Schema)
  implicit val stateExtractor  = extractor[ProjectionState](v1Schema)

  def fromFile(input: File): IO[Validation[Error, ProjectionState]] = IO {
    JParser.parseFromFile(input).bimap(Extractor.Thrown(_): Extractor.Error, x => x).flatMap { jv =>
      jv.validated[ProjectionState]
    }
  }

  def toFile(state: ProjectionState, output: File): IO[Boolean] = {
    IOUtils.safeWriteToFile(state.serialize.renderCompact, output)
  }
}
