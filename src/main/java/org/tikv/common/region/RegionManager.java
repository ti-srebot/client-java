/*
 *
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.tikv.common.region;

import static org.tikv.common.codec.KeyUtils.formatBytesUTF8;
import static org.tikv.common.util.KeyRangeUtils.makeRange;

import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import com.google.protobuf.ByteString;
import io.prometheus.client.Histogram;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.ReadOnlyPDClient;
import org.tikv.common.TiConfiguration;
import org.tikv.common.event.CacheInvalidateEvent;
import org.tikv.common.exception.GrpcException;
import org.tikv.common.exception.TiClientInternalException;
import org.tikv.common.key.Key;
import org.tikv.common.util.BackOffer;
import org.tikv.common.util.ChannelFactory;
import org.tikv.common.util.ConcreteBackOffer;
import org.tikv.common.util.Pair;
import org.tikv.kvproto.Metapb;
import org.tikv.kvproto.Metapb.Peer;
import org.tikv.kvproto.Metapb.StoreState;

@SuppressWarnings("UnstableApiUsage")
public class RegionManager {
  private static final Logger logger = LoggerFactory.getLogger(RegionManager.class);
  // TODO: the region cache logic need rewrite.
  // https://github.com/pingcap/tispark/issues/1170
  private final RegionCache cache;
  private final ScheduledExecutorService executor;
  private final UnreachableStoreChecker storeChecker;

  private final Function<CacheInvalidateEvent, Void> cacheInvalidateCallback;

  public static final Histogram GET_REGION_BY_KEY_REQUEST_LATENCY =
      Histogram.build()
          .name("client_java_get_region_by_requests_latency")
          .help("getRegionByKey request latency.")
          .register();

  // To avoid double retrieval, we used the async version of grpc
  // When rpc not returned, instead of call again, it wait for previous one done
  public RegionManager(
      TiConfiguration conf,
      ReadOnlyPDClient pdClient,
      Function<CacheInvalidateEvent, Void> cacheInvalidateCallback) {
    this.cache = new RegionCache(conf, pdClient);
    this.cacheInvalidateCallback = cacheInvalidateCallback;
    this.executor = null;
    this.storeChecker = null;
  }

  public RegionManager(
      TiConfiguration conf,
      ReadOnlyPDClient pdClient,
      Function<CacheInvalidateEvent, Void> cacheInvalidateCallback,
      ChannelFactory channelFactory,
      boolean enableGrpcForward) {
    this.cache = new RegionCache(conf, pdClient);
    this.cacheInvalidateCallback = cacheInvalidateCallback;
    if (enableGrpcForward) {
      UnreachableStoreChecker storeChecker = new UnreachableStoreChecker(channelFactory, pdClient);
      this.storeChecker = storeChecker;
      this.executor = Executors.newScheduledThreadPool(1);
      this.executor.scheduleAtFixedRate(storeChecker, 10, 10, TimeUnit.SECONDS);
    } else {
      this.storeChecker = null;
      this.executor = null;
    }
  }

  public RegionManager(TiConfiguration conf, ReadOnlyPDClient pdClient) {
    this.cache = new RegionCache(conf, pdClient);
    this.cacheInvalidateCallback = null;
    this.storeChecker = null;
    this.executor = null;
  }

  public synchronized void close() {
    if (this.executor != null) {
      this.executor.shutdownNow();
    }
  }

  public Function<CacheInvalidateEvent, Void> getCacheInvalidateCallback() {
    return cacheInvalidateCallback;
  }

  public ReadOnlyPDClient getPDClient() {
    return this.cache.pdClient;
  }

  public TiRegion getRegionByKey(ByteString key) {
    return getRegionByKey(key, ConcreteBackOffer.newGetBackOff());
  }

  public TiRegion getRegionByKey(ByteString key, BackOffer backOffer) {
    return cache.getRegionByKey(key, backOffer);
  }

  @Deprecated
  // Do not use GetRegionByID when retrying request.
  //
  //   A,B |_______|_____|
  //   A   |_____________|
  // Consider region A, B. After merge of (A, B) -> A, region ID B does not exist.
  // This request is unrecoverable.
  public TiRegion getRegionById(long regionId) {
    return cache.getRegionById(ConcreteBackOffer.newGetBackOff(), regionId);
  }

  public Pair<TiRegion, TiStore> getRegionStorePairByKey(ByteString key, BackOffer backOffer) {
    return getRegionStorePairByKey(key, TiStoreType.TiKV, backOffer);
  }

  public Pair<TiRegion, TiStore> getRegionStorePairByKey(ByteString key) {
    return getRegionStorePairByKey(key, TiStoreType.TiKV);
  }

  public Pair<TiRegion, TiStore> getRegionStorePairByKey(ByteString key, TiStoreType storeType) {
    return getRegionStorePairByKey(key, storeType, ConcreteBackOffer.newGetBackOff());
  }

  public Pair<TiRegion, TiStore> getRegionStorePairByKey(
      ByteString key, TiStoreType storeType, BackOffer backOffer) {
    TiRegion region = cache.getRegionByKey(key, backOffer);
    if (region == null) {
      throw new TiClientInternalException("Region not exist for key:" + formatBytesUTF8(key));
    }
    if (!region.isValid()) {
      throw new TiClientInternalException("Region invalid: " + region.toString());
    }

    TiStore store = null;
    if (storeType == TiStoreType.TiKV) {
      Peer peer = region.getCurrentReplica();
      store = cache.getStoreById(peer.getStoreId(), backOffer);
      if (store == null) {
        cache.clearAll();
      }
    } else {
      outerLoop:
      for (Peer peer : region.getLearnerList()) {
        TiStore s = getStoreById(peer.getStoreId(), backOffer);
        for (Metapb.StoreLabel label : s.getStore().getLabelsList()) {
          if (label.getKey().equals(storeType.getLabelKey())
              && label.getValue().equals(storeType.getLabelValue())) {
            store = s;
            break outerLoop;
          }
        }
      }
      if (store == null) {
        // clear the region cache so we may get the learner peer next time
        cache.invalidateRegion(region);
      }
    }

    if (store == null) {
      throw new TiClientInternalException(
          "Cannot find valid store on " + storeType + " for region " + region.toString());
    }

    return Pair.create(region, store);
  }

  public TiStore getStoreById(long id) {
    return getStoreById(id, ConcreteBackOffer.newGetBackOff());
  }

  public TiStore getStoreById(long id, BackOffer backOffer) {
    return cache.getStoreById(id, backOffer);
  }

  public void onRegionStale(TiRegion region) {
    cache.invalidateRegion(region);
  }

  public synchronized TiRegion updateLeader(TiRegion region, long storeId) {
    TiRegion newRegion = region.switchPeer(storeId);
    if (cache.updateRegion(region, newRegion)) {
      return newRegion;
    }
    // failed to switch leader, possibly region is outdated, we need to drop region cache from
    // regionCache
    logger.warn("Cannot find peer when updating leader (" + region.getId() + "," + storeId + ")");
    return null;
  }

  public synchronized void updateStore(TiStore oldStore, TiStore newStore) {
    if (cache.updateStore(oldStore, newStore)) {
      if (newStore.isUnreachable()) {
        logger.warn(
            String.format(
                "check health for store [%s] in background thread",
                newStore.getStore().getAddress()));
        this.storeChecker.scheduleStoreHealthCheck(newStore);
      }
    }
  }

  /** Clears all cache when some unexpected error occurs. */
  public void clearRegionCache() {
    cache.clearAll();
  }

  /**
   * Clears all cache when a TiKV server does not respond
   *
   * @param region region
   */
  public synchronized void onRequestFail(TiRegion region) {
    cache.invalidateRegion(region);
  }

  public void invalidateStore(long storeId) {
    cache.invalidateStore(storeId);
  }

  public void invalidateRegion(TiRegion region) {
    cache.invalidateRegion(region);
  }

  public static class RegionCache {
    private final Map<Long, TiRegion> regionCache;
    private final Map<Long, TiStore> storeCache;
    private final RangeMap<Key, Long> keyToRegionIdCache;
    private final ReadOnlyPDClient pdClient;
    private final TiConfiguration conf;

    public RegionCache(TiConfiguration conf, ReadOnlyPDClient pdClient) {
      regionCache = new HashMap<>();
      storeCache = new HashMap<>();

      keyToRegionIdCache = TreeRangeMap.create();
      this.conf = conf;
      this.pdClient = pdClient;
    }

    public synchronized TiRegion getRegionByKey(ByteString key, BackOffer backOffer) {
      Histogram.Timer requestTimer = GET_REGION_BY_KEY_REQUEST_LATENCY.startTimer();
      try {
        Long regionId;
        if (key.isEmpty()) {
          // if key is empty, it must be the start key.
          regionId = keyToRegionIdCache.get(Key.toRawKey(key, true));
        } else {
          regionId = keyToRegionIdCache.get(Key.toRawKey(key));
        }
        if (logger.isDebugEnabled()) {
          logger.debug(
              String.format("getRegionByKey key[%s] -> ID[%s]", formatBytesUTF8(key), regionId));
        }

        if (regionId == null) {
          logger.debug("Key not found in keyToRegionIdCache:" + formatBytesUTF8(key));
          Pair<Metapb.Region, Metapb.Peer> regionAndLeader =
              pdClient.getRegionByKey(backOffer, key);
          TiRegion region = createRegion(regionAndLeader.first, regionAndLeader.second, backOffer);
          if (!putRegion(region)) {
            throw new TiClientInternalException("Invalid Region: " + region.toString());
          }
          return region;
        }
        TiRegion region;
        region = regionCache.get(regionId);
        if (logger.isDebugEnabled()) {
          logger.debug(String.format("getRegionByKey ID[%s] -> Region[%s]", regionId, region));
        }

        return region;
      } finally {
        requestTimer.observeDuration();
      }
    }

    private synchronized boolean putRegion(TiRegion region) {
      if (logger.isDebugEnabled()) {
        logger.debug("putRegion: " + region);
      }
      regionCache.put(region.getId(), region);
      keyToRegionIdCache.put(makeRange(region.getStartKey(), region.getEndKey()), region.getId());
      return true;
    }

    @Deprecated
    private synchronized TiRegion getRegionById(BackOffer backOffer, long regionId) {
      TiRegion region = regionCache.get(regionId);
      if (logger.isDebugEnabled()) {
        logger.debug(String.format("getRegionByKey ID[%s] -> Region[%s]", regionId, region));
      }
      if (region == null) {
        Pair<Metapb.Region, Metapb.Peer> regionAndLeader =
            pdClient.getRegionByID(backOffer, regionId);
        region = createRegion(regionAndLeader.first, regionAndLeader.second, backOffer);
        if (!putRegion(region)) {
          throw new TiClientInternalException("Invalid Region: " + region.toString());
        }
      }
      return region;
    }

    private synchronized TiRegion getRegionFromCache(long regionId) {
      return regionCache.get(regionId);
    }

    /** Removes region associated with regionId from regionCache. */
    public synchronized void invalidateRegion(TiRegion region) {
      try {
        if (logger.isDebugEnabled()) {
          logger.debug(String.format("invalidateRegion ID[%s]", region.getId()));
        }
        TiRegion oldRegion = regionCache.get(region.getId());
        if (oldRegion != null && oldRegion == region) {
          keyToRegionIdCache.remove(makeRange(region.getStartKey(), region.getEndKey()));
          regionCache.remove(region.getId());
        }
      } catch (Exception ignore) {
      }
    }

    public synchronized boolean updateRegion(TiRegion expected, TiRegion region) {
      try {
        if (logger.isDebugEnabled()) {
          logger.debug(String.format("invalidateRegion ID[%s]", region.getId()));
        }
        TiRegion oldRegion = regionCache.get(region.getId());
        if (expected != oldRegion) {
          return false;
        } else {
          if (oldRegion != null) {
            keyToRegionIdCache.remove(makeRange(oldRegion.getStartKey(), oldRegion.getEndKey()));
          }
          putRegion(region);
          return true;
        }
      } catch (Exception ignore) {
        return false;
      }
    }

    public synchronized boolean updateStore(TiStore oldStore, TiStore newStore) {
      TiStore originStore = storeCache.get(oldStore.getId());
      if (originStore == oldStore) {
        storeCache.put(newStore.getId(), newStore);
        if (oldStore != null && oldStore.isUnreachable()) {
          oldStore.markReachable();
        }
        if (newStore.getProxyStore() != null) {
          newStore.markUnreachable();
        }
        return true;
      }
      return false;
    }

    public synchronized void invalidateAllRegionForStore(TiStore store) {
      TiStore oldStore = storeCache.get(store.getId());
      if (oldStore != store) {
        return;
      }
      List<TiRegion> regionToRemove = new ArrayList<>();
      for (TiRegion r : regionCache.values()) {
        if (r.getLeader().getStoreId() == store.getId()) {
          if (logger.isDebugEnabled()) {
            logger.debug(String.format("invalidateAllRegionForStore Region[%s]", r));
          }
          regionToRemove.add(r);
        }
      }

      logger.warn(String.format("invalid store [%d]", store.getId()));
      // remove region
      for (TiRegion r : regionToRemove) {
        keyToRegionIdCache.remove(makeRange(r.getStartKey(), r.getEndKey()));
        regionCache.remove(r.getId());
      }
    }

    public synchronized void invalidateStore(long storeId) {
      TiStore store = storeCache.remove(storeId);
      if (store != null) {
        store.markReachable();
      }
    }

    public synchronized TiStore getStoreById(long id, BackOffer backOffer) {
      try {
        TiStore store = storeCache.get(id);
        if (store == null) {
          store = new TiStore(pdClient.getStore(backOffer, id));
        }
        if (store.getStore().getState().equals(StoreState.Tombstone)) {
          return null;
        }
        storeCache.put(id, store);
        return store;
      } catch (Exception e) {
        throw new GrpcException(e);
      }
    }

    private List<TiStore> getRegionStore(List<Metapb.Peer> peers, BackOffer backOffer) {
      return peers
          .stream()
          .map(p -> getStoreById(p.getStoreId(), backOffer))
          .collect(Collectors.toList());
    }

    private TiRegion createRegion(Metapb.Region region, Metapb.Peer leader, BackOffer backOffer) {
      List<Metapb.Peer> peers = region.getPeersList();
      List<TiStore> stores = getRegionStore(peers, backOffer);
      return new TiRegion(conf, region, leader, peers, stores);
    }

    public synchronized void clearAll() {
      keyToRegionIdCache.clear();
      regionCache.clear();
    }
  }
}
