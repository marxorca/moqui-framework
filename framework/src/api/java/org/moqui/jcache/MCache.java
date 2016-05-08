/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.jcache;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CompletionListener;
import javax.cache.management.CacheStatisticsMXBean;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: implement size limit with size check and eviction done in separate thread; running every 30 seconds?

/** A simple implementation of the javax.cache.Cache interface. Basically a wrapper around a Map with stats and expiry. */
public class MCache<K, V> implements Cache<K, V> {
    private static final Logger logger = LoggerFactory.getLogger(MCache.class);

    private String name;
    private CacheManager manager;
    private CompleteConfiguration<K, V> configuration;
    // NOTE: could use ConcurrentHashMap here for atomic ops, but complicated by the MEntry for absent/present checks
    private HashMap<K, MEntry<K, V>> entryStore = new HashMap<>();
    // currently for future reference, no runtime type checking
    // private Class<K> keyClass = null;
    // private Class<V> valueClass = null;

    private MStats stats = new MStats();
    private boolean statsEnabled = true;

    private Duration accessDuration = null;
    private Duration creationDuration = null;
    private Duration updateDuration = null;
    private final boolean hasExpiry;
    private boolean isClosed = false;

    private EvictRunnable evictRunnable = null;
    private ScheduledFuture<?> evictFuture = null;

