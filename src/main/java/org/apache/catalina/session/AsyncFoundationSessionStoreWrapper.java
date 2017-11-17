package org.apache.catalina.session;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.io.IOException;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.session.SessionSerializationData.CouldNotObtainLockException;

import java.util.logging.Logger; 

/**
 * Foundation {@link org.apache.catalina.Session} {@link org.apache.catalina.Store} wrapper that defers session persistence into
 * an asynchronous work queue.
 * <p/>
 * Also provides JMX MBean registration to allow for monitoring the queue and persistence metrics.
 *
 * @author jim631@sina.com
 */
public class AsyncFoundationSessionStoreWrapper extends BaseFoundationSessionStore implements RejectedExecutionHandler {

	private static final Logger log = Logger.getLogger(AsyncFoundationSessionStoreWrapper.class.getName());
	
	private final BaseFoundationSessionStore target;

	private FoundationSessionStoreMonitoring mBean;

	// async queues/executor
	protected ThreadPoolExecutor persistenceExecutor;
	protected LinkedBlockingQueue<Runnable> persistenceExecutorQueue;

	// lock to prevent concurrent thread pool size adjustments
	private final ReentrantLock executorThreadPoolAdjusterLock = new ReentrantLock();

	private final ThreadFactory threadFactory = new ThreadFactoryInternal();

	public FoundationSessionStoreMonitoring getFoundationSessionStoreMonitoring() {
		return mBean;
	}


    public AsyncFoundationSessionStoreWrapper(final BaseFoundationSessionStore target) {
		super();
		this.target = target;
		this.mBean = new FoundationSessionStoreMonitoring(this);

		// set configuration from system properties if applicable
		mBean.corePoolSize = getSystemPropertyIntValue("corePoolSize", mBean.corePoolSize);
		mBean.maxPoolSize = getSystemPropertyIntValue("maxPoolSize", mBean.maxPoolSize);
		mBean.executorThreadPoolThreadTTLMs = getSystemPropertyLongValue("executorThreadPoolThreadTTLMs", mBean.executorThreadPoolThreadTTLMs);
		mBean.executorQueueMaxCapacity = getSystemPropertyIntValue("executorQueueMaxCapacity", mBean.executorQueueMaxCapacity);
		final int[] percentages = getSystemPropertyIntArrayValue("executorThreadPoolQueuePercentageSizeThresholds",
				mBean.executorThreadPoolQueuePercentageSizeThresholds);
		setExecutorThreadPoolQueuePercentageSizeThresholds(percentages);
	}

	@Override
	protected void sendChangedSessionDataToPersist(final StandardSession session, final SessionSerializationData ssd) throws IOException {
		ssd.lock();
		try {
			mBean.totalSessionsMutated.incrementAndGet();
			ssd.setPersistenceQueueState(PersistenceQueueState.NONE);
		} finally {
			ssd.unlock();
		}
		queueDirtySession(session, ssd);
	}

	@Override
	protected void persistSession(final SessionSerializationData ssd) throws IOException {
		final long start = System.currentTimeMillis();

		target.persistSession(ssd);

		mBean.sessionSavedCounter.incrementAndGet();
		final long end = System.currentTimeMillis();
		final long duration = end - start;
		mBean.sessionSaveTotalTime.addAndGet(duration);
		mBean.sessionSaveLastTime = duration;


		if (mBean.sessionSaveMaxTime < duration) {
			log.info("Session " + ssd.getId() + " persisted in " + Long.valueOf(duration)
					+ "ms exceeded the previous maximum session save time of " + Long.valueOf(mBean.sessionSaveMaxTime) + "ms");
			mBean.sessionSaveMaxTime = duration;
		} else {
			log.info("Session " + ssd.getId() + " persisted in " + Long.valueOf(duration) + "ms");
		}
	}

	@Override
	protected synchronized void startInternal() throws LifecycleException {
		super.startInternal();
		createExecutor();
		mBean.resetCounters();
	}

	protected void createExecutor() {
		persistenceExecutorQueue = new LinkedBlockingQueue<>(mBean.executorQueueMaxCapacity);
		persistenceExecutor = new ThreadPoolExecutor(mBean.corePoolSize, mBean.maxPoolSize, mBean.executorThreadPoolThreadTTLMs, TimeUnit.MILLISECONDS, persistenceExecutorQueue,
				threadFactory, this);
	}

