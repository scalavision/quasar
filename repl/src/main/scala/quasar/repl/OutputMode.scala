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

package quasar.repl

import slamdata.Predef._

import scalaz._, Scalaz._

sealed abstract class OutputMode

object OutputMode {
  final case object Console extends OutputMode
  final case object File extends OutputMode

  def fromString(str: String): Option[OutputMode] = str.toLowerCase match {
    case "console" => Console.some
    case "file"    => File.some
    case _         => none
  }
}
