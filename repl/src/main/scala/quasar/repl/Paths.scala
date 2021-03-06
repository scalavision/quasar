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

package quasar
package repl

import slamdata.Predef._

import java.lang.System
import java.nio.file.{Files => JFiles, Path => JPath, Paths => JPaths}

import cats.effect.Sync
import scalaz._, Scalaz._
import shims._

object Paths {
  val UserHome = "user.home"

  val TmpQuasarReplDirNames = NonEmptyList("tmp", "quasar-repl")
  val QuasarDirNames = NonEmptyList(".config", "quasar")
  val QuasarDataDirName = "data"
  val QuasarPluginsDirName = "plugins"

  def createTempFile[F[_]](prefix: String, suffix: String)(implicit F: Sync[F])
      : F[JPath] =
    F.delay(JFiles.createTempFile(prefix, suffix))

  def getBasePath[F[_]](implicit F: Sync[F]): F[JPath] =
    getUserHome.map(h => h.map(_.resolve(getPath(QuasarDirNames)))
      .getOrElse(getPath(TmpQuasarReplDirNames)))

  def getProp[F[_]](p: String)(implicit F: Sync[F]): F[Option[String]] =
    F.delay(Option(System.getProperty(p)))

  def getUserHome[F[_]](implicit F: Sync[F]): F[Option[JPath]] =
    getProp(UserHome).map(_.map(JPaths.get(_)))

  def mkdirs[F[_]](p: JPath)(implicit F: Sync[F]): F[Boolean] =
    F.delay(p.toFile.mkdirs())

  ////

  private def getPath(names: NonEmptyList[String]): JPath =
    JPaths.get(names.head, names.tail.toList :_*)

}
