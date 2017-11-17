package org.apache.catalina.session;

import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import javax.servlet.http.HttpSessionActivationListener;

import org.apache.catalina.Container;
import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.SessionSerializationData.CouldNotObtainLockException;
import org.apache.catalina.session.ReflectionUtils;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.session.StoreBase;
import org.apache.catalina.util.CustomObjectInputStream;

import java.util.logging.Logger;

/**
 * Base Foundation {@link org.apache.catalina.Session} {@link org.apache.catalina.Store} that manages the session store activities with no
 * actual persistence implementation.
 *
 * @author jim631@sina.com
 */
public abstract class BaseFoundationSessionStore extends StoreBase implements FoundationSessionStore {

	private static final Logger log = Logger.getLogger(BaseFoundationSessionStore.class.getName());

	private static final String SESSION_SERIALIZATION_DATA_KEY = "SessionSerializationData";

	private final String nodeID;

	protected SessionCache sessionCache;

	private final Object lock = new Object();

	public BaseFoundationSessionStore() {
		super();
		this.nodeID = NetUtil.getHostName();
		this.sessionCache = new SessionCache();
	}

    //todo not to expose as JMX attributes. for now can't name it as a getter.
	public SessionCache sessionCache() {
		return sessionCache;
	}





	/**
	 * Get from cache, if not found ,try load it from DB.
	 *
	 * @param sessionId
	 * @return
	 */
	public SessionSerializationData getSessionSerializationDataForSession(final String sessionId) {

		SessionSerializationData ssd = sessionCache().get(sessionId);

		//wait on loading from DB, this is intended so we don't result in possible simuteneous loads
		if (ssd == null) {
			synchronized (lock) {
				//try if again after obtaining the lock.
				ssd = sessionCache().get(sessionId);
				if (ssd == null) {
					//load from DB , it put in the cache. so get from cache again.
					try {
						load(sessionId);
					} catch (ClassNotFoundException | IOException e) {
						throw new RuntimeException("load session failed", e);
					}

					//load session put in the cache, so obtian from it.
					ssd = sessionCache().get(sessionId);

					if (ssd == null) {
						//still null
						ssd = new SessionSerializationData(log, sessionId);
						sessionCache().put(sessionId, ssd);

					}
				}
			}
		}

		return ssd;
	}

	protected void cacheSessionSerializationData(final StandardSession session, final SessionSerializationData ssd) {

		ssd.setLastAccessTime(session.getThisAccessedTimeInternal());

		//set the data field of all attributes to be null to save  memory.
		ssd.wipeAttributeData();

		sessionCache().put(session.getId(), ssd);
	}


	protected StandardSession createSessionFromData(final String id, final byte[] data) throws IOException {
		if (data == null) {
			return null;
		}

		final StandardSession result = (StandardSession) manager.createEmptySession();
		result.setNew(false);
		result.setValid(true);
		result.setCreationTime(System.currentTimeMillis());
		result.setMaxInactiveInterval(manager.getMaxInactiveInterval());
		/* -- result.setId(c);*/
		//set id add this session to the sessions cache !
		ReflectionUtils.setFieldValue(StandardSession.class, result, "id", id);

		try {
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			//if (loader == null)
			//	loader = SerializationUtils.getWebappClassLoader(manager);
			final ByteArrayInputStream bis = new ByteArrayInputStream(data);
			final ObjectInputStream ois = new CustomObjectInputStream(bis, loader);
			result.readObjectData(ois);
			ois.close();
		} catch (final ClassNotFoundException e) {
			final String message = "Problem deserializing session: " + id + ", data.length=" + data.length;
			//log.error(message, e);
			throw new IOException(message, e);
		} catch (final IOException e) {
			final String message = "Problem deserializing session: " + id + ", data.length=" + data.length;
			//log.error(message, e);
			throw new IOException(message, e);
		}
		return result;
	}


	@Override
	public void save(final Session session) throws IOException {
		processChanges(session, null);
	}




