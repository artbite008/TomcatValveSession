package org.apache.catalina.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.util.logging.Logger;

/**
 * Description:  The session cache is per foundation manger instance.
 *
 * @author jim631@sina.com
 */
public class SessionCache {
	private static final Logger log = Logger.getLogger(SessionCache.class.getName());


	protected ConcurrentHashMap<String, SessionSerializationData> cache = new ConcurrentHashMap<>();
	private static final long MAX_IDLE_TIME = 15 * 60 * 1000; //15 * 60 * 1000;        //15 min.
	private static final long SLEEP_TIME = 5 * 60 * 1000;     //5 min. 5 * 60 * 1000
	private Boolean cleanUpThreadStarted = Boolean.FALSE;

	public void put(String key, SessionSerializationData data) {
		cache.put(key, data);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the previous value associated with the specified key,
	 * or <tt>null</tt> if there was no mapping for the key
	 * @throws NullPointerException if the specified key or value is null
	 */
	public SessionSerializationData putIfAbsent(String key, SessionSerializationData data) {
		return cache.putIfAbsent(key, data);
	}

	public SessionSerializationData get(String key) {
		SessionSerializationData ret = cache.get(key);
//even TTL has passed, before the clean up thread got a chance to evict it,  it does not hurt to hand it out.
//which means let the SSD live in the cache a little longer.
//		if (ret != null && isTTLReached(ret)) {
//			cache.remove(key);
//			return null;
//		}
		return ret;
	}

	public void remove(String id) {
		cache.remove(id);
	}

	public int getSize() {
		return cache.size();
	}

	public synchronized void startCleanUpJob() {
		if (!cleanUpThreadStarted.booleanValue()) {
			Thread t = new Thread(new CleanupExpiredSessionsJob(this));
			t.setDaemon(true);  //this is a deamon thread.
			t.start();
			cleanUpThreadStarted = Boolean.TRUE;
			log.info("started the cleanupExpiredSessions thread, cache instance@" + System.identityHashCode(cache));
		}
	}

	/**
	 * Has the TTL reached.
	 *
	 * @param ssd
	 * @return
	 */
	private static boolean isTTLReached(SessionSerializationData ssd) {
		//Timestamp cur = new Timestamp(System.currentTimeMillis());
		ssd.lock();
		try {
// not checking the session expiration here in the cache. let the Manager handle it.
//			if (ssd.getHeader() != null) {
//				if (ssd.getHeader().expiration_time != null && ssd.getHeader().expiration_time.before(cur))
//					return true;
//			}

			//evict if the last access time is 15 min ago.
			if (ssd.getLastAccessTime() > 0
					&& (System.currentTimeMillis() - ssd.getLastAccessTime()) > MAX_IDLE_TIME
					) {
				return true;
			}
			return false;
		} finally {
			ssd.unlock();
		}
	}

	public static class CleanupExpiredSessionsJob implements Runnable {
		private SessionCache sessionCache;

		public CleanupExpiredSessionsJob(SessionCache sessionCache) {
			this.sessionCache = sessionCache;
		}

		@Override
		public void run() {
			while (true) {
				try {
					//return the thread name executing this callable task
					List<String> toRemove = new ArrayList<String>();
					for (Map.Entry<String, SessionSerializationData> entry : sessionCache.cache.entrySet()) {
						if (isTTLReached(entry.getValue())) {
							toRemove.add(entry.getKey());
						}
					}
					sessionCache.log.info("CleanupExpiredSessionsJob runs for SessionCache@" + System.identityHashCode(sessionCache.cache));
					sessionCache.log.info("\tcache size=" + sessionCache.cache.size());
					sessionCache.log.info("\texpired sessions data=" + toRemove.size());
					for (String key : toRemove) {
						SessionSerializationData ssd = sessionCache.cache.get(key);
						if (ssd != null) {
							ssd.lock(); //the job can wait for the lock.
							try {
								sessionCache.cache.remove(key);
								sessionCache.log.info("Evicted SSD from cache, id=" + key);
							} finally {
								ssd.unlock();
							}
						}
					}

					Thread.sleep(SLEEP_TIME);
				} catch (InterruptedException e) {
					throw new RuntimeException("Cache cleaning up thread got interrupted", e);
				}
			}

		}
	}

}
