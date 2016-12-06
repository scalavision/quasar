/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.qscript

import quasar.Predef.{ Map => ScalaMap, _ }
import quasar.common.SortDir
import quasar.contrib.matryoshka._
import quasar.fp._
import quasar.fp.ski._
import quasar.qscript.MapFunc._
import quasar.qscript.MapFuncs._

import matryoshka._,
  Recursive.ops._
import matryoshka.patterns._
import monocle.macros.Lenses
import scalaz.{:+: => _, Divide => _, _},
  Leibniz._,
  Scalaz._
import simulacrum.typeclass

object PATypes {
  type Acc = Set[BigInt]
  type StateAcc = Option[Acc]

  implicit final class StateAccOps(val self: StateAcc) extends AnyVal {
    def |++|(other: StateAcc): StateAcc =
      Semigroup.liftSemigroup[Option, Acc].append(self, other)
  }

  /** @param newState the state produced by the current focus
    * @param newEnv the environment annotated to the current focus
    */
  @Lenses final case class Annotation(newState: StateAcc, newEnv: StateAcc)

  /** @param newState the state produced by the current focus
    * @param out the value which replaces the current focus
    */
  @Lenses final case class Output[F[_], A](newState: StateAcc, out: F[A])
}

@typeclass trait PruneArrays[F[_]] {
  import PATypes._

  def find[A](state: StateAcc, in: F[A]): Annotation
  def remap[A](env: StateAcc, state: StateAcc, in: F[A]): Output[F, A]
}

class PAHelpers[T[_[_]]: Recursive: Corecursive] extends TTypes[T] {
  import PATypes._

  type Mapping = ScalaMap[BigInt, BigInt]

  /** Returns `None` if a non-static non-integer index was found.
    * Else returns all indices of the form `ProjectIndex(SrcHole, IntLit(_))`.
    */
  def findIndicesInFunc(func: FreeMap): StateAcc = {
    type FreeMapCoEnv[A] = CoEnv[Hole, MapFunc, A]

    def accumulate: MapFunc[(T[FreeMapCoEnv], StateAcc)] => StateAcc = {
      case ProjectIndex((src, Some(acc1)), (value, Some(acc2))) =>
        (src.project.run, value.project.run) match {
          case (-\/(SrcHole), \/-(IntLitMapFunc(idx))) => ((acc1 ++ acc2) + idx).some  // static integer index
          case (-\/(SrcHole), _)                       => None  // non-static index
          case (_, _)                                  => (acc1 ++ acc2).some
        }
      case f => f.foldMapM[Option, Acc](_._2)
    }

    // CoEnv[Hole, MapFunc, (T[CoEnv[Hole, MapFunc, ?]], StateAcc)] => StateAcc
    val galg: GAlgebra[(T[FreeMapCoEnv], ?), FreeMapCoEnv, StateAcc] =
      _.run.fold(κ(Set().some), accumulate)

    func.toCoEnv.para[StateAcc](galg)
  }

  /** Remap all indices in `func` in structures like `ProjectIndex(SrcHoe, IntLit(_))`
    * according to the provided `mapping`.
    */
  def remapIndicesInFunc(func: FreeMap, mapping: Mapping): FreeMap =
    freeTransCata[T, MapFunc, MapFunc, Hole, Hole](func) {
      case co @ CoEnv(\/-(ProjectIndex(hole @ Embed(CoEnv(-\/(SrcHole))), IntLitCoEnv(idx)))) =>
        CoEnv(\/-(ProjectIndex(hole, IntLit(mapping.get(idx).getOrElse(idx)).toCoEnv)))
      case co => co
    }

  /** Prune the provided `array` keeping only the indices in `indicesToKeep`. */
  object arrayRewrite {
    def apply(array: ConcatArrays[T, JoinFunc], indicesToKeep: Set[Int]): JoinFunc =
      rebuildArray(removeUnusedIndices[JoinFunc](flattenArray(array), indicesToKeep))

    private def flattenArray(array: ConcatArrays[T, JoinFunc]): List[JoinFunc] = {
      def inner(jf: JoinFunc): List[JoinFunc] =
        jf.resume match {
          case -\/(ConcatArrays(lhs, rhs)) => inner(lhs) ++ inner(rhs)
          case _                           => List(jf)
        }
      inner(Free.roll(array))
    }

    private def removeUnusedIndices[A](array: List[A], indicesToKeep: Set[Int]): List[A] =
      indicesToKeep.toList.sorted map array

    private def rebuildArray(funcs: List[JoinFunc]): JoinFunc = {
      def inner(funcs: List[JoinFunc]): JoinFunc = funcs match {
        case Nil          => Free.roll(EmptyArray[T, JoinFunc])
        case func :: Nil  => func
        case func :: rest => Free.roll(ConcatArrays(inner(rest), func))
      }
      inner(funcs.reverse)
    }
  }