	@Override
	public void processChanges(final Session session, final Request request) throws IOException {

		if (FoundationUtil.isStaticResource(request))
			return;

		log.info("processChanges()");

		if (session == null) { // no session, nothing to save
			return;
		}

		if (!session.isValid()) { // invalid session, it will have already removed itself, we can ignore it
			return;
		}

		if (!(session instanceof StandardSession)) {
			return; // ignore DummyProxySessions and possibly other unit-test type sessions that should not be persisted
		}

		final long start = System.currentTimeMillis();

		final String sessionID = session.getIdInternal();

		final StandardSession actualSession = (StandardSession) session; // keep a reference to the original session since the notes won't survive the cloning process, and we need the notes
		StandardSession currentSession = actualSession;
		if (hasHttpSessionActivationListener(currentSession)) { // give the HttpActivationListener a chance to mutate the copy of the session before persisting
			try {
				final byte[] data = serializeFrom(currentSession);
				final StandardSession sessionClone = createSessionFromData(sessionID, data);
				currentSession = sessionClone;
			} catch (final Exception e) {
			  //TODO we need handle Session passivate 
			  //here need take some actions to desrialize the data
		      //log.error("Problem cloning session: " + sessionID, e);
			}
			currentSession.passivate();
		}
	    //need to use reflection because the package is v2 now and the attributes is protected in StandardSession
		//todo after v2 is stable after a release or so,m we'll remove v1 and move v2 to the  org.apache.catalina.session package.
		//then such reflection will no lonber be needed.
		Map<String, Object> attributes = ReflectionUtils.getSessionAttributes(currentSession);
		if (attributes.isEmpty()) { // if there are no attributes, then there is nothing to save
			return;
		}

		SessionSerializationData ssdLastSnapshot = getSessionSerializationDataForSession(actualSession.getId());
		try {
			/*no longer needed to compete with the persistence events. Tony b1408*/
			// in order to capture the session data, we want to be sure we're not in the middle of a persistence event,
			// else we risk a concurrent modification of the session data while it is being stored so we will try to get
			// a lock (waiting for a little while) but if we can't we'll pass and hope for better luck next time
			//ssdLastSnapshot.tryLock(10, TimeUnit.SECONDS); // persistence should never take longer than a few 10s of ms, but under load this could go longer


			//get a lock to capture changes
			ssdLastSnapshot.tryLock(100, TimeUnit.MILLISECONDS);

			try {
				final String nodeID = getNodeID();
				final String webApp = getWebapp();

				//capture the deltas between ssdLastSnapshot attributes and the  currentSession attributes.
				ssdLastSnapshot.captureSessionData(currentSession, request, nodeID, webApp);

				//save the snapshot to cache
				//don't need to actually attr data within to save memory.
				cacheSessionSerializationData(currentSession, ssdLastSnapshot);

				if (ssdLastSnapshot.isDirty()) {

					//need to be persisted.
					 /*make a clone and send to the queue.*/
					SessionSerializationData cloneSSD = null;
					try {
						//the clone is to hand off to the aync persistence part
						//only the header, modified and removed list attributes is required
						//attributes with Data are not required. so the cloneSSD here does not have data.
						//will need the data in the modifed and removed list of attributes though.
						cloneSSD = SerializationUtils.cloneSessionSerializationData(manager, ssdLastSnapshot);
					} catch (ClassNotFoundException e) {
						throw new RuntimeException("cloneSessionSerializationData() failed for sessionId="+ sessionID, e);
					}
					//handleDirtySession
					sendChangedSessionDataToPersist(actualSession, cloneSSD);
					//if (log.isDebugEnabled()) {
						final long end = System.currentTimeMillis();
						log.info("Process session Delta for : " + sessionID + ", attributes_size=" + ssdLastSnapshot.getAttributes_size()
								+ " time=" + (end - start));
						log.info("\tmodified attributes:" + ssdLastSnapshot.getModifiedSessionAttributeRecords().size());
						log.info("\tremoved attributes:" + ssdLastSnapshot.getRemovedSessionAttributes().size());
					//}

				}
				else {
					log.info("\tSSD is not dirty. session not changed for this request.");
				}
			} finally {
				ssdLastSnapshot.unlock();
			}


		}
		catch (final InterruptedException e) {
			throw new IOException("Unable to save session data for persistence for session " + sessionID, e);
		} catch (final CouldNotObtainLockException e) {

			final Thread owner = ssdLastSnapshot.getLockOwner();
//			final String stackTrace;
//			if (owner == null)
//				stackTrace = "(no thread)";
//			else
//				stackTrace = FoundationUtil.getThreadStackTrace(owner, 0);
			log.info("A Thread is already working on the processing session delta:" + owner);
// this is no longer a warning. if this thread can't obtain a lock it means some other thread/request is process the current session changes.
//the fact this thread comes in so adjacent to the already working thread, we assume the session has not changed.
// it confirms from the log.
//			2014-07-05 11:30:53,460 [DEBUG] http-bio-8080-exec-11 - t1 - https://txue-t3500.successfactors.com/learning/ui/juic/img/sfloading_back.gif;mod=9d8634d3 tomcat - BaseFoundationSessionStore - A Thread is already working on the processing session delta:null
//			2014-07-05 11:30:53,461 [DEBUG] http-bio-8080-exec-2 - t1 - https://txue-t3500.successfactors.com/learning/ui/juic/img/ico_loading_lg_gry_on-wht.gif;mod=84bbbd2d tomcat - BaseFoundationSessionStore - A Thread is already working on the processing session delta:null


//			log.warn("Unable lock the SSD to process changes.  for session " + sessionID
//					+ " due to lock contention on the session serialization data token\nThread " + owner + " holds the lock.  Thread stack trace:\n"
//					+ stackTrace);
		}
	}

