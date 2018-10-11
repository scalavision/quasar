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

package quasar.yggdrasil
package table

import quasar.common.{CPathNode, CPathField, CPathIndex, CPathMeta}

import tectonic.{Plate, Signal}

trait CPathPlate[A] { self: Plate[A] =>
  protected var cursor: List[CPathNode] = Nil
  protected var nextIndex: List[Int] = 0 :: Nil

  abstract override def nestMap(pathComponent: CharSequence): Signal = {
    cursor ::= CPathField(pathComponent.toString)
    nextIndex ::= 0
    self.nestMap(pathComponent)
  }

  abstract override def nestArr(): Signal = {
    val idx :: tail = nextIndex
    nextIndex = 0 :: (idx + 1) :: tail

    cursor ::= CPathIndex(idx)
    self.nestArr()
  }

  abstract override def nestMeta(pathComponent: CharSequence): Signal = {
    cursor ::= CPathMeta(pathComponent.toString)
    nextIndex ::= 0
    self.nestMeta(pathComponent)
  }

  abstract override def unnest(): Signal = {
    nextIndex = nextIndex.tail
    cursor = cursor.tail
    self.unnest()
  }
}
