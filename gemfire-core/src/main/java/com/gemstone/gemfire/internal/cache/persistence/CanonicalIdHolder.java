/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
package com.gemstone.gemfire.internal.cache.persistence;

import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectLongHashMap;

/**
 * This class manages in memory copy of the canonical ids held in the disk
 * init file. It's used by the init file to assign numbers to new ids and 
 * retrieve existing ids.
 * 
 * This class is not thread safe, so it should be synchronized externally.
 * 
 * @author dsmith
 *
 */
public class CanonicalIdHolder {
  /**
   * Map of integer representation to canonicalized member ids.
   */
  private final IntObjectHashMap<Object> idToObject =
      new IntObjectHashMap<>(10);
  
  /**
   * Map of canonicalized member ids to integer representation.
   */
  private final ObjectLongHashMap<Object> objectToID =
      new ObjectLongHashMap<>(10);
  
  private int highestID = 0;
  
  /**
   * Add a mapping that we have recovered from disk
   */
  public void addMapping(int id, Object object) {
    //Store the mapping
    idToObject.put(id, object);
    objectToID.put(object, id);
    
    //increase the next canonical id the recovered id is higher than it.
    highestID = highestID  < id ? id : highestID;
  }
  
  /**
   * Get the id for a given object 
   */
  public int getId(Object object) {
    return (int)objectToID.get(object);
  }
  
  /**
   * Get the object for a given id.
   */
  public Object getObject(int id) {
    return idToObject.get(id);
  }
  
  /**
   * Create an id of the given object.
   * @return the id generated for this object.
   */
  public int createId(Object object) {
    assert !objectToID.containsKey(object);
    int id = ++highestID;
    objectToID.put(object, id);
    idToObject.put(id, object);
    return id;
  }
  
  /**
   * Get all of the objects that are mapped.
   * @return a map of id to object for all objects
   * held by this canonical id holder.
   */
  public IntObjectHashMap<Object> getAllMappings() {
    return idToObject;
  }
}
