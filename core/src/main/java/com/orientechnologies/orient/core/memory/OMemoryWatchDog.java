/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
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
package com.orientechnologies.orient.core.memory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.*;

import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.profiler.OProfiler.METRIC_TYPE;
import com.orientechnologies.common.profiler.OProfiler.OProfilerHookValue;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;

/**
 * This memory warning system will call the listener when we exceed the percentage of available memory specified. There should only
 * be one instance of this object created, since the usage threshold can only be set to one number.
 */
public class OMemoryWatchDog extends Thread {
  private final Map<Listener, Object> listeners    = new WeakHashMap<Listener, Object>(128);
  private int                         alertTimes   = 0;
  protected ReferenceQueue<Object>    monitorQueue = new ReferenceQueue<Object>();
  protected SoftReference<Object>     monitorRef   = new SoftReference<Object>(new Object(), monitorQueue);

  public static interface Listener {
    /**
     * Execute a soft free of memory resources.
     * 
     * @param iType
     *          OS or JVM
     * @param iFreeMemory
     *          Current used memory
     * @param iFreeMemoryPercentage
     *          Max memory
     */
    public void memoryUsageLow(long iFreeMemory, long iFreeMemoryPercentage);
  }

  /**
   * Create the memory watch dog with the default memory threshold.
   * 
   * @param iThreshold
   */
  public OMemoryWatchDog() {
    super("OrientDB MemoryWatchDog");

    setDaemon(true);
    start();
  }

  public void run() {
    Orient
        .instance()
        .getProfiler()
        .registerHookValue("system.memory.alerts", "Number of alerts received by JVM to free memory resources",
            METRIC_TYPE.COUNTER, new OProfilerHookValue() {
              public Object getValue() {
                return alertTimes;
              }
            });
      Orient
          .instance()
          .getProfiler()
          .registerHookValue("system.memory.lastGC", "Date of last System.gc() invocation",
              METRIC_TYPE.STAT, new OProfilerHookValue() {
                public Object getValue() {
                  return lastGC;
                }
              });

    while (true) {
      try {
        // WAITS FOR THE GC FREE
        monitorQueue.remove();

        // GC is freeing memory!
        alertTimes++;
        long maxMemory = Runtime.getRuntime().maxMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        int freeMemoryPer = (int) (freeMemory * 100 / maxMemory);

        if (OLogManager.instance().isDebugEnabled())
          OLogManager.instance().debug(this, "Free memory is low %s of %s (%d%%), calling listeners to free memory...",
              OFileUtils.getSizeAsString(freeMemory), OFileUtils.getSizeAsString(maxMemory), freeMemoryPer);

        final long timer = Orient.instance().getProfiler().startChrono();

        synchronized (listeners) {
          for (Listener listener : listeners.keySet()) {
            try {
              listener.memoryUsageLow(freeMemory, freeMemoryPer);
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        }

        Orient.instance().getProfiler().stopChrono("OMemoryWatchDog.freeResources", "WatchDog free resources", timer);

      } catch (Exception e) {
      } finally {
        // RE-INSTANTIATE THE MONITOR REF
        monitorRef = new SoftReference<Object>(new Object(), monitorQueue);
      }
    }
  }

  public Collection<Listener> getListeners() {
    synchronized (listeners) {
      return listeners.keySet();
    }
  }

  public Listener addListener(final Listener listener) {
    synchronized (listeners) {
      listeners.put(listener, listener);
    }
    return listener;
  }

  public boolean removeListener(final Listener listener) {
    synchronized (listeners) {
      return listeners.remove(listener) != null;
    }
  }

  private static long lastGC = 0;

  public static void freeMemoryForOptimization(final long iDelayTime) {
      freeMemory(iDelayTime, OGlobalConfiguration.GC_DELAY_FOR_OPTIMIZE.getValueAsLong());
  }

  public static void freeMemoryForResourceCleanup(final long iDelayTime) {
      freeMemory(iDelayTime, OGlobalConfiguration.GC_DELAY_FOR_RESOURCE_CLEANUP.getValueAsLong());
  }

  private static void freeMemory(final long iDelayTime, long minimalTimeAmount) {
      long dateFromLastGC = new Date().getTime() - lastGC;
      if (dateFromLastGC > minimalTimeAmount) {
          System.gc();
          lastGC = new Date().getTime();
          if (iDelayTime > 0)
            try {
              Thread.sleep(iDelayTime);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
      }
  }

}