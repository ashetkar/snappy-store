/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package io.snappydata.collection;

import java.util.function.IntPredicate;

import com.koloboke.compile.KolobokeSet;

@KolobokeSet
public abstract class IntHashSet {

  public static IntHashSet withExpectedSize(int expectedSize) {
    return new KolobokeIntHashSet(expectedSize);
  }

  public abstract boolean add(int key);

  public abstract boolean contains(int key);

  public abstract boolean forEachWhile(IntPredicate predicate);

  public abstract int size();

  public abstract void clear();
}