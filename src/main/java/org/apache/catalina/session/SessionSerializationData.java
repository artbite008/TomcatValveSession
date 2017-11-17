package org.apache.catalina.session;

import org.apache.catalina.connector.Request;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Container of session serialization data, plus manager of a lock to manage concurrency between
 * reading and writing the data.
 *
 * @author jim631@sina.com
 */
class SessionSerializationData implements ManualSerializable, Serializable {

	private static Logger log = Logger.getLogger(SessionSerializationData.class.getName());
 	
	protected static final String NOT_SERIALIZED = "___NOT_SERIALIZABLE_EXCEPTION___";

	private final static boolean logLockinfoAtInfo = Boolean.getBoolean("integrationServerMode") || Boolean.getBoolean("plateau.developmentmode");

	private static class OwnerExposingReentrantLock extends ReentrantLock {

		private static final long serialVersionUID = 1L;

		@Override
		public Thread getOwner() {
			return super.getOwner();
		}

	}

	public Logger getLogger() {
		 return this.log;
	}

	private int attributes_count;
	private int attributes_size;
	private  String id;
	private  transient OwnerExposingReentrantLock lock;
	private Long lockAcquisitionTime;


	private SessionSerializationHeaderData header;
	private boolean headerChanged;
	private final Map<String, SessionAttributeRecord> attributes;
	private final Map<String, SessionAttributeRecord> modifiedSessionAttributeRecords;
	private final Set<String> removedSessionAttributes;
	private PersistenceQueueState persistenceQueueState;

	private long waitLockTime;
	private long lastAccessTime; //determine the TTL in cache only.

	public SessionSerializationData(Logger logger, String id) {
		super();
		this.id= id;
		this.log= logger;
		this.lock = new OwnerExposingReentrantLock();
		this.attributes = new HashMap<String, SessionAttributeRecord>();
		this.modifiedSessionAttributeRecords = new HashMap<String, SessionAttributeRecord>();
		this.removedSessionAttributes = new HashSet<String>();
	}

	public SessionSerializationData(Logger logger, String id, final Map<String, SessionAttributeRecord> attributes) {
		this(logger, id);
		this.attributes.putAll(attributes);
	}


	/**
	 * Attempt to obtain a lock for this SessionSerializationData, timing out after the specified timeout.
	 *
	 * @param time time to wait for timeout
	 * @param timeUnit unit of time to wait for timeout
	 * @throws InterruptedException if the thread is interrupted while waiting for the lock
	 * @throws CouldNotObtainLockException if the lock attempt timed out
	 */
	public void tryLock(final long time, final TimeUnit timeUnit) throws InterruptedException, CouldNotObtainLockException {
		if (logLockinfoAtInfo)
			getLogger().info("SessionSerializationData.tryLock(" + time + ", " + timeUnit + ")");
		else
			getLogger().info("SessionSerializationData.tryLock(" + time + ", " + timeUnit + ")");
		long  b = System.currentTimeMillis();
		final boolean lockObtained = lock.tryLock(time, timeUnit);
		if (!lockObtained) {
			if (logLockinfoAtInfo)
				getLogger().info("SessionSerializationData- Lock not obtained (timeout!)");
			else
				getLogger().info("SessionSerializationData-Lock not obtained (timeout!)");
			throw new CouldNotObtainLockException("Lock attempt timed out after " + time + " " + timeUnit + "(s)");
		}
		if (lockAcquisitionTime == null)
			lockAcquisitionTime = Long.valueOf(System.currentTimeMillis());

		this.waitLockTime = lockAcquisitionTime.longValue()-b;
		if (logLockinfoAtInfo)
			getLogger().info("SessionSerializationData- waitLockTIme=" + Long.valueOf(this.waitLockTime));
		else
			getLogger().info("SessionSerializationData- waitLockTIme=" + Long.valueOf(this.waitLockTime));

	}

	public void lock() {
		lock.lock();
		lockAcquisitionTime = Long.valueOf(System.currentTimeMillis());
	}


	public long getWaitLockTime() {
		return waitLockTime;
	}