  // TODO can we be more efficient?
  def indexMapping(state: StateAcc): Option[Mapping] =
    state.map(_.toList.sorted.zipWithIndex.map(_.rightMap(BigInt(_))).toMap)
}

// TODO `find` and `remap` impls should be returning a free algebra
// which is interpreted separately
object PruneArrays {
  import PATypes._

  private def annotateEmpty(state: StateAcc): Annotation =
    Annotation(state, None)

  private def haltRemap[F[_], A](out: F[A]): Output[F, A] =
    Output(None, out)

  private def default[IN[_]]
      : PruneArrays[IN] =
    new PruneArrays[IN] {
      def find[A](state: StateAcc, in: IN[A]): Annotation = annotateEmpty(None)
      def remap[A](env: StateAcc, state: StateAcc, in: IN[A]): Output[IN, A] = haltRemap(in)
    }

  implicit def coproduct[I[_], J[_]]
    (implicit I: PruneArrays[I], J: PruneArrays[J])
      : PruneArrays[Coproduct[I, J, ?]] =
    new PruneArrays[Coproduct[I, J, ?]] {

      def find[A](state: StateAcc, in: Coproduct[I, J, A]): Annotation =
        in.run.fold(I.find(state, _), J.find(state, _))

      def remap[A](env: StateAcc, state: StateAcc, in: Coproduct[I, J, A]): Output[Coproduct[I, J, ?], A] =
        in.run.fold(
          { i =>
            val Output(newState, out) = I.remap(env, state, i)
            Output(newState, Coproduct.leftc(out))
          },
          { j =>
            val Output(newState, out) = J.remap(env, state, j)
            Output(newState, Coproduct.rightc(out))
          })
    }

  implicit def read: PruneArrays[Const[Read, ?]] = default
  implicit def shiftedRead: PruneArrays[Const[ShiftedRead, ?]] = default
  implicit def deadEnd: PruneArrays[Const[DeadEnd, ?]] = default

  // TODO examine branches
  implicit def thetaJoin[T[_[_]]]: PruneArrays[ThetaJoin[T, ?]] = default
  // TODO examine branches
  implicit def equiJoin[T[_[_]]]: PruneArrays[EquiJoin[T, ?]] = default

  implicit def projectBucket[T[_[_]]: Recursive: Corecursive]
      : PruneArrays[ProjectBucket[T, ?]] =
    new PruneArrays[ProjectBucket[T, ?]] {

      val helpers = new PAHelpers[T]
      import helpers._

      def find[A](state: StateAcc, in: ProjectBucket[A]): Annotation = in match {
        case BucketField(_, value, name) =>
          annotateEmpty(findIndicesInFunc(value) |++| findIndicesInFunc(name) |++| state)
        case BucketIndex(_, value, index) =>
          annotateEmpty(findIndicesInFunc(value) |++| findIndicesInFunc(index) |++| state)
      }

      def remap[A](env: StateAcc, state: StateAcc, in: ProjectBucket[A]): Output[ProjectBucket, A] = {
        val mapping: Option[Mapping] = indexMapping(state)

        in match {
          case qs @ BucketField(src, value, name) =>
            def replacement(repl: Mapping): ProjectBucket[A] =
              BucketField(src, remapIndicesInFunc(value, repl), remapIndicesInFunc(name, repl))
            Output(state, mapping.cata(replacement, qs))
          case qs @ BucketIndex(src, value, index) =>
            def replacement(repl: Mapping): ProjectBucket[A] =
              BucketIndex(src, remapIndicesInFunc(value, repl), remapIndicesInFunc(index, repl))
            Output(state, mapping.cata(replacement, qs))
        }
      }
    }