	@Override
	protected synchronized void stopInternal() throws LifecycleException {
		super.stopInternal();
		final ExecutorService executor = persistenceExecutor;
		persistenceExecutor = null;
		persistenceExecutorQueue = null;
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	protected void queueDirtySession(final StandardSession session, final SessionSerializationData ssd) throws IOException {
		// assume we will end up queuing the session, then flag it false when we see a reason it shouldn't be
		boolean queueSession = true;
		final String sessionID = session.getIdInternal();

		//ssd wait lock time
		if (mBean.processChangesMaxWaitLockTime < ssd.getWaitLockTime())
			mBean.processChangesMaxWaitLockTime = ssd.getWaitLockTime();

		// if the session is already queued, we need not queue it again
		if (PersistenceQueueState.QUEUED.equals(ssd.getPersistenceQueueState())) {
			queueSession = false;
			log.info("Skipping queuing of already-queued dirty session " + sessionID);
		} else {
			// the session is not queued, but therefore could be PERSISTING, after which it will end up as CLEAN again,
			// so we need to try and lock it, check again to see if it has been re-QUEUED, and if not, flag it as QUEUED
			// (note that we should already have the lock under normal circumstances anyway)
			try {
				ssd.tryLock(100, TimeUnit.MILLISECONDS);
				try {
					if (PersistenceQueueState.QUEUED.equals(ssd.getPersistenceQueueState())) {
						// some other thread beat us to it, so no need to re-queue it
						queueSession = false;
						log.info("Skipping queuing of already-queued dirty session " + sessionID);
					} else {
						// time to throw it in the QUEUED state
						ssd.setPersistenceQueueState(PersistenceQueueState.QUEUED);
						log.info("Queuing dirty session " + sessionID);

					}
				} finally {
					ssd.unlock();
				}
			} catch (final CouldNotObtainLockException e) {
				// it is in the middle of persisting or being mutated, so skip this operation for now; likely we'll come back and process the session later
				queueSession = false;
				log.info("Could not obtain lock for session " + sessionID + " while trying to queue for persistence");
			} catch (final InterruptedException e) {
				// this is not expected, so let's log it and move on
				log.severe("Unexpected InterruptedException during queueDirtySession for session " + sessionID);
				queueSession = false;
			}
		}
		if (queueSession) {
			// we've determined that this is a session we want to save, so queue or save it as needed
			final ExecutorService executor = persistenceExecutor;
			if (executor != null && !executor.isTerminated()) {
				// we're good to queue this session in the ExecutorService's queue for later processing
				final SessionPersistenceRunnable persistor = new SessionPersistenceRunnable(ssd);
				mBean.queueSize = queueAsynchronousSave(persistor, mBean.totalSessionsQueued);
				if (mBean.queueSize > mBean.queueSizeMax)
					mBean.queueSizeMax = mBean.queueSize;				
					log.info("Queued async persistence event for session " + sessionID + ", queueSize/queueSizeMax == " + mBean.queueSize + "/" + mBean.queueSizeMax);
			} else {
				// no executor available, so save immediately
				log.info("Performing save of dirty session synchronously due to no executor configured for " + sessionID);
				flush(ssd);
			}
		}
	}

	private int queueAsynchronousSave(final Runnable runnable, final AtomicInteger counter) {
		final ThreadPoolExecutor executor = persistenceExecutor;
		if (executor == null || executor.isTerminated())
			throw new IllegalStateException("Executor service is null or terminated - cannot queue event");
		executor.execute(runnable);
		counter.incrementAndGet();
		final int queueSize = persistenceExecutorQueue != null ? persistenceExecutorQueue.size() : 0;
		if (queueSize > mBean.executorQueueMaxSize)
			mBean.executorQueueMaxSize = queueSize;
		adjustExecutorThreads(executor, queueSize);
		return queueSize;
	}

	private void resetExecutorThreadPoolQueueSizeThresholds() {
		// recalculate the executorThreadPoolQueueSizeThresholds array
		final int queueMaxSize = mBean.executorQueueMaxCapacity;
		final int[] thresholds = new int[mBean.executorThreadPoolQueuePercentageSizeThresholds.length];
		for (int i = 0; i < thresholds.length; i++) {
			thresholds[i] = queueMaxSize * mBean.executorThreadPoolQueuePercentageSizeThresholds[i] / 100;
		}

		// force the last value to always be max no matter what was configured (just to be safe)
		thresholds[thresholds.length - 1] = Integer.MAX_VALUE;

		// take it live
		mBean.executorThreadPoolQueueSizeThresholds = thresholds;
	}

	private void adjustExecutorThreads(final ThreadPoolExecutor executor, final int queueSize) {
		// determine our target thread count based on the queueSize relative to executorThreadPoolQueueSizeThresholds
		int targetThreadCount = 0;
		for (final int threshold : mBean.executorThreadPoolQueueSizeThresholds) {
			targetThreadCount++;
			if (queueSize <= threshold) {
				break;
			}
		}

		// make sure that we don't exceed the max pool size (possible if someone misconfigures the executorThreadPoolQueueSizeThresholds
		targetThreadCount = Math.min(targetThreadCount, mBean.maxPoolSize);

		// are we changing the threadCount?
		int currentThreadCount = executor.getCorePoolSize();
		if (currentThreadCount != targetThreadCount) {
			// let only 1 thread do this at a time...
			if (executorThreadPoolAdjusterLock.tryLock()) {
				try {
					// check again to see if the target is different, and change only if it is
					currentThreadCount = executor.getCorePoolSize();
					if (targetThreadCount < currentThreadCount) {
						// we are heading downwards (need to decrease the thread count)
						// here we want to decrease the thread count only if we're down 2 threshold levels from where we were
						// in order to avoid churn if the queue level is fluctuating around a threshold
						if (targetThreadCount < 2) {
							// don't let us go below 1 thread ever
							targetThreadCount = 1;
						} else {
							// target up one more level so that we only drop if we were 2 levels or more down
							targetThreadCount++;
						}
					}
					if (currentThreadCount != targetThreadCount) {
						executor.setCorePoolSize(targetThreadCount);
					}
				} finally {
					executorThreadPoolAdjusterLock.unlock();
				}
			}
		}
	}

	@Override
	public void setManager(final Manager manager) {
		super.setManager(manager);
		target.setManager(manager);
	}

	@Override
	public boolean isStoreAvailable() {
		final boolean result = target.isStoreAvailable();
		return result;
	}

	@Override
	public StandardSession load(final String id) throws ClassNotFoundException, IOException {
		final StandardSession result = target.load(id);
		return result;
	}

	@Override
	public Set<String> getExpiredSessionKeys() throws IOException {
		final Set<String> result = target.getExpiredSessionKeys();
		return result;
	}

	@Override
	public int getSize() throws IOException {
		final int result = target.getSize();
		return result;
	}

	@Override
	public String[] keys() throws IOException {
		final String[] result = target.keys();
		return result;
	}

	@Override
	public void remove(final String id) throws IOException {
		target.remove(id);
		sessionCache().remove(id);
		log.info("remove(): Removed the session from Session Cache: " + id);
	}

	@Override
	public void clear() throws IOException {
		target.clear();
	}

	@Override
	public void rejectedExecution(final Runnable r, final ThreadPoolExecutor executor) {
		if (r instanceof SessionPersistenceRunnable) {
			final SessionPersistenceRunnable spr = (SessionPersistenceRunnable) r;
			final String sessionID = spr.getSessionId();

			// reset the status back to not QUEUED
			//final StandardSession session = spr.getSession();
			//final SessionSerializationData ssd = getSessionSerializationDataForSession(session);
			final SessionSerializationData ssd = spr.getSessionSerializationData();
			try {
				ssd.tryLock(100, TimeUnit.MILLISECONDS);
				try {
					ssd.setPersistenceQueueState(null);
				} finally {
					ssd.unlock();
				}
			} catch (final CouldNotObtainLockException e) {
				// session is being handled by some other thread, so let it go
			} catch (final InterruptedException e) {
				// this is not expected, so let's log it and move on
				log.severe("Unexpected InterruptedException while trying to restore session state to null for session " + sessionID);
			}

			// if we're here it is because we were unable to queue a job - the executor queue is full
			// forget the job/skip it, consider it a casualty of scalability; the session will likely be hit again and saved later
			log.severe("Could not queue asynchronous save event - queue was full; session data not persisted for session " + sessionID);
		} else {
			//log.severe("Unexpected runnable type rejected from queue: " + r.getClass());
			log.severe("Unexpected runnable type rejected from queue: ");
		}
	}

	protected static class ThreadFactoryInternal implements ThreadFactory {
		private final AtomicInteger workerIDGenerator = new AtomicInteger();

		@Override
		public Thread newThread(final Runnable r) {
			final Thread result = new Thread(r);
			result.setDaemon(true);
			result.setName("FoundationSessionStore-worker-" + workerIDGenerator.incrementAndGet());
			return result;
		}

	}

	private class SessionPersistenceRunnable implements Runnable {
		private final SessionSerializationData ssd;
		private final long timeQueued;

		public SessionPersistenceRunnable(final SessionSerializationData ssd) {
			super();
			this.ssd = ssd;
			this.timeQueued = System.currentTimeMillis();
		}

		@Override
		public void run() {
			recordQueueTime(timeQueued);

			final String sessionID = ssd.getId();
			try {
				log.info("Starting asynchronous flush of session " + sessionID);
				flush(ssd);
			} catch (final IOException e) {
				log.severe("Could not complete asynchronous session persist for sessionID " + sessionID);
			} finally {
				if ( persistenceExecutorQueue != null)
					log.info("Async persistence event for session " + sessionID + " completed, queue.size() == " + persistenceExecutorQueue.size());
			}
		}

		public String getSessionId() {
			final String result = ssd.getId();
			return result;
		}

		public SessionSerializationData getSessionSerializationData() {
			return ssd;
		}
	}

	protected void recordQueueTime(final long timeQueued) {
		final long now = System.currentTimeMillis();
		final long timeInQueue = now - timeQueued;
		mBean.sessionTimeInQueueTotal.addAndGet(timeInQueue);
		mBean.sessionTimeInQueueLast = timeInQueue;
		if (mBean.sessionTimeInQueueMax < timeInQueue)
			mBean.sessionTimeInQueueMax = timeInQueue;
	}

	private void setExecutorThreadPoolQueuePercentageSizeThresholds(final int[] executorThreadPoolQueuePercentageSizeThresholds) {
		if (executorThreadPoolQueuePercentageSizeThresholds == null)
			throw new IllegalArgumentException("Cannot set executorThreadPoolQueuePercentageSizeThresholds to null");
		final int size = executorThreadPoolQueuePercentageSizeThresholds.length;
		if (size != mBean.maxPoolSize)
			throw new IllegalArgumentException("executorThreadPoolQueuePercentageSizeThresholds length must == maxPoolSize");
		final int[] copy = new int[size];
		System.arraycopy(executorThreadPoolQueuePercentageSizeThresholds, 0, copy, 0, size);
		mBean.executorThreadPoolQueuePercentageSizeThresholds = copy;
		resetExecutorThreadPoolQueueSizeThresholds();
	}

	public void setExecutorCorePoolSize(final int corePoolSize) {
		if (persistenceExecutor != null)
			persistenceExecutor.setCorePoolSize(corePoolSize);
	}


	public void setExecutorMaxPoolSize(final int maxPoolSize) {
		if (persistenceExecutor != null)
			persistenceExecutor.setMaximumPoolSize(maxPoolSize);
		resetExecutorThreadPoolQueueSizeThresholds();
	}

	public void setExecutorThreadPoolThreadTTLMs(final long executorThreadPoolThreadTTLMs) {
		if (persistenceExecutor != null)
			persistenceExecutor.setKeepAliveTime(executorThreadPoolThreadTTLMs, TimeUnit.MILLISECONDS);
	}

	public int getExecutorThreadPoolWorkerThreads() {
		int result = 0;
		if (persistenceExecutor != null) {
			result = persistenceExecutor.getPoolSize();
		}
		return result;
	}

	public int getExecutorThreadPoolActiveThreads() {
		int result = 0;
		if (persistenceExecutor != null) {
			result = persistenceExecutor.getActiveCount();
		}
		return result;
	}


	public int getExecutorQueueSize() {
		final int result = persistenceExecutorQueue == null ? 0 : persistenceExecutorQueue.size();
		return result;
	}

	public int getCacheSize() {
		return sessionCache().getSize();
	}

	public void setExecutorThreadPoolQueuePercentageSizeThresholds(final String commaDelimitedList) {
		final int[] values = FoundationUtil.getIntArrayFromCommaDelimitedString(commaDelimitedList);
		setExecutorThreadPoolQueuePercentageSizeThresholds(values);
	}


	public void resetExecutor() {
		final ThreadPoolExecutor executor = persistenceExecutor;
		if (executor != null) {
			// attempt to prune off non-core threads
			executor.setMaximumPoolSize(mBean.corePoolSize);
			executor.setMaximumPoolSize(mBean.maxPoolSize);
		}

	}


}
