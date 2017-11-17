package org.apache.catalina.session;

/**
 * Description:   FoundationSessionStore  Monitoring Mbean
 *
 * @author jim631@sina.com
 */
public interface FoundationSessionStoreMonitoringMBean {

	// simple session counters

	public int getTotalSessionsSaved() ;

	public int getTotalSessionsSavedPerSecond() ;

	public int getTotalSessionQueueEvents() ;

	public int getTotalSessionQueueEventsPerSecond() ;

	public int getTotalSessionsMutated() ;

	public int getTotalSessionsMutatedPerSecond() ;

	public long getSessionSaveAverageTime() ;

	public long getSessionSaveLastTime();

	public long getProcessChangesMaxWaitLockTime();

	public long getSessionSaveMaxTime();

	public long getSessionSaveTotalTime() ;

	// executor queue metrics and config

	public int getExecutorQueueMaxSize();

	public long getSessionTimeInQueueMaxTime();

	public long getSessionTimeInQueueLastTime();

	public long getSessionTimeInQueueTotalTime() ;

	public long getSessionTimeInQueueAverageTime() ;

	public int getExecutorCorePoolSize();

	public void setExecutorCorePoolSize(final int corePoolSize) ;

	public int getExecutorMaxPoolSize();

	public void setExecutorMaxPoolSize(final int maxPoolSize) ;

	public long getExecutorThreadPoolThreadTTLMs();

	public void setExecutorThreadPoolThreadTTLMs(final long executorThreadPoolThreadTTLMs) ;

	public int getExecutorThreadPoolWorkerThreads() ;

	public int getExecutorThreadPoolActiveThreads() ;

	public int getExecutorQueueMaxCapacity();

	public String getExecutorThreadPoolQueuePercentageSizeThresholds() ;

	public void setExecutorThreadPoolQueuePercentageSizeThresholds(final String commaDelimitedList) ;


	public int getExecutorQueueSize() ;

	public int getCacheSize() ;

	// JMX utility methods

	public void resetCounters() ;

}