	/**
	 * If we know we're dirty, attempt a lock, and then check again after the lock is obtained.  If the state changed,
	 * unlock and return false.
	 *
	 * If we're clean, the result will be false and a lock will not be held.
	 *
	 * @param time time to wait for timeout
	 * @param timeUnit unit of time to wait for timeout
	 * @return true if a lock is held and we're known to be dirty, false if we're clean and no lock is held
	 * @throws InterruptedException if the thread is interrupted while waiting for the lock
	 * @throws CouldNotObtainLockException if the lock attempt timed out
	 */
	public boolean tryLockIfDirty(final long time, final TimeUnit timeUnit) throws InterruptedException, CouldNotObtainLockException {
		boolean result = isDirty();
		if (result) {
			tryLock(time, timeUnit);
			result = isDirty();
			if (!result)
				unlock();
		}
		return result;
	}

	public void unlock() {
		final String logMsg;
		if (lockAcquisitionTime == null) {
			logMsg = "SessionSerializationData.unlock() - lock hold time: ? ";
		} else {
			final long elapsedTime = System.currentTimeMillis() - lockAcquisitionTime.longValue();
			logMsg = "SessionSerializationData.unlock() - lock hold time: " + elapsedTime + "ms";
		}
		if (logLockinfoAtInfo)
			getLogger().info(logMsg);
		else
			getLogger().info(logMsg);
		lock.unlock();
		if (lockAcquisitionTime != null && !lock.isLocked()) {
			lockAcquisitionTime = null;
		}
	}

	public Thread getLockOwner() {
		final Thread result = lock.getOwner();
		return result;
	}

	public boolean isDirty() {
		final boolean headerDirty = hasHeaderChanged();
		final boolean attrsChanged = haveAttributesChanged();
		final boolean result = headerDirty || attrsChanged;
		return result;
	}

	public boolean hasHeaderChanged() {
		return headerChanged;
	}

	public boolean haveAttributesChanged() {
		final boolean attrsDirty = !modifiedSessionAttributeRecords.isEmpty();
		final boolean attrsRemoved = !removedSessionAttributes.isEmpty();
		final boolean result = attrsDirty || attrsRemoved;
		return result;
	}

	private void assertThreadHasLock() throws IllegalStateException {
		if (!lock.isHeldByCurrentThread())
			throw new IllegalStateException("Cannot perform this operation without a lock");
	}

	public void captureSessionData(final StandardSession currentSession, final Request request, final String nodeID, final String webApp) {
		assertThreadHasLock();
		setHeaderData(currentSession, request, nodeID, webApp);
		takeSnapshotAndCalculateDelta(currentSession);
	}

	public void clear() {
		assertThreadHasLock();
		// clear out the data usde in persistence, but keep the attributes list so that we know the baseline against which to compare future saves
		header = null;
		headerChanged = false;
		attributes.clear();
		modifiedSessionAttributeRecords.clear();
		removedSessionAttributes.clear();
		persistenceQueueState = null;
		waitLockTime=0;
		lastAccessTime=0;
	}

	public PersistenceQueueState getPersistenceQueueState() {
		return persistenceQueueState;
	}

	public void setPersistenceQueueState(final PersistenceQueueState persistenceQueueState) {
		// allow this only if the current thread has the lock, and the state is going through an allowed transition
		assertThreadHasLock();
		if (this.persistenceQueueState != null && !this.persistenceQueueState.isValidTransition(persistenceQueueState))
			throw new IllegalArgumentException("Cannot transition from " + this.persistenceQueueState + " to " + persistenceQueueState);
		this.persistenceQueueState = persistenceQueueState;
	}

	public SessionSerializationHeaderData getHeader() {
		assertThreadHasLock();
		return header;
	}

	protected void putAttributeRecord(final String key, final int update_count, final int data_length, final String data_checksum
			, final String data_type
			, final byte[] data) {
		assertThreadHasLock();
		// no need for the data here - this is just a record of what the attr looked like @ the time
		//tony changed. to have data at this point. it is needed for the clone.
		final SessionAttributeRecord sar = new SessionAttributeRecord(key, update_count, data_length, data_checksum, data_type, data);
		attributes.put(key, sar);
	}

