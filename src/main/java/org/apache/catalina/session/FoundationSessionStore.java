package org.apache.catalina.session;

import java.io.IOException;
import java.util.Set;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.Session;
import org.apache.catalina.Store;
import org.apache.catalina.connector.Request;
import org.apache.catalina.session.StandardSession;

/**
 * {@link org.apache.catalina.Session} {@link org.apache.catalina.Store} for use with the FoundationSessionManager.
 *
 * @author jim631@sina.com
 */
public interface FoundationSessionStore extends Store, Lifecycle {

	/**
	 * Return true if the session store is available and ready for use.
	 */
	public boolean isStoreAvailable();

	/**
	 * Save a session data to the store immediately.
	 *
	 * @param session session to persist/save
	 * @throws java.io.IOException thrown if a persistence fails
	 */
	public void flush(final String  sessionId) throws IOException;

	/**
	 * Save a session data to the store immediately.
	 * @param ssd
	 * @throws IOException
	 */
	public void flush(final SessionSerializationData ssd) throws IOException;

	/**
	 * Examine the given session for changes and possibly persist or queue for later persistence.
	 *
	 * @param session session to persist/save
	 * @param request optional request (used to scrape request metadata to store with the session)
	 *
	 * @throws java.io.IOException thrown if a persistence fails
	 */
	public void processChanges(final Session session, final Request request) throws IOException;

	/**
	 * Load a session from the store.
	 *
	 * @param id session id to load
	 * @return StandardSession or null if no session available
	 * @throws ClassNotFoundException if the session deserialization fails due to CNF
	 * @throws java.io.IOException if problem occurs in reading the session from the store
	 */
	@Override
	public StandardSession load(final String id) throws ClassNotFoundException, IOException;

	/**
	 * Retrieve the session keys for expired sessions.
	 *
	 * @return Set of session IDs for sessions that are expired
	 * @throws java.io.IOException if problem happens during read from store
	 */
	public Set<String> getExpiredSessionKeys() throws IOException;



	/**
	 *
	 * @return
	 */
	public SessionCache sessionCache();

}