	protected abstract void sendChangedSessionDataToPersist(final StandardSession session, final SessionSerializationData ssd) throws IOException;

	@Override
	public void flush(final String  sessionId) throws IOException {
		SessionSerializationData ssd = getSessionSerializationDataForSession(sessionId);
		flush(ssd);
	}


		@Override
	public void flush( final SessionSerializationData ssd) throws IOException {

		if (ssd == null) { // no session, nothing to save
			return;
		}

	/*--         Now we got SSD directly passed in. session is supposed to be checked already.
		if (!session.isValid()) { // invalid session, it will have already removed itself, we can ignore it
			return;
		}

		if (!(session instanceof StandardSession)) {
			return; // ignore DummyProxySessions and possibly other unit-test type sessions that should not be persisted
		}

		final String session_id = session.getIdInternal();
		final StandardSession s = (StandardSession) session;

		//The ssd to be persisted
		//-final SessionSerializationData ssd = getSessionSerializationDataForSession(s);

*/

		final String session_id = ssd.getId();

		try {
			if (ssd.tryLockIfDirty(100, TimeUnit.MILLISECONDS)) {
				// set the persistence queue state to PERSISTING
				ssd.setPersistenceQueueState(PersistenceQueueState.STORING);

				try {
					persistSession(ssd);
				} catch (Exception e) {
				  //log.error(e);
				}
				finally {
					// set the persistence queue state to CLEAN
					ssd.setPersistenceQueueState(PersistenceQueueState.STORED);

					ssd.unlock();
				}
			}
		} catch (final InterruptedException e) {
			throw new IOException("Could not save session " + session_id, e);
		} catch (final CouldNotObtainLockException e) {
			final Thread owner = ssd.getLockOwner();
			final String stackTrace;
			if (owner == null)
				stackTrace = "(no thread)";
			else
				stackTrace = FoundationUtil.getThreadStackTrace(owner, 0);
			//log.warn("[flush]Unable to save session data for persistence for session " + session_id
			//		+ " due to lock contention on the session serialization data token\nThread " + owner + " holds the lock.  Thread stack trace:\n"
			//		+ stackTrace);
		}
	}