  implicit def qscriptCore[T[_[_]]: Recursive: Corecursive]
      : PruneArrays[QScriptCore[T, ?]] =
    new PruneArrays[QScriptCore[T, ?]] {

      val helpers = new PAHelpers[T]
      import helpers._

      def find[A](state: StateAcc, in: QScriptCore[A]): Annotation = in match {
        case LeftShift(_, struct, _, repair) => // TODO examine struct and repair
          repair.resume match {
            case -\/(ConcatArrays(_, _)) =>
              Annotation(None, state) // annotate state as environment
            case _ =>
              default.find(state, in)
          }

        case Reduce(src, bucket, reducers, _) =>
          val bucketState: StateAcc = findIndicesInFunc(bucket)
          val reducersState: StateAcc = reducers.foldMap(_.foldMap[StateAcc](findIndicesInFunc(_)))
          annotateEmpty(bucketState |++| reducersState)

        case Union(_, _, _)     => default.find(state, in) // TODO examine branches
        case Subset(_, _, _, _) => default.find(state, in) // TODO examine branches

        case Map(_, func)    => annotateEmpty(findIndicesInFunc(func))
        case Filter(_, func) => annotateEmpty(findIndicesInFunc(func) |++| state)

        case Sort(_, bucket, order) =>
          val bucketState: StateAcc = findIndicesInFunc(bucket)
          val orderState: StateAcc = order.foldMap {
            case (f, _) => findIndicesInFunc(f)
          }
          annotateEmpty(bucketState |++| orderState |++| state)

        case Unreferenced() => default.find(state, in)
      }

      def remap[A](env: StateAcc, state: StateAcc, in: QScriptCore[A]): Output[QScriptCore, A] = {
        val mapping: Option[Mapping] = indexMapping(state)

        // ignore `env` everywhere except for `LeftShift`
        in match {
          case qs @ LeftShift(src, struct, id, repair) => // TODO examine struct and repair
            repair.resume match {
              case -\/(array @ ConcatArrays(_, _)) =>
                env.cata(
                  acc => {
                    val out = LeftShift(src, struct, id, arrayRewrite(array, acc.map(_.toInt).toSet))
                    Output(env, out)
                  },
                  default.remap(env, state, qs))
              case _ =>
                def replacement(repl: Mapping): QScriptCore[A] =
                  LeftShift(src, remapIndicesInFunc(struct, repl), id, repair)
                haltRemap(mapping.cata(replacement, qs))
            }

          case qs @ Reduce(src, bucket0, reducers0, repair) =>
            def reducers(repl: Mapping): List[ReduceFunc[FreeMap]] =
              reducers0.map(_.map(remapIndicesInFunc(_, repl)))
            def bucket(repl: Mapping): FreeMap =
              remapIndicesInFunc(bucket0, repl)
            def replacement(repl: Mapping): QScriptCore[A] =
              Reduce(src, bucket(repl), reducers(repl), repair)
            haltRemap(mapping.cata(replacement, qs))

          case qs @ Union(_, _, _)     => default.remap(env, state, qs) // TODO examine branches
          case qs @ Subset(_, _, _, _) => default.remap(env, state, qs) // TODO examine branches

          case qs @ Map(src, func) =>
            def replacement(repl: Mapping): QScriptCore[A] =
              Map(src, remapIndicesInFunc(func, repl))
            haltRemap(mapping.cata(replacement, qs))

          case qs @ Filter(src, func) =>
            def replacement(repl: Mapping): QScriptCore[A] =
              Filter(src, remapIndicesInFunc(func, repl))
            Output(state, mapping.cata(replacement, qs))

          case qs @ Sort(src, bucket0, order0) =>
            def bucket(repl: Mapping): FreeMap =
              remapIndicesInFunc(bucket0, repl)
            def order(repl: Mapping): NonEmptyList[(FreeMap, SortDir)] =
              order0.map(_.leftMap(remapIndicesInFunc(_, repl)))
            def replacement(repl: Mapping): QScriptCore[A] =
              Sort(src, bucket(repl), order(repl))
            Output(state, mapping.cata(replacement, qs))

          case qs @ Unreferenced() => default.remap(env, state, qs)
        }
      }
    }
}

class PAFindRemap[T[_[_]]: Recursive: Corecursive, F[_]: Functor] {
  import PATypes._

  type ArrayEnv[G[_], A] = EnvT[StateAcc, G, A]
  type ArrayState[A] = State[StateAcc, A]

  /** Given an input, we accumulate state and annotate the focus.
    *
    * The state collects the used indices and indicates if the dereferenced array can be pruned.
    * For example, if we deref an array non-statically, we cannot prune it.
    *
    * If the focus is an array that can be pruned, the annotatation is set to the state.
    * Else the annotation is set to `None`.
    *
    * T[F] => ArrayState[ArrayEnv[F, T[F]]]
    */
  def findIndices(implicit P: PruneArrays[F])
      : CoalgebraM[ArrayState, ArrayEnv[F, ?], T[F]] = tqs => {
    State(state => {
      val gtg = tqs.project
      val Annotation(newState, newEnv) = P.find[T[F]](state, gtg)
      (newState, EnvT((newEnv, gtg)))
    })
  }

  /** Given an annotated input, we produce an output with state.
    *
    * If the previous state provides indices, we remap array dereferences accordingly.
    *
    * If an array has an associated environment, we update the state
    * to be the environment and prune the array.
    *
    * ArrayEnv[F, T[F]] => ArrayState[T[F]]
    */
  def remapIndices(implicit P: PruneArrays[F])
      : AlgebraM[ArrayState, ArrayEnv[F, ?], T[F]] = arrenv => {
    val (env, qs): (StateAcc, F[T[F]]) = arrenv.run

    State(state => {
      val Output(newState, out) = P.remap[T[F]](env, state, qs)
      (newState, out.embed)
    })
  }
}