	protected void putAttributeRecord(final SessionAttributeRecord newSar) {
		assertThreadHasLock();
		putAttributeRecord(newSar.key, newSar.update_count, newSar.data_length, newSar.data_checksum, newSar.data_type, newSar.data);
	}


	/*
	[tony]
	1. sync up the attributes with the current session. Attributes was the last snapshot to start with.
		afterward, it will be synced up with the session attributes.
 	2. gather modifiedSessionAttributeRecords.
	3. gather removedSessionAttributes

	 */
	protected void takeSnapshotAndCalculateDelta(final StandardSession currentSession) {
		assertThreadHasLock();
		final String session_id = currentSession.getIdInternal();
		attributes_count = 0;
		attributes_size = 0;

		//clear them first. Why wasn't this done before??
		this.removedSessionAttributes.clear();
		this.modifiedSessionAttributeRecords.clear();

		for (final Map.Entry<String, Object> entry : ReflectionUtils.getSessionAttributes(currentSession).entrySet()) {
			final String key = entry.getKey();

			final Object currentSessionAttrValue = entry.getValue();
			try {
				final SessionAttributeRecord existingSar = this.attributes.get(key);
				final int update_count = existingSar == null ? 1 : existingSar.update_count + 1;
				final SessionAttributeRecord newAttrRec = new SessionAttributeRecord(getLogger(), key, currentSessionAttrValue, update_count);
				attributes_count++;
				attributes_size += newAttrRec.data_length;
				if (existingSar == null || existingSar.data_length != newAttrRec.data_length || !existingSar.data_checksum.equals(newAttrRec.data_checksum)) {
					modifiedSessionAttributeRecords.put(key, newAttrRec);
					//new attr or changed attributes,  put to the attributes collection.
					putAttributeRecord(newAttrRec);
				}

				// remove this from the removedSessionAttributes because we know it exists now  --redundant?
				removedSessionAttributes.remove(key);
			} catch (final Exception e) {
				//getLogger().error("Problem processing session attribute change for session: " + session_id + " and attribute: " + key, e);
				// we'll end up skipping saving this attribute, it probably had problems being serialized
			}
		}

		Map<String, Object>  sessionAttrs = ReflectionUtils.getSessionAttributes(currentSession);
		final Set<String> keySet = this.attributes.keySet();
		for (final Iterator<String> attrIterator = keySet.iterator(); attrIterator.hasNext();) {
			final String key = attrIterator.next();
			if (!sessionAttrs.containsKey(key)) {
				removedSessionAttributes.add(key);
				modifiedSessionAttributeRecords.remove(key);
				//remove from attributes list also
				attrIterator.remove();
			}
		}

	}

	public void setHeaderData(final StandardSession s, final Request request, final String nodeID, final String webApp) {
		assertThreadHasLock();
		final SessionSerializationHeaderData oldHeader = header;
		header = new SessionSerializationHeaderData(getLogger(), s, request, nodeID, webApp);
		headerChanged = oldHeader == null || !oldHeader.equals(header);
	}

	public Collection<SessionAttributeRecord> getModifiedSessionAttributeRecords() {
		assertThreadHasLock();
		final Collection<SessionAttributeRecord> result = modifiedSessionAttributeRecords.values();
		return result;
	}

	public Collection<String> getRemovedSessionAttributes() {
		assertThreadHasLock();
		return removedSessionAttributes;
	}

	public int getAttributes_count() {
		return attributes_count;
	}

	public int getAttributes_size() {
		return attributes_size;
	}

	public static class CouldNotObtainLockException extends Exception {
		private static final long serialVersionUID = 1L;

		public CouldNotObtainLockException() {
			super();
		}

		public CouldNotObtainLockException(final String message, final Throwable cause) {
			super(message, cause);
		}

		public CouldNotObtainLockException(final String message) {
			super(message);
		}

		public CouldNotObtainLockException(final Throwable cause) {
			super(cause);
		}

	}

	public long getLastAccessTime() {
		return lastAccessTime;
	}

	public void setLastAccessTime(long lastAccessTime) {
		this.lastAccessTime = lastAccessTime;
	}

	public String getId() {
		return id;
	}



