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
package com.gemstone.gemfire.memcached;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.memcached.GemFireMemcachedServer.Protocol;

import junit.framework.TestCase;

import net.spy.memcached.CASResponse;
import net.spy.memcached.CASValue;
import net.spy.memcached.MemcachedClient;


public class GemcachedDevelopmentJUnitTest extends TestCase {

  private static final Logger logger = Logger.getLogger(GemcachedDevelopmentJUnitTest.class.getCanonicalName());
  
  protected static int PORT = 0;
  
  private GemFireMemcachedServer server;
  
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    PORT = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    this.server = new GemFireMemcachedServer(PORT, getProtocol());
    server.start();
    logger.addHandler(new StreamHandler());
    logger.info("SWAP:Running test:"+getName());
    logger.info("SWAP:userdir:"+System.getProperty("user.dir"));
  }
  
  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    this.server.shutdown();
  }

  protected Protocol getProtocol() {
    return Protocol.ASCII;
  }

  public void testPutGet() throws Exception {
    MemcachedClient client = createMemcachedClient();
    Future<Boolean> f = client.add("key", 10, "myStringValue");
    assertTrue(f.get());
    Future<Boolean> f1 = client.add("key1", 10, "myStringValue1");
    assertTrue(f1.get());
    assertEquals("myStringValue", client.get("key"));
    assertEquals("myStringValue1", client.get("key1"));
    assertNull(client.get("nonExistentkey"));
    // zero exp
    f = client.add("Hello", 0, "World");
    Thread.sleep(1100);
    assertEquals("World", client.get("Hello"));
  }

  public void testSet() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> f = client.set("key", 10, "myStringValue");
    assertTrue(f.get());
  }
  
  public void testAdd() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> f = client.add("key", 10, "newVal");
    assertFalse(f.get());
  }

  public void testReplace() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> b = client.replace("key", 10, "newVal");
    assertTrue(b.get());
    b = client.replace("nonExistentkey", 10, "val");
    assertFalse(b.get());
    b = client.replace("key", 10, "myStringValue");
    assertTrue(b.get());
  }
  
  public void testMultiGet() throws Exception {
    MemcachedClient client = bootstrapClient();
    Map<String, Object> val = client.getBulk("key", "key1");
    assertEquals(2, val.size());
    assertEquals("myStringValue", val.get("key"));
    assertEquals("myStringValue1", val.get("key1"));
    client.add("Hello", 0, "World");
    Thread.sleep(1100);
    assertEquals("World", client.get("Hello"));
  }
  
  public void testDelete() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> b = client.delete("key");
    assertTrue(b.get());
    b = client.delete("nonExistentkey");
    assertFalse(b.get());
  }
  
  public void testFlush() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> b = client.flush();
    assertTrue(b.get());
    assertNull(client.get("key"));
    assertNull(client.get("key1"));
  }

  public void testFlushDelay() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> b = client.flush(5);
    assertTrue(b.get());
    assertNotNull(client.get("key"));
    assertNotNull(client.get("key1"));
    Thread.sleep(8*1000);
    assertNull(client.get("key"));
    assertNull(client.get("key1"));
  }

  public void testExpiration() throws Exception {
    MemcachedClient client = bootstrapClient();
    Thread.sleep(15*1000); // we add with expiration 10 seconds
    assertNull(client.get("key"));
  }
  
  public void testLongExpiration() throws Exception {
    MemcachedClient client = bootstrapClient();
    client.add("newKey", (int)System.currentTimeMillis() - 60 *1000, "newValue");
    Thread.sleep(15 *1000);
    assertEquals("newValue", client.get("newKey"));
  }
  
  public void testAppend() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> b = client.append(0, "key", "WithAddition");
    assertTrue(b.get());
    assertEquals("myStringValueWithAddition", client.get("key"));
    b = client.append(0, "appendkey", "val");
    assertFalse(b.get());
    assertNull(client.get("appendkey"));
  }
  
  public void testPrepend() throws Exception {
    MemcachedClient client = bootstrapClient();
    Future<Boolean> b = client.prepend(0, "key", "prepended");
    assertTrue(b.get());
    assertEquals("prependedmyStringValue", client.get("key"));
    b = client.prepend(0, "prependkey", "val");
    assertFalse(b.get());
    assertNull(client.get("prependkey"));
  }
  
  public void testIncr() throws Exception {
    MemcachedClient client = bootstrapClient();
    client.add("incrkey", 10, 99).get();
    assertEquals(104, client.incr("incrkey", 5));
    assertEquals(105, client.incr("incrkey", 1));
    assertEquals(-1, client.incr("inckey1", 10));
  }
  
  public void testDecr() throws Exception {
    MemcachedClient client = bootstrapClient();
    client.add("decrkey", 10, 99).get();
    assertEquals(95, client.decr("decrkey", 4));
    assertEquals(94, client.decr("decrkey", 1));
    assertEquals(-1, client.decr("decrkey1", 77));
  }
  
  public void testGets() throws Exception {
    MemcachedClient client = bootstrapClient();
    client.add("getskey", 10, "casValue").get();
    CASValue<Object> val = client.gets("getskey");
    long oldCas = val.getCas();
    assertEquals("casValue", val.getValue());
    client.replace("getskey", 10, "myNewVal").get();
    val = client.gets("getskey");
    assertEquals(oldCas + 1, val.getCas());
    assertEquals("myNewVal", val.getValue());
  }
  
  public void testCas() throws Exception {
    MemcachedClient client = bootstrapClient();
    client.add("caskey", 10, "casValue").get();
    CASValue<Object> val = client.gets("caskey");
    assertEquals("casValue", val.getValue());
    CASResponse r = client.cas("caskey", val.getCas(), "newValue");
    assertEquals(CASResponse.OK, r);
    r = client.cas("caskey", val.getCas(), "newValue2");
    assertEquals(CASResponse.EXISTS, r);
  }

  public void testStats() throws Exception {
    MemcachedClient client = bootstrapClient();
    Map stats = client.getStats();
    logger.info("stats:"+stats+" val:"+stats.values().toArray()[0]);
    assertEquals(1, stats.size());
    assertTrue(((Map)stats.values().toArray()[0]).isEmpty());
    assertTrue(client.add("keystats", 1, "stats").get());
  }
  
  private MemcachedClient bootstrapClient() throws IOException,
      UnknownHostException, InterruptedException, ExecutionException {
    MemcachedClient client = createMemcachedClient();
    Future<Boolean> f = client.add("key", 10, "myStringValue");
    f.get();
    Future<Boolean> f1 = client.add("key1", 10, "myStringValue1");
    f1.get();
    return client;
  }

  protected MemcachedClient createMemcachedClient() throws IOException,
      UnknownHostException {
    MemcachedClient client = new MemcachedClient(new InetSocketAddress(
        SocketCreator.getLocalHost(), PORT));
    return client;
  }
}