	protected abstract void persistSession(final SessionSerializationData ssd) throws IOException;

	protected String getNodeID() {
		return nodeID;
	}

	protected String getWebapp() {
		final String result;
		final Container container = manager.getContainer();
		if (container instanceof StandardContext) {
			String path = ((StandardContext) container).getPath();
			if (path == null || path.length() == 0)
				path = "/";
			result = path;
		} else {
			result = container.toString();
		}
		return result;
	}

	protected byte[] serializeFrom(final StandardSession session) throws IOException {
		final Callable<byte[]> process = new Callable<byte[]>() {
			@Override
			public byte[] call() throws IOException {
				final ByteArrayOutputStream bos = new ByteArrayOutputStream();
				final ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(bos));
				try {
					session.writeObjectData(oos);
				} finally {
					oos.close();
				}
				final byte[] result = bos.toByteArray();
				return result;
			}
		};
		try {
			final byte[] result = FoundationUtil.tryMultipleTimes(process, ConcurrentModificationException.class);
			return result;
		} catch (final IOException e) {
			throw e;
		} catch (final Exception e) {
			throw new IOException("Could not serialize session data", e);
		}
	}

	protected boolean hasHttpSessionActivationListener(final StandardSession session) {
		boolean result = false;
		//session.keys();
		String[] keys = (String[]) ReflectionUtils.invokeMethod(session, "keys");
		Map<String, Object> attributes = (Map<String, Object>) ReflectionUtils.getFieldValue(StandardSession.class, session, "attributes");
		for (int i = 0; i < keys.length; i++) {
			final Object attribute = attributes.get(keys[i]);
			if (attribute instanceof HttpSessionActivationListener) {
				result = true;
				break;
			}
		}

		return result;
	}

	protected int getSystemPropertyIntValue(final String propertyName, final int defaultValue) {
		int result = defaultValue;
		final String key = this.getClass().getSimpleName() + "." + propertyName;
		final String propertyValue = System.getProperty(key);
		if (propertyValue != null && propertyValue.length() > 0) {
			try {
				result = Integer.parseInt(propertyValue);
			} catch (final NumberFormatException e) {
				result = defaultValue;
				//log.error("Could not set " + propertyName + " from system property " + key + " value " + propertyValue + "; default value used: "
				//		+ defaultValue, e);
			}
		}
		return result;
	}

	protected long getSystemPropertyLongValue(final String propertyName, final long defaultValue) {
		long result = defaultValue;
		final String key = this.getClass().getSimpleName() + "." + propertyName;
		final String propertyValue = System.getProperty(key);
		if (propertyValue != null && propertyValue.length() > 0) {
			try {
				result = Long.parseLong(propertyValue);
			} catch (final NumberFormatException e) {
				result = defaultValue;
				//log.error("Could not set " + propertyName + " from system property " + key + " value " + propertyValue + "; default value used: "
				//		+ defaultValue, e);
			}
		}
		return result;
	}

	protected int[] getSystemPropertyIntArrayValue(final String propertyName, final int[] defaultValues) {
		int[] result = defaultValues;
		final String key = this.getClass().getSimpleName() + "." + propertyName;
		final String propertyValue = System.getProperty(key);
		if (propertyValue != null && propertyValue.length() > 0) {
			try {
				final int[] values = FoundationUtil.getIntArrayFromCommaDelimitedString(propertyValue);
				if (values.length != defaultValues.length) {
					//log.error("Could not set " + propertyName + " from system property " + key + " value " + propertyValue
					//		+ " - provided array size did not match default array size; default value used: "
					//		+ FoundationUtil.intArrayToCollection(defaultValues));
				} else {
					result = values;
				}
			} catch (final NumberFormatException e) {
				result = defaultValues;
				//log.error("Could not set " + propertyName + " from system property " + key + " value " + propertyValue + "; default value used: "
				//		+ FoundationUtil.intArrayToCollection(defaultValues), e);
			}
		}
		return result;
	}

}