	@Override
	public void writeObjectData(ObjectOutputStream stream) throws IOException {

		// Write the scalar instance variables (except Manager)
		//if (getLogger().isinfoEnabled())
		getLogger().info("writeObjectData() storing session " + id);
		stream.writeObject(Integer.valueOf(attributes_count));
		stream.writeObject(Integer.valueOf(attributes_size));
		stream.writeObject(id);
		//stream.writeObjectData(lock); transient.
		stream.writeObject(lockAcquisitionTime);
		stream.writeObject(Boolean.valueOf(headerChanged));

		/*SessionSerializationHeaderData header;*/
		if (header==null)
			header = new SessionSerializationHeaderData(getLogger());
		header.writeObjectData(stream);


		/*Map<String, SessionAttributeRecord> attributes;*/
		int attributes_count = attributes.size();
		stream.writeObject(Integer.valueOf(attributes_count));
		for (Map.Entry<String, SessionAttributeRecord> entry : attributes.entrySet()) {
			stream.writeObject(entry.getKey());
			entry.getValue().writeObjectData(stream);
		}

		/*Map<String, SessionAttributeRecord> modifiedSessionAttributeRecords*/
		int modifiedSessionAttributeRecords_cnt = modifiedSessionAttributeRecords.size();
		stream.writeObject(Integer.valueOf(modifiedSessionAttributeRecords_cnt));
		for (Map.Entry<String, SessionAttributeRecord> entry : modifiedSessionAttributeRecords.entrySet()) {
			stream.writeObject(entry.getKey());
			entry.getValue().writeObjectData(stream);
		}

		/*removedSessionAttributes*/
		int removedSessionAttributes_count  = removedSessionAttributes.size();
		stream.writeObject(Integer.valueOf(removedSessionAttributes_count));
		for (String removedSessionAttribute : removedSessionAttributes) {
			stream.writeObject(removedSessionAttribute);
		}

		stream.writeObject(persistenceQueueState);
		stream.writeObject(Long.valueOf(waitLockTime));
		stream.writeObject(Long.valueOf(lastAccessTime));

	}


	@Override
	public void readObjectData(ObjectInputStream stream) throws ClassNotFoundException, IOException {

		attributes_count = ((Integer) stream.readObject()).intValue();
		attributes_size = ((Integer) stream.readObject()).intValue();
		id = (String)stream.readObject();
		//make a new lock.
		lock= new OwnerExposingReentrantLock();
		lockAcquisitionTime = (Long)stream.readObject();
		headerChanged =  ((Boolean)stream.readObject()).booleanValue();


		if (header==null)
			header = new SessionSerializationHeaderData(getLogger());
		header.readObjectData(stream);


		/*Map<String, SessionAttributeRecord> attributes;*/
		int attributes_count = ((Integer)stream.readObject()).intValue();
		for (int i = 0; i < attributes_count; i++) {
			String key = (String)stream.readObject();
			SessionAttributeRecord attr = new SessionAttributeRecord();
			attr.readObjectData(stream);
			attributes.put(key, attr);
		}

		/*Map<String, SessionAttributeRecord> modifiedSessionAttributeRecords*/
		int modifiedSessionAttributeRecords_cnt = ((Integer)stream.readObject()).intValue();

		for (int i = 0; i < modifiedSessionAttributeRecords_cnt; i++) {
			String key = (String)stream.readObject();
			SessionAttributeRecord attr = new SessionAttributeRecord();
			attr.readObjectData(stream);
			modifiedSessionAttributeRecords.put(key, attr);
		}

		/*Set<String> removedSessionAttributes;*/
		int removedSessionAttributes_count =((Integer)stream.readObject()).intValue();
		for (int i = 0; i < removedSessionAttributes_count; i++) {
			removedSessionAttributes.add((String)stream.readObject());
		}

		persistenceQueueState = (PersistenceQueueState)stream.readObject();
		waitLockTime =((Long)stream.readObject()).longValue();
		lastAccessTime = ((Long)stream.readObject()).longValue();


	}


	/**
	 * Wipe the attribute data to be stored to the cache to save memory.
	 */
	public void wipeAttributeData() {
		for (Map.Entry<String, SessionAttributeRecord> entry : attributes.entrySet()) {
			entry.getValue().data=null;
		}
	}

}
