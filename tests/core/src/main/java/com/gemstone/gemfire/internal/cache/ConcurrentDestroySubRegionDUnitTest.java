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
package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache30.CacheTestCase;

import dunit.AsyncInvocation;
import dunit.Host;
import dunit.SerializableRunnable;
import dunit.VM;

/**
 * @author dsmith
 *
 */
public class ConcurrentDestroySubRegionDUnitTest extends CacheTestCase {

  /**
   * @param name
   */
  public ConcurrentDestroySubRegionDUnitTest(String name) {
    super(name);
  }

  public void test() throws Throwable {
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);


    for(int i =0; i < 200; i++) {
      final SerializableRunnable createParent = new SerializableRunnable() {

        public void run() {
          Cache cache = getCache();
          AttributesFactory af = new AttributesFactory();
          af.setScope(Scope.DISTRIBUTED_ACK);
          af.setDataPolicy(DataPolicy.REPLICATE);
          Region region = cache.createRegion("region", af.create());
        }
      };

      vm0.invoke(createParent);
      vm1.invoke(createParent);

      final SerializableRunnable createChild = new SerializableRunnable() {

        public void run() {
          Cache cache = getCache();
          Region region = cache.getRegion("region");
          if(region != null) {
            AttributesFactory af = new AttributesFactory();
            af.setScope(Scope.DISTRIBUTED_ACK);
            af.setDataPolicy(DataPolicy.REPLICATE);
            Region subregion = region.createSubregion("subregion", af.create());
          }

        }
      };
      vm0.invoke(createChild);

      final SerializableRunnable destroyParent = new SerializableRunnable() {

        public void run() {
          Cache cache = getCache();
          Region region = cache.getRegion("/region");
          region.destroyRegion();
        }
      };

      AsyncInvocation future = vm0.invokeAsync(destroyParent);
      try {
        vm1.invoke(createChild);
      } catch(Exception e) {
        if(!(e.getCause() instanceof RegionDestroyedException)) {
          fail("Wrong exception", e);
        }
        RegionDestroyedException rde = (RegionDestroyedException) e.getCause();
        assertEquals("Error on loop " + i, "/region", rde.getRegionFullPath());
      }
      future.getResult(60 * 1000);
    }
  }
  
  public void testPartitionedRegion() throws Throwable {
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);


    for(int i =0; i < 50; i++) {
      final SerializableRunnable createParent = new SerializableRunnable() {

        public void run() {
          Cache cache = getCache();
          AttributesFactory af = new AttributesFactory();
          af.setScope(Scope.DISTRIBUTED_ACK);
          af.setDataPolicy(DataPolicy.REPLICATE);
          Region region = cache.createRegion("region", af.create());
        }
      };

      vm0.invoke(createParent);
      vm1.invoke(createParent);

      final SerializableRunnable createChild = new SerializableRunnable() {

        public void run() {
          Cache cache = getCache();
          Region region = cache.getRegion("region");
          if(region != null) {
            AttributesFactory af = new AttributesFactory();
            af.setDataPolicy(DataPolicy.PARTITION);
            Region subregion = region.createSubregion("subregion", af.create());
          }

        }
      };
      vm0.invoke(createChild);

      final SerializableRunnable destroyParent = new SerializableRunnable() {

        public void run() {
          Cache cache = getCache();
          Region region = cache.getRegion("/region");
          region.destroyRegion();
        }
      };

      AsyncInvocation future = vm0.invokeAsync(destroyParent);
      try {
        vm1.invoke(createChild);
      } catch(Exception e) {
        if(!(e.getCause() instanceof RegionDestroyedException)) {
          fail("Wrong exception", e);
        }
        RegionDestroyedException rde = (RegionDestroyedException) e.getCause();
        assertEquals("Error on loop " + i, "/region", rde.getRegionFullPath());
      }
      future.getResult(60 * 1000);
    }
  }

}