    private static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MCacheEvict");
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        public Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MCacheEvict-" + threadNumber.getAndIncrement()); }
    }
    private static ScheduledThreadPoolExecutor workerPool = new ScheduledThreadPoolExecutor(1, new WorkerThreadFactory());
    static { workerPool.setRemoveOnCancelPolicy(true); }

    public static class MCacheConfiguration<K, V> extends MutableConfiguration<K, V> {
        public MCacheConfiguration() { super(); }
        public MCacheConfiguration(CompleteConfiguration<K, V> conf) { super(conf); }
        int maxEntries = 0;
        long maxCheckSeconds = 30;
        /** Set maximum number of entries in the cache, 0 means no limit (default). Limit is enforced in a scheduled worker, not on put operations. */
        public MCacheConfiguration<K, V> setMaxEntries(int elements) { maxEntries = elements; return this; }
        public int getMaxEntries() { return maxEntries; }
        /** Set maximum number of entries in the cache, 0 means no limit (default). */
        public MCacheConfiguration<K, V> setMaxCheckSeconds(long seconds) { maxCheckSeconds = seconds; return this; }
        public long getMaxCheckSeconds() { return maxCheckSeconds; }
    }

    /** Supports a few configurations but both manager and configuration can be null. */
    public MCache(String name, CacheManager manager, CompleteConfiguration<K, V> configuration) {
        this.name = name;
        this.manager = manager;
        this.configuration = configuration;
        if (configuration != null) {
            statsEnabled = configuration.isStatisticsEnabled();

            if (configuration.getExpiryPolicyFactory() != null) {
                ExpiryPolicy ep = configuration.getExpiryPolicyFactory().create();
                accessDuration = ep.getExpiryForAccess();
                if (accessDuration != null && accessDuration.isEternal()) accessDuration = null;
                creationDuration = ep.getExpiryForCreation();
                if (creationDuration != null && creationDuration.isEternal()) creationDuration = null;
                updateDuration = ep.getExpiryForUpdate();
                if (updateDuration != null && updateDuration.isEternal()) updateDuration = null;
            }

            // keyClass = configuration.getKeyType();
            // valueClass = configuration.getValueType();
            // TODO: support any other configuration?

            if (configuration instanceof MCacheConfiguration) {
                MCacheConfiguration<K, V> mCacheConf = (MCacheConfiguration<K, V>) configuration;

                if (mCacheConf.maxEntries > 0) {
                    evictRunnable = new EvictRunnable(this, mCacheConf.maxEntries);
                    evictFuture = workerPool.scheduleWithFixedDelay(evictRunnable, 30, mCacheConf.maxCheckSeconds, TimeUnit.SECONDS);
                }
            }
        }
        hasExpiry = accessDuration != null || creationDuration != null || updateDuration != null;
    }

    public synchronized void setMaxEntries(int elements) {
        if (elements == 0) {
            if (evictRunnable != null) {
                evictRunnable = null;
                evictFuture.cancel(false);
                evictFuture = null;
            }
        } else {
            if (evictRunnable != null) {
                evictRunnable.maxEntries = elements;
            } else {
                evictRunnable = new EvictRunnable(this, elements);
                long maxCheckSeconds = 30;
                if (configuration instanceof MCacheConfiguration) maxCheckSeconds = ((MCacheConfiguration) configuration).maxCheckSeconds;
                evictFuture = workerPool.scheduleWithFixedDelay(evictRunnable, 1, maxCheckSeconds, TimeUnit.SECONDS);
            }
        }
    }
    public int getMaxEntries() { return evictRunnable != null ? evictRunnable.maxEntries : 0; }

    @Override
    public String getName() { return name; }

    @Override
    public V get(K key) {
        MEntry<K, V> entry = getEntryInternal(key, null, null, System.currentTimeMillis());
        if (entry == null) return null;
        return entry.value;
    }
    public V get(K key, ExpiryPolicy policy) {
        MEntry<K, V> entry = getEntryInternal(key, policy, null, System.currentTimeMillis());
        if (entry == null) return null;
        return entry.value;
    }
    /** Get with expire if the entry's last updated time is before the expireBeforeTime.
     * Useful when last updated time of a resource is known to see if the cached entry is out of date. */
    public V get(K key, long expireBeforeTime) {
        MEntry<K, V> entry = getEntryInternal(key, null, expireBeforeTime, System.currentTimeMillis());
        if (entry == null) return null;
        return entry.value;
    }
    /** Get an entry, if it is in the cache and not expired, otherwise returns null. The policy can be null to use cache's policy. */
    public MEntry<K, V> getEntry(final K key, final ExpiryPolicy policy) {
        return getEntryInternal(key, policy, null, System.currentTimeMillis());
    }
    /** Simple entry get, doesn't check if expired. */
    public MEntry<K, V> getEntryNoCheck(K key) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        MEntry<K, V> entry = entryStore.get(key);
        if (entry != null) {
            if (statsEnabled) { stats.gets++; stats.hits++; }
            long accessTime = System.currentTimeMillis();
            entry.accessCount++; if (accessTime > entry.lastAccessTime) entry.lastAccessTime = accessTime;
        } else {
            if (statsEnabled) { stats.gets++; stats.misses++; }
        }
        return entry;
    }
    private MEntry<K, V> getEntryInternal(final K key, final ExpiryPolicy policy, final Long expireBeforeTime, long currentTime) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        MEntry<K, V> entry = entryStore.get(key);

        if (entry != null) {
            if (policy != null) {
                if (entry.isExpired(currentTime, policy)) {
                    entryStore.remove(key);
                    entry = null;
                    if (statsEnabled) stats.countExpire();
                }
            } else if (hasExpiry) {
                if (entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                    entryStore.remove(key);
                    entry = null;
                    if (statsEnabled) stats.countExpire();
                }
            }

            if (expireBeforeTime != null && entry != null && entry.lastUpdatedTime < expireBeforeTime) {
                entryStore.remove(key);
                entry = null;
                if (statsEnabled) stats.countExpire();
            }

            if (entry != null) {
                if (statsEnabled) { stats.gets++; stats.hits++; }
                entry.accessCount++; if (currentTime > entry.lastAccessTime) entry.lastAccessTime = currentTime;
            } else {
                if (statsEnabled) { stats.gets++; stats.misses++; }
            }
        } else {
            if (statsEnabled) { stats.gets++; stats.misses++; }
        }

        return entry;
    }
    private MEntry<K, V> getCheckExpired(K key) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        MEntry<K, V> entry = entryStore.get(key);
        if (hasExpiry && entry != null && entry.isExpired(accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            if (statsEnabled) stats.countExpire();
        }
        return entry;
    }
    private MEntry<K, V> getCheckExpired(K key, long currentTime) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        MEntry<K, V> entry = entryStore.get(key);
        if (hasExpiry && entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            if (statsEnabled) stats.countExpire();
        }
        return entry;
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
        long currentTime = System.currentTimeMillis();
        Map<K, V> results = new HashMap<>();
        for (K key: keys) {
            MEntry<K, V> entry = getEntryInternal(key, null, null, currentTime);
            results.put(key, entry != null ? entry.value : null);
        }
        return results;
    }
    @Override
    public boolean containsKey(K key) {
        MEntry<K, V> entry = getCheckExpired(key);
        return entry != null;
    }

    @Override
    public void put(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        MEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
        } else {
            entry = new MEntry<>(key, value, currentTime);
            entryStore.put(key, entry);
            if (statsEnabled) stats.puts++;
        }
    }
    @Override
    public V getAndPut(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        MEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            V oldValue = entry.value;
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
            return oldValue;
        } else {
            entry = new MEntry<>(key, value, currentTime);
            entryStore.put(key, entry);
            if (statsEnabled) stats.puts++;
            return null;
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        if (map == null) return;
        for (Map.Entry<? extends K, ? extends V> me: map.entrySet()) getAndPut(me.getKey(), me.getValue());
    }
    @Override
    public boolean putIfAbsent(K key, V value) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            return false;
        } else {
            entry = new MEntry<>(key, value, currentTime);
            entryStore.put(key, entry);
            if (statsEnabled) stats.puts++;
            return true;
        }
    }

    @Override
    public boolean remove(K key) {
        MEntry<K, V> entry = getCheckExpired(key);
        if (entry != null) {
            entryStore.remove(key);
            if (statsEnabled) stats.countRemoval();
            return true;
        } else {
            return false;
        }
    }
    @Override
    public boolean remove(K key, V oldValue) {
        MEntry<K, V> entry = getCheckExpired(key);

        if (entry != null) {
            boolean remove = entry.valueEquals(oldValue);
            if (oldValue != null) { if (oldValue.equals(entry.value)) remove = true; }
            else if (entry.value == null) { remove = true; }
            if (remove) {
                entryStore.remove(key);
                if (statsEnabled) stats.countRemoval();
            }
            return remove;
        } else {
            return false;
        }
    }

    @Override
    public V getAndRemove(K key) {
        // get entry, count hit/miss
        MEntry<K, V> entry = getEntryInternal(key, null, null, System.currentTimeMillis());
        if (entry != null) {
            V oldValue = entry.value;
            entryStore.remove(key);
            if (statsEnabled) stats.countRemoval();
            return oldValue;
        }
        return null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = getCheckExpired(key, currentTime);

        if (entry != null) {
            boolean replace = entry.valueEquals(oldValue);
            if (replace) {
                entry.setValue(newValue, currentTime);
                if (statsEnabled) stats.puts++;
            }
            return replace;
        } else {
            return false;
        }
    }

    @Override
    public boolean replace(K key, V value) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = getCheckExpired(key, currentTime);

        if (entry != null) {
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public V getAndReplace(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        MEntry<K, V> entry = getEntryInternal(key, null, null, currentTime);
        if (entry != null) {
            V oldValue = entry.value;
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
            return oldValue;
        } else {
            return null;
        }
    }

    @Override
    public void removeAll(Set<? extends K> keys) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        for (K key: keys) remove(key);
    }

    @Override
    public void removeAll() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        int size = entryStore.size();
        entryStore.clear();
        if (statsEnabled) stats.countBulkRemoval(size);
    }

    @Override
    public void clear() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        // don't track removals or do anything else, removeAll does that
        entryStore.clear();
    }

    @Override
    public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
        if (configuration == null) return null;
        if (clazz.isAssignableFrom(configuration.getClass())) return clazz.cast(configuration);
        throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with configuration class " + configuration.getClass().getName());
    }

    @Override
    public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, CompletionListener completionListener) {
        throw new CacheException("loadAll not supported in MCache");
    }
    @Override
    public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments) throws EntryProcessorException {
        throw new CacheException("invoke not supported in MCache");
    }
    @Override
    public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys, EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
        throw new CacheException("invokeAll not supported in MCache");
    }
    @Override
    public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new CacheException("registerCacheEntryListener not supported in MCache");
    }
    @Override
    public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new CacheException("deregisterCacheEntryListener not supported in MCache");
    }

    @Override
    public CacheManager getCacheManager() { return manager; }

    @Override
    public void close() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is already closed");
        isClosed = true;
        entryStore.clear();
    }
    @Override
    public boolean isClosed() { return isClosed; }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(this.getClass())) return clazz.cast(this);
        throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with MCache");
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        return new CacheIterator<>(this);
    }

    public static class CacheIterator<K, V> implements Iterator<Entry<K, V>> {
        final MCache<K, V> mCache;
        final long initialTime;
        final ArrayList<MEntry<K, V>> entryList;
        final int maxIndex;
        int curIndex = -1;
        MEntry<K, V> curEntry = null;

        CacheIterator(MCache<K, V> mCache) {
            this.mCache = mCache;
            entryList = new ArrayList<>(mCache.entryStore.values());
            maxIndex = entryList.size() - 1;
            initialTime = System.currentTimeMillis();
        }

        @Override
        public boolean hasNext() { return curIndex < maxIndex; }

        @Override
        public Entry<K, V> next() {
            curEntry = null;
            while (curIndex < maxIndex) {
                curIndex++;
                curEntry = entryList.get(curIndex);
                if (curEntry.isExpired) {
                    curEntry = null;
                } else if (mCache.hasExpiry && curEntry.isExpired(initialTime, mCache.accessDuration, mCache.creationDuration, mCache.updateDuration)) {
                    mCache.entryStore.remove(curEntry.getKey());
                    if (mCache.statsEnabled) mCache.stats.countExpire();
                    curEntry = null;
                } else {
                    if (mCache.statsEnabled)  { mCache.stats.gets++; mCache.stats.hits++; }
                    break;
                }
            }
            return curEntry;
        }

        @Override
        public void remove() {
            if (curEntry != null) {
                mCache.entryStore.remove(curEntry.getKey());
                if (mCache.statsEnabled) mCache.stats.countRemoval();
                curEntry = null;
            }
        }
    }

    /** Gets all entries, checking for expiry and counts a get for each */
    public ArrayList<Entry<K, V>> getEntryList() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        ArrayList<Entry<K, V>> entryList = new ArrayList<>(keyListSize);
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            MEntry<K, V> entry = getCheckExpired(key, currentTime);
            if (entry != null) {
                entryList.add(entry);
                if (statsEnabled) { stats.gets++; stats.hits++; }
                entry.accessCount++; if (currentTime > entry.lastAccessTime) entry.lastAccessTime = currentTime;
            }
        }
        return entryList;
    }
    public int clearExpired() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        if (!hasExpiry) return 0;
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        int expireCount = 0;
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            MEntry<K, V> entry = entryStore.get(key);
            if (entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                entryStore.remove(key);
                if (statsEnabled) stats.countExpire();
                expireCount++;
            }
        }
        return expireCount;
    }
    public CacheStatisticsMXBean getStats() { return stats; }
    public MStats getMStats() { return stats; }
    public int size() { return entryStore.size(); }

    public Duration getAccessDuration() { return accessDuration; }
    public Duration getCreationDuration() { return creationDuration; }
    public Duration getUpdateDuration() { return updateDuration; }

    public static class MEntry<K, V> implements Cache.Entry<K, V> {
        K key;
        V value;
        private long createdTime;
        private long lastUpdatedTime;
        long lastAccessTime;
        long accessCount = 0;
        private boolean isExpired = false;

        MEntry(K key, V value, long createdTime) {
            this.key = key;
            this.value = value;
            this.createdTime = createdTime;
            lastUpdatedTime = createdTime;
            lastAccessTime = createdTime;
        }

        @Override
        public K getKey() { return key; }
        @Override
        public V getValue() { return value; }
        @Override
        public <T> T unwrap(Class<T> clazz) {
            if (clazz.isAssignableFrom(this.getClass())) return clazz.cast(this);
            throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with MCache.MEntry");
        }

        boolean valueEquals(V otherValue) {
            if (otherValue == null) {
                return value == null;
            } else {
                return otherValue.equals(value);
            }
        }
        void setValue(V val, long updateTime) {
            if (updateTime > lastUpdatedTime) {
                value = val;
                lastUpdatedTime = updateTime;
            }
        }

        public long getCreatedTime() { return createdTime; }
        public long getLastUpdatedTime() { return lastUpdatedTime; }
        public long getLastAccessTime() { return lastAccessTime; }
        public long getAccessCount() { return accessCount; }

        /* done directly on fields for performance reasons
        void countAccess(long accessTime) {
            accessCount++; if (accessTime > lastAccessTime) lastAccessTime = accessTime;
        }
        */
        public boolean isExpired(ExpiryPolicy policy) {
            return isExpired(System.currentTimeMillis(), policy.getExpiryForAccess(), policy.getExpiryForCreation(),
                    policy.getExpiryForUpdate());
        }
        boolean isExpired(long accessTime, ExpiryPolicy policy) {
            return isExpired(accessTime, policy.getExpiryForAccess(), policy.getExpiryForCreation(),
                    policy.getExpiryForUpdate());
        }
        public boolean isExpired(Duration accessDuration, Duration creationDuration, Duration updateDuration) {
            return isExpired(System.currentTimeMillis(), accessDuration, creationDuration, updateDuration);
        }
        boolean isExpired(long accessTime, Duration accessDuration, Duration creationDuration, Duration updateDuration) {
            if (isExpired) return true;
            if (accessDuration != null && !accessDuration.isEternal()) {
                if (accessDuration.getAdjustedTime(lastAccessTime) < accessTime) { isExpired = true; return true; }
            }
            if (creationDuration != null && !creationDuration.isEternal()) {
                if (creationDuration.getAdjustedTime(createdTime) < accessTime) { isExpired = true; return true; }
            }
            if (updateDuration != null && !updateDuration.isEternal()) {
                if (updateDuration.getAdjustedTime(lastUpdatedTime) < accessTime) { isExpired = true; return true; }
            }
            return false;
        }
    }

    public static class MStats implements CacheStatisticsMXBean {
        long hits = 0;
        long misses = 0;
        long gets = 0;

        long puts = 0;
        long removals = 0;
        long evictions = 0;
        long expires = 0;

        // long totalGetMicros = 0, totalPutMicros = 0, totalRemoveMicros = 0;

        @Override
        public void clear() {
            hits = 0;
            misses = 0;
            gets = 0;
            puts = 0;
            removals = 0;
            evictions = 0;
            expires = 0;
        }

        @Override
        public long getCacheHits() { return hits; }
        @Override
        public float getCacheHitPercentage() { return (hits / gets) * 100; }
        @Override
        public long getCacheMisses() { return misses; }
        @Override
        public float getCacheMissPercentage() { return (misses / gets) * 100; }
        @Override
        public long getCacheGets() { return gets; }

        @Override
        public long getCachePuts() { return puts; }
        @Override
        public long getCacheRemovals() { return removals; }
        @Override
        public long getCacheEvictions() { return evictions; }

        @Override
        public float getAverageGetTime() { return 0; } // totalGetMicros / gets
        @Override
        public float getAveragePutTime() { return 0; } // totalPutMicros / puts
        @Override
        public float getAverageRemoveTime() { return 0; } // totalRemoveMicros / removals

        public long getCacheExpires() { return expires; }

        /* have callers access fields directly for performance reasons:
        void countHit() {
            gets++; hits++;
            // totalGetMicros += micros;
        }
        void countMiss() {
            gets++; misses++;
            // totalGetMicros += micros;
        }
        void countPut() {
            puts++;
            // totalPutMicros += micros;
        }
        */
        void countRemoval() {
            removals++;
            // totalRemoveMicros += micros;
        }
        void countBulkRemoval(long entries) {
            removals += entries;
        }
        void countExpire() {
            expires++;
        }
    }

    private static class EvictRunnable<K, V> implements Runnable {
        static AccessComparator comparator = new AccessComparator();
        MCache cache;
        int maxEntries;
        EvictRunnable(MCache mc, int entries) { cache = mc; maxEntries = entries; }
        @Override
        public void run() {
            if (maxEntries == 0) return;
            int entriesToEvict = cache.entryStore.size() - maxEntries;
            if (entriesToEvict <= 0) return;

            long startTime = System.currentTimeMillis();

            Collection<MEntry> entrySet = (Collection<MEntry>) cache.entryStore.values();
            PriorityQueue<MEntry> priorityQueue = new PriorityQueue<>(entrySet.size(), comparator);
            priorityQueue.addAll(entrySet);

            int entriesEvicted = 0;
            while (entriesToEvict > 0 && priorityQueue.size() > 0) {
                MEntry curEntry = priorityQueue.poll();
                // if an entry was expired after pulling the initial value set
                if (curEntry.isExpired) continue;
                cache.entryStore.remove(curEntry.getKey());
                cache.stats.evictions++;
                entriesEvicted++;
                entriesToEvict--;
            }
            long timeElapsed = System.currentTimeMillis() - startTime;
            logger.info("Evicted " + entriesEvicted + " entries in " + timeElapsed + "ms from cache " + cache.name);
        }
    }
    private static class AccessComparator implements Comparator<MEntry> {
        @Override
        public int compare(MEntry e1, MEntry e2) {
            if (e1.accessCount == e2.accessCount) {
                if (e1.lastAccessTime == e2.lastAccessTime) return 0;
                else return e1.lastAccessTime > e2.lastAccessTime ? 1 : -1;
            } else {
                return e1.accessCount > e2.accessCount ? 1 : -1;
            }
        }
    }

}
