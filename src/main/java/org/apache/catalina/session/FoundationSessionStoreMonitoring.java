package org.apache.catalina.session;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Description:   FoundationSessionStore config and Monitoring Mbean impl.
 *
 * @author jim631@sina.com
 */
public class FoundationSessionStoreMonitoring implements FoundationSessionStoreMonitoringMBean {

	// initial configuration parameters for the executor and queues
	protected int corePoolSize = 3;
	protected int maxPoolSize = 10;
	protected long executorThreadPoolThreadTTLMs = 10*1000; //unit MILLISECONDS so this is 10 seconds.
	protected int executorQueueMaxCapacity = 5000;
	// thresholds at which the core thread count for the executor goes up a thread)
	protected int[] executorThreadPoolQueuePercentageSizeThresholds = new int[] { 10, 20, 30, 30, 50, 50, 60, 70, 80, 100 };
	protected int[] executorThreadPoolQueueSizeThresholds = new int[] { 300, 600, 700, 800, Integer.MAX_VALUE };



	// tracking of session activity events
	protected  final AtomicInteger totalSessionsMutated = new AtomicInteger();
	protected  final AtomicInteger totalSessionsQueued = new AtomicInteger();
	protected  long trackingStartTime;

	// statistical tracking for executor queue
	protected  volatile int executorQueueMaxSize;
	protected  volatile long sessionTimeInQueueMax;
	protected  volatile long sessionTimeInQueueLast;
	protected  final AtomicLong sessionTimeInQueueTotal = new AtomicLong();

	// statistical tracking for session persistence
	protected  final AtomicInteger sessionSavedCounter = new AtomicInteger();
	protected  volatile long sessionSaveMaxTime;
	protected  final AtomicLong sessionSaveTotalTime = new AtomicLong();
	protected  volatile long sessionSaveLastTime;
	protected  volatile long processChangesMaxWaitLockTime=0;

	protected  volatile long queueSize;
	protected  volatile long queueSizeMax;
	protected  volatile int cacheSize;

	private AsyncFoundationSessionStoreWrapper store;

	public FoundationSessionStoreMonitoring(AsyncFoundationSessionStoreWrapper store) {
		this.store= store;
		//
	}

	/* EXPOSED FOR MBEAN/JMX */

	// simple session counters

	public int getTotalSessionsSaved() {
		final int result = sessionSavedCounter.get();
		return result;
	}

	public int getTotalSessionsSavedPerSecond() {
		final int result;
		final int seconds = (int) ((System.currentTimeMillis() - trackingStartTime) / 1000);
		if (seconds == 0) {
			result = 0;
		} else {
			final int count = sessionSavedCounter.get();
			result = count / seconds;
		}
		return result;
	}

	public int getTotalSessionQueueEvents() {
		final int result = totalSessionsQueued.get();
		return result;
	}

	public int getTotalSessionQueueEventsPerSecond() {
		final int result;
		final int seconds = (int) ((System.currentTimeMillis() - trackingStartTime) / 1000);
		if (seconds == 0) {
			result = 0;
		} else {
			final int count = totalSessionsQueued.get();
			result = count / seconds;
		}
		return result;
	}

	public int getTotalSessionsMutated() {
		final int result = totalSessionsMutated.get();
		return result;
	}

	public int getTotalSessionsMutatedPerSecond() {
		final int result;
		final int seconds = (int) ((System.currentTimeMillis() - trackingStartTime) / 1000);
		if (seconds == 0) {
			result = 0;
		} else {
			final int count = totalSessionsMutated.get();
			result = count / seconds;
		}
		return result;
	}

	public long getSessionSaveAverageTime() {
		final long result;
		final int totalSavedSessions = getTotalSessionsSaved();
		if (totalSavedSessions == 0) {
			result = 0;
		} else {
			final long totalSaveTime = getSessionSaveTotalTime();
			result = totalSaveTime / totalSavedSessions;
		}
		return result;
	}

	public long getSessionSaveLastTime() {
		return sessionSaveLastTime;
	}

	public long getProcessChangesMaxWaitLockTime() {
		return processChangesMaxWaitLockTime;
	}

	public long getSessionSaveMaxTime() {
		return sessionSaveMaxTime;
	}

	public long getSessionSaveTotalTime() {
		final long result = sessionSaveTotalTime.get();
		return result;
	}

	// executor queue metrics and config

	public int getExecutorQueueMaxSize() {
		return executorQueueMaxSize;
	}

	public long getSessionTimeInQueueMaxTime() {
		return sessionTimeInQueueMax;
	}

	public long getSessionTimeInQueueLastTime() {
		return sessionTimeInQueueLast;
	}

	public long getSessionTimeInQueueTotalTime() {
		final long result = sessionTimeInQueueTotal.get();
		return result;
	}

	public long getSessionTimeInQueueAverageTime() {
		final long result;
		final int totalQueuedSessions = getTotalSessionQueueEvents();
		if (totalQueuedSessions == 0) {
			result = 0;
		} else {
			final long totalQueueWaitTime = getSessionTimeInQueueTotalTime();
			result = totalQueueWaitTime / totalQueuedSessions;
		}
		return result;
	}

	public int getExecutorCorePoolSize() {
		return corePoolSize;
	}

	public void setExecutorCorePoolSize(final int corePoolSize) {
		this.corePoolSize = corePoolSize;
		store.setExecutorCorePoolSize(corePoolSize);
	}

	public int getExecutorMaxPoolSize() {
		return maxPoolSize;
	}

	public void setExecutorMaxPoolSize(final int maxPoolSize) {
		this.maxPoolSize = maxPoolSize;
		store.setExecutorMaxPoolSize(maxPoolSize);
	}

	public long getExecutorThreadPoolThreadTTLMs() {
		return executorThreadPoolThreadTTLMs;
	}

	public void setExecutorThreadPoolThreadTTLMs(final long executorThreadPoolThreadTTLMs) {
		this.executorThreadPoolThreadTTLMs = executorThreadPoolThreadTTLMs;
		store.setExecutorThreadPoolThreadTTLMs(executorThreadPoolThreadTTLMs);
	}

	public int getExecutorThreadPoolWorkerThreads() {
		return store.getExecutorThreadPoolWorkerThreads();
	}

	public int getExecutorThreadPoolActiveThreads() {
		return store.getExecutorThreadPoolActiveThreads();
	}

	public int getExecutorQueueMaxCapacity() {
		return executorQueueMaxCapacity;
	}

	public String getExecutorThreadPoolQueuePercentageSizeThresholds() {
		final String result = FoundationUtil.buildCommaDelimitedString(executorThreadPoolQueuePercentageSizeThresholds);
		return result;
	}

	public void setExecutorThreadPoolQueuePercentageSizeThresholds(final String commaDelimitedList) {
		store.setExecutorThreadPoolQueuePercentageSizeThresholds(commaDelimitedList);
	}



	public int getExecutorQueueSize() {
		return store.getExecutorQueueSize();
	}

	public int getCacheSize() {
		return store.getCacheSize();
	}



	// JMX utility methods

	public void resetCounters() {
		totalSessionsMutated.set(0);
		totalSessionsQueued.set(0);

		executorQueueMaxSize = 0;
		sessionTimeInQueueMax = 0;
		sessionTimeInQueueLast = 0;
		sessionTimeInQueueTotal.set(0);

		sessionSavedCounter.set(0);
		sessionSaveMaxTime = 0;
		sessionSaveTotalTime.set(0);
		sessionSaveLastTime = 0;
		processChangesMaxWaitLockTime = 0;

		trackingStartTime = System.currentTimeMillis();

		store.resetExecutor();
			}
}
