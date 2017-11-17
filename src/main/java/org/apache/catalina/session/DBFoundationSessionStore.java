package org.apache.catalina.session;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.apache.commons.dbcp.BasicDataSource;

import java.util.logging.Logger;

/**
 * {@link org.apache.catalina.Session} {@link org.apache.catalina.Store} that can read and write sessions to an Oracle database.
 *
 * @author jim631@sina.com
 */
public class DBFoundationSessionStore extends BaseFoundationSessionStore {

	private static final Logger log = Logger.getLogger(DBFoundationSessionStore.class.getName());

	private static final String NAME = DBFoundationSessionStore.class.getSimpleName();
	private static final String INFO = NAME + "/1.0";

	/**
	 * Return the name for this Store, used for logging.
	 */
	@Override
	public String getStoreName() {
		return NAME;
	}

	//@Override
	public String getInfo() {
		return INFO;
	}

	private static final String countSessionSQL = //
	"SELECT count(node_id) session_count" //
			+ " FROM ps_foundation_session s" //
			+ " WHERE s.node_id = ?" //
			+ " AND s.webapp = ?" //
	;

	@Override
	public int getSize() throws IOException {
		if (!isStoreAvailable())
			return 0;

		int result = 0;

			Connection conn = null;
			PreparedStatement ps = null;
			ResultSet rs = null;
			final String node_id = getNodeID();
			final String webapp = getWebapp();
			try {
				conn = getConnection();
				ps = conn.prepareStatement(countSessionSQL);
				ps.setString(1, node_id);
				ps.setString(2, webapp);
				rs = ps.executeQuery();
				if (rs.next()) {
					result = rs.getInt(1);
				}
				rs.close();
				rs = null;
				ps.close();
				ps = null;
				conn.close();
				conn = null;
			} catch (final Exception e) {
				final String message = "Problem counting sessions for node: " + node_id + " and webapp: " + webapp;
				////log.error(message, e);
				throw new IOException(message, e);
			} finally {
				closeFinally(conn, ps, rs);
			}

		return result;
	}

	private static final String sessionKeysSQL = //
	"SELECT session_id" //
			+ " FROM ps_foundation_session s" //
			+ " WHERE s.node_id = ?" //
			+ " AND s.webapp = ?" //
			+ " ORDER BY expiration_time" //
	;

	@Override
	public String[] keys() throws IOException {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		final ArrayList<String> list = new ArrayList<String>();
		final String node_id = getNodeID();
		final String webapp = getWebapp();
		try {
			conn = getConnection();
			ps = conn.prepareStatement(sessionKeysSQL);
			ps.setString(1, node_id);
			ps.setString(2, webapp);
			rs = ps.executeQuery();
			while (rs.next()) {
				final String session_id = rs.getString(1);
				list.add(session_id);
			}
			rs.close();
			rs = null;
			ps.close();
			ps = null;
			conn.close();
			conn = null;
		} catch (final SQLException e) {
			final String message = "Problem getting session IDs for node: " + node_id + " and webapp: " + webapp;
			//log.error(message, e);
			throw new IOException(message, e);
		} finally {
			closeFinally(conn, ps, rs);
		}

		final String[] result = list.toArray(new String[list.size()]);
		return result;
	}

	private static final String loadSessionSQL = //
	"SELECT s.session_id, s.creation_time, s.last_accessed_time, s.max_inactive_interval, s.is_new, s.is_valid, s.this_accessed_time, s.request_count, a.attr_key, a.update_count, a.data_length, a.data_checksum, a.data_type, a.data" //
			+ " FROM ps_foundation_session s, ps_foundation_session_attr a" //
			+ " WHERE s.session_id = ?" //
			+ " AND s.session_id = a.session_id" //
	;

	@Override
	public StandardSession load(final String id) throws ClassNotFoundException, IOException {
		//if (log.isInfoEnabled())
			log.info("Loading session: " + id);

		final HashMap<String, SessionAttributeRecord> attributes = new HashMap<String, SessionAttributeRecord>();
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		StandardSession result = null;
		try {
			conn = getConnection();
			ps = conn.prepareStatement(loadSessionSQL);
			ps.setString(1, id);
			rs = ps.executeQuery();
			while (rs.next()) {
				if (result == null) {
					result = (StandardSession) manager.createEmptySession();
					//--result.setId( rs.getString("session_id"));
					ReflectionUtils.setFieldValue(StandardSession.class, result, "id", rs.getString("session_id"));

					result.setCreationTime(rs.getTimestamp("creation_time").getTime());


					//need to use reflection because the package is v2 now and the attributes is protected in StandardSession
					//todo after v2 is stable after a release or so,m we'll remove v1 and move v2 to the  org.apache.catalina.session package.
					//then such reflecton will no lonber be needed.

					//result.lastAccessedTime(rs.getTimestamp("last_accessed_time").getTime());
					ReflectionUtils.setFieldValue(StandardSession.class, result
							, "lastAccessedTime"
							, Long.valueOf(rs.getTimestamp("last_accessed_time").getTime()));


					result.setMaxInactiveInterval( rs.getInt("max_inactive_interval"));
					result.setNew(rs.getString("is_new").equals("Y"));
					result.setValid(rs.getString("is_valid").equals("Y"));

					//result.thisAccessedTime = rs.getTimestamp("this_accessed_time").getTime();
					ReflectionUtils.setFieldValue(StandardSession.class, result
							, "thisAccessedTime"
							, Long.valueOf(rs.getTimestamp("this_accessed_time").getTime()));


					result.setNote("request_count", rs.getString("request_count"));
				}
				final String key = rs.getString("attr_key");
				final int update_count = rs.getInt("update_count");
				final int data_length = rs.getInt("data_length");
				final String data_checksum = rs.getString("data_checksum");
				final String data_type = rs.getString("data_type");
				final byte[] data = rs.getBytes("data");
				try {
					final Object value = SerializationUtils.createObjectFromData(manager, data);
					if (value != null) {
						Map<String, Object> retAttributes =(Map<String, Object>) ReflectionUtils.getFieldValue(StandardSession.class, result, "attributes");
						retAttributes.put(key, value);
						//result.attributes.put(key, value);
						// no need for the data here - this is just a record of what the attr looked like @ the time
						//changed to handle it at the cache level . when put in cache, wipe it out. Before that, we need the data.
						final SessionAttributeRecord sar = new SessionAttributeRecord(key, update_count, data_length, data_checksum, data_type, data);
						attributes.put(key, sar);
						log.info("\tLoaded attribute [" + key + "], value=" + value);
					} else {
						log.info("\tAttribute [" + key + "] skipped due to null value stored in DB");
					}
				} catch (final RuntimeException e) {
					//if (log.isInfoEnabled())
						log.info("\tSkipping problematic session attribute: [" + key + "] while loading session: " + id);
				} catch (final IOException e) {
					//if (log.isInfoEnabled())
						log.info("\tSkipping problematic session attribute: [" + key + "] while loading session: " + id);
				}
			}
			if (result != null) {
				final SessionSerializationData ssd = new SessionSerializationData(log, id, attributes);
				cacheSessionSerializationData(result, ssd);
			}

			rs.close();
			rs = null;
			ps.close();
			ps = null;
			conn.close();
			conn = null;
		} catch (final SQLException e) {
			final String message = "Problem loading session with id: " + id;
			//log.error(message, e);
			throw new IOException(message, e);
		} finally {
			closeFinally(conn, ps, rs);
		}
		//if (log.isInfoEnabled()) {
			if (result == null) {
				log.info("Session not loaded - does not exist in store: " + id);
			} else {
				log.info("Loaded session: " + id);
			}
		//}
		return result;
	}

	private static final String removeSessionSQL = //
	"DELETE FROM ps_foundation_session" //
			+ " WHERE session_id = ?";

	@Override
	public void remove(final String id) throws IOException {
		//if (log.isDebugEnabled())
		log.info("Removing session " + id);
		Connection conn = null;
		PreparedStatement ps = null;
		try {
			conn = getConnection();
			ps = conn.prepareStatement(removeSessionSQL);
			ps.setString(1, id);
			ps.executeUpdate();
			ps.close();
			ps = null;
			conn.close();
			conn = null;
		} catch (final SQLException e) {
			final String message = "Problem deleting session with id: " + id;
			//log.error(message, e);
			throw new IOException(message, e);
		} finally {
			closeFinally(conn, ps, null);
		}
		//if (log.isDebugEnabled())
			log.info("Removed session " + id);
	}

	private static final String clearSessionsSQL = //
	"DELETE FROM ps_foundation_session" //
			+ " WHERE node_id = ?" //
			+ " AND webapp = ?" //
	;

	@Override
	public void clear() throws IOException {
		//if (log.isDebugEnabled())
	    log.info("Clearing all sessions");
		Connection conn = null;
		PreparedStatement ps = null;
		final String node_id = getNodeID();
		final String webapp = getWebapp();
		try {
			conn = getConnection();
			ps = conn.prepareStatement(clearSessionsSQL);
			ps.setString(1, node_id);
			ps.setString(2, webapp);
			ps.executeUpdate();
			ps.close();
			ps = null;
			conn.close();
			conn = null;
		} catch (final SQLException e) {
			final String message = "Problem clearing sessions for node: " + node_id + " and webapp: " + webapp;
			//log.error(message, e);
			throw new IOException(message, e);
		} finally {
			closeFinally(conn, ps, null);
		}
		//if (log.isDebugEnabled())
			log.info("All sessions cleared");
	}

	private static final String insertSessionSQL = //
	" INSERT INTO ps_foundation_session " //
			+ /**/" (session_id" //
			+ /**/", tenant_id" //
			+ /**/", user_id" //
			+ /**/", node_id" //
			+ /**/", webapp" //
			+ /**/", creation_time" //
			+ /**/", last_accessed_time" //
			+ /**/", max_inactive_interval" //
			+ /**/", expiration_time" //
			+ /**/", is_new" //
			+ /**/", is_valid" //
			+ /**/", this_accessed_time" //
			+ /**/", request_count" //
			+ /**/", attributes_count" //
			+ /**/", attributes_size" //
			+ /**/", user_agent" //
			+ /**/", remote_host" //
			+ /**/", remote_addr" //
			+ /**/", remote_port" //
			+ /**/", remote_user" //
			+ /**/") VALUES" //
			+ /**/" (?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/")"; //

	private static final String updateSessionSQL = //
	" UPDATE ps_foundation_session SET" //
			+ /**/" tenant_id = ?" //
			+ /**/", user_id = ?" //
			+ /**/", node_id = ?" //
			+ /**/", webapp = ?" //
			+ /**/", creation_time = ?" //
			+ /**/", last_accessed_time = ?" //
			+ /**/", max_inactive_interval = ?" //
			+ /**/", expiration_time = ?" //
			+ /**/", is_new = ?" //
			+ /**/", is_valid = ?" //
			+ /**/", this_accessed_time = ?" //
			+ /**/", request_count = ?" //
			+ /**/", attributes_count = ?" //
			+ /**/", attributes_size = ?" //
			+ /**/", user_agent = ?" //
			+ /**/", remote_host = ?" //
			+ /**/", remote_addr = ?" //
			+ /**/", remote_port = ?" //
			+ /**/", remote_user = ?" //
			+ " where session_id = ?";

	private static final String getSessionSQL = " SELECT * from ps_foundation_session where session_id = ?";

	/*	private static final String saveSessionSQL = //
		"MERGE INTO ps_foundation_session s" //
				+ " USING (SELECT" //
				+ " ? session_id" //
				+ ", ? tenant_id" //
				+ ", ? user_id" //
				+ ", ? node_id" //
				+ ", ? webapp" //
				+ ", ? creation_time" //
				+ ", ? last_accessed_time" //
				+ ", ? max_inactive_interval" //
				+ ", ? expiration_time" //
				+ ", ? is_new" //
				+ ", ? is_valid" //
				+ ", ? this_accessed_time" //
				+ ", ? request_count" //
				+ ", ? attributes_count" //
				+ ", ? attributes_size" //
				+ ", ? user_agent" //
				+ ", ? remote_host" //
				+ ", ? remote_addr" //
				+ ", ? remote_port" //
				+ ", ? remote_user" //
				+ " FROM dual) i" //
				+ " ON (s.session_id = i.session_id)" //
				+ " WHEN MATCHED THEN" //
				+ " UPDATE SET" //
				+ " s.tenant_id = i.tenant_id" //
				+ ", s.user_id = i.user_id" //
				+ ", s.node_id = i.node_id" //
				+ ", s.webapp = i.webapp" //
				+ ", s.creation_time = i.creation_time" //
				+ ", s.last_accessed_time = i.last_accessed_time" //
				+ ", s.max_inactive_interval = i.max_inactive_interval" //
				+ ", s.expiration_time = i.expiration_time" //
				+ ", s.is_new = i.is_new" //
				+ ", s.is_valid = i.is_valid" //
				+ ", s.this_accessed_time = i.this_accessed_time" //
				+ ", s.request_count = i.request_count" //
				+ ", s.attributes_count = i.attributes_count" //
				+ ", s.attributes_size = i.attributes_size" //
				+ ", s.user_agent = i.user_agent" //
				+ ", s.remote_host = i.remote_host" //
				+ ", s.remote_addr = i.remote_addr" //
				+ ", s.remote_port = i.remote_port" //
				+ ", s.remote_user = i.remote_user" //
				+ " WHEN NOT MATCHED THEN" //
				+ " INSERT" //
				+ " (s.session_id" //
				+ ", s.tenant_id" //
				+ ", s.user_id" //
				+ ", s.node_id" //
				+ ", s.webapp" //
				+ ", s.creation_time" //
				+ ", s.last_accessed_time" //
				+ ", s.max_inactive_interval" //
				+ ", s.expiration_time" //
				+ ", s.is_new" //
				+ ", s.is_valid" //
				+ ", s.this_accessed_time" //
				+ ", s.request_count" //
				+ ", s.attributes_count" //
				+ ", s.attributes_size" //
				+ ", s.user_agent" //
				+ ", s.remote_host" //
				+ ", s.remote_addr" //
				+ ", s.remote_port" //
				+ ", s.remote_user" //
				+ ") VALUES" //
				+ " (i.session_id" //
				+ ", i.tenant_id" //
				+ ", i.user_id" //
				+ ", i.node_id" //
				+ ", i.webapp" //
				+ ", i.creation_time" //
				+ ", i.last_accessed_time" //
				+ ", i.max_inactive_interval" //
				+ ", i.expiration_time" //
				+ ", i.is_new" //
				+ ", i.is_valid" //
				+ ", i.this_accessed_time" //
				+ ", i.request_count" //
				+ ", i.attributes_count" //
				+ ", i.attributes_size" //
				+ ", i.user_agent" //
				+ ", i.remote_host" //
				+ ", i.remote_addr" //
				+ ", i.remote_port" //
				+ ", i.remote_user" //
				+ ")" //
		;*/

	private static final String insertSessionAttributeSQL = //
	" INSERT INTO ps_foundation_session_attr " //
			+ /**/" (session_id" //
			+ /**/", attr_key" //
			+ /**/", last_updated_time" //
			+ /**/", update_count" //
			+ /**/", data_length" //
			+ /**/", data_checksum" //
			+ /**/", data_type" //
			+ /**/", data" //
			+ /**/") VALUES" //
			+ /**/" (?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/", ?" //
			+ /**/")"; //
	private static final String updateSessionAttributeSQL = //
	" UPDATE ps_foundation_session_attr SET" //
			+ /**/" last_updated_time = ?" //
			+ /**/", update_count = ?" //
			+ /**/", data_length = ?" //
			+ /**/", data_checksum = ?" //
			+ /**/", data_type = ?" //
			+ /**/", data =? " //
			+ /**/" WHERE (session_id = ? AND attr_key = ?)"; //
	private static final String getSessionAttributeSQL = //
	" select * from ps_foundation_session_attr where session_id=? and attr_key=?";

	/*	private static final String saveSessionAttributeSQL = //
		"MERGE INTO ps_foundation_session_attr s" //
				+ " USING (SELECT" //
				+ " ? session_id" //
				+ ", ? key" //
				+ ", ? last_updated_time" //
				+ ", ? update_count" //
				+ ", ? data_length" //
				+ ", ? data_checksum" //
				+ ", ? data_type" //
				+ ", ? data" //
				+ " FROM dual) i" //
				+ " ON (s.session_id = i.session_id AND s.key = i.key)" //
				+ " WHEN MATCHED THEN" //
				+ " UPDATE SET" //
				+ " s.last_updated_time = i.last_updated_time" //
				+ ", s.update_count = i.update_count" //
				+ ", s.data_length = i.data_length" //
				+ ", s.data_checksum = i.data_checksum" //
				+ ", s.data_type = i.data_type" //
				+ ", s.data = i.data" //
				+ " WHEN NOT MATCHED THEN" //
				+ " INSERT" //
				+ " (s.session_id" //
				+ ", s.key" //
				+ ", s.last_updated_time" //
				+ ", s.update_count" //
				+ ", s.data_length" //
				+ ", s.data_checksum" //
				+ ", s.data_type" //
				+ ", s.data" //
				+ ") VALUES" //
				+ " (i.session_id" //
				+ ", i.key" //
				+ ", i.last_updated_time" //
				+ ", i.update_count" //
				+ ", i.data_length" //
				+ ", i.data_checksum" //
				+ ", i.data_type" //
				+ ", i.data" //
				+ ")" //
		;
	*/
	private static final String deleteSessionAttributeSQL = //
	"DELETE FROM ps_foundation_session_attr" //
			+ " WHERE session_id = ?" //
			+ " AND attr_key = ?";

	@Override
	protected void sendChangedSessionDataToPersist(final StandardSession session, final SessionSerializationData ssd) throws IOException {
		// for this implementation, we persist dirty sessions
		flush(ssd);
	}


	//SSD attributes are not needed for persiste, nor the data inside it.
	@Override
	protected void persistSession(final SessionSerializationData ssd) throws IOException {
		// do not bother persisting anything if we have no attribute changes
		final long start = System.currentTimeMillis();
		final String session_id = ssd.getId();
		//if (log.isDebugEnabled())
			log.info("Saving session: " + session_id);
		final SessionSerializationHeaderData header = ssd.getHeader();
//todo debug only
//		try {
//			Thread.sleep(5000);
//		} catch (InterruptedException e) {
//			e.printStackTrace();
//		}

		if (header != null) {
			// if header wasn't there, there's nothing to save anyway
			Connection conn = null;
			try {
				conn = getConnection();
				conn.setAutoCommit(false);

				persistSessionHeader(conn, session_id, ssd);
				persistSessionModifiedAttributes(conn, session_id, ssd);
				persistSessionRemovedAttributes(conn, session_id, ssd);

				conn.commit();

				conn.setAutoCommit(true);
				conn.close();
				conn = null;

				// record save metrics
				final long end = System.currentTimeMillis();
				final long duration = end - start;

				//if (log.isDebugEnabled()) {
					log.info("Saved session: " + session_id + ", attributes_size=" + ssd.getAttributes_size() + " time=" + duration +"ms");
					log.info("\tmodified attributes:" + ssd.getModifiedSessionAttributeRecords().size());
					log.info("\tremoved attributes:" + ssd.getRemovedSessionAttributes().size());

				//}
				ssd.clear();
			} catch (final SQLException e) {
				final int errorCode = e.getErrorCode();
				if (errorCode == 1) { // ORA-00001: unique constraint (PS_FOUNDATION_SESSION_PK) violated
					// Ignore this exception. Another thread already inserted the session but its row was not committed at the time this merge statement fired.
					// We can safely ignore this condition because the other thread already wrote the session (and it was most likely just milliseconds ago, and doesn't need to be written again).
					// Also, if we move saving into an asynchronous background queue and let the background processing thread write them out serially, we won't have to worry about this condition anymore.
				} else {
					final String message = "Problem saving session: " + session_id;
					//log.error(message, e);
					throw new IOException(message, e);
				}
			} finally {
				closeFinally(conn, null, null);
			}
		} else {
			log.info("Skipping the save of session " + session_id + " due to no attribute changes");
		}
	}

	protected void persistSessionHeader(final Connection conn, final String session_id, final SessionSerializationData ssd) throws SQLException {
		final SessionSerializationHeaderData header = ssd.getHeader();
		if (header != null) {
			if (isSessionHeaderExists(conn, session_id, ssd)) {
				updateSessionHeader(conn, session_id, ssd);
			} else {
				insertSessionHeader(conn, session_id, ssd);
			}
		}
	}

	private boolean isSessionHeaderExists(final Connection conn, final String session_id, final SessionSerializationData ssd) throws SQLException {
		final SessionSerializationHeaderData header = ssd.getHeader();
		boolean result = false;
		if (header != null) {
			PreparedStatement ps = null;
			ResultSet rs = null;
			try {
				ps = conn.prepareStatement(getSessionSQL);
				ps.setString(1, session_id);
				rs = ps.executeQuery();
				while (rs.next()) {
					result = true;
					break;
				}
				rs.close();
				rs = null;
				ps.close();
				ps = null;
			} finally {
				closeFinally(null, ps, rs);
			}
		}
		return result;
	}

	private void insertSessionHeader(final Connection conn, final String session_id, final SessionSerializationData ssd) throws SQLException {
		final SessionSerializationHeaderData header = ssd.getHeader();
		if (header != null) {
			PreparedStatement ps = null;
			try {
				ps = conn.prepareStatement(insertSessionSQL);
				ps.setString(1, session_id);
				ps.setString(2, header.tenant_id);
				ps.setString(3, header.user_id);
				ps.setString(4, header.node_id);
				ps.setString(5, header.webapp);
				ps.setTimestamp(6, header.creation_time);
				ps.setTimestamp(7, header.last_accessed_time);
				ps.setInt(8, header.max_inactive_interval);
				ps.setTimestamp(9, header.expiration_time);
				ps.setString(10, header.is_new);
				ps.setString(11, header.is_valid);
				ps.setTimestamp(12, header.this_accessed_time);
				ps.setInt(13, header.request_count);
				ps.setInt(14, ssd.getAttributes_count());
				ps.setInt(15, ssd.getAttributes_size());
				ps.setString(16, header.user_agent);
				ps.setString(17, header.remote_host);
				ps.setString(18, header.remote_addr);
				ps.setString(19, header.remote_port);
				ps.setString(20, header.remote_user);
				ps.executeUpdate();
				ps.close();
				ps = null;
			} finally {
				closeFinally(null, ps, null);
			}
		}
	}

	private void updateSessionHeader(final Connection conn, final String session_id, final SessionSerializationData ssd) throws SQLException {
		final SessionSerializationHeaderData header = ssd.getHeader();
		if (header != null) {
			PreparedStatement ps = null;
			try {
				ps = conn.prepareStatement(updateSessionSQL);
				ps.setString(1, header.tenant_id);
				ps.setString(2, header.user_id);
				ps.setString(3, header.node_id);
				ps.setString(4, header.webapp);
				ps.setTimestamp(5, header.creation_time);
				ps.setTimestamp(6, header.last_accessed_time);
				ps.setInt(7, header.max_inactive_interval);
				ps.setTimestamp(8, header.expiration_time);
				ps.setString(9, header.is_new);
				ps.setString(10, header.is_valid);
				ps.setTimestamp(11, header.this_accessed_time);
				ps.setInt(12, header.request_count);
				ps.setInt(13, ssd.getAttributes_count());
				ps.setInt(14, ssd.getAttributes_size());
				ps.setString(15, header.user_agent);
				ps.setString(16, header.remote_host);
				ps.setString(17, header.remote_addr);
				ps.setString(18, header.remote_port);
				ps.setString(19, header.remote_user);
				ps.setString(20, session_id);
				ps.executeUpdate();
				ps.close();
				ps = null;
			} finally {
				closeFinally(null, ps, null);
			}
		}
	}

	private void insertSessionModifiedAttributes(final Connection conn, final String session_id, final SessionAttributeRecord r) throws SQLException {
		PreparedStatement ps = null;
		try {
			final Timestamp now = new Timestamp(System.currentTimeMillis());
			ps = conn.prepareStatement(insertSessionAttributeSQL);
			ps.setString(1, session_id);
			ps.setString(2, r.key);
			ps.setTimestamp(3, now);
			ps.setInt(4, r.update_count);
			ps.setInt(5, r.data_length);
			ps.setString(6, r.data_checksum);
			ps.setString(7, r.data_type);
			int blobLength = 0;
			final byte[] blob = r.data;
			blobLength = blob.length;
			if (blobLength > 0) {
				ps.setBytes(8, blob);
			} else {
				ps.setBytes(8, null);
			}
			ps.executeUpdate();
			log.info("Adding attribute [" + r.key + "] for " + r.data_length + " bytes");
			ps.executeBatch();
			ps.close();
			ps = null;
		} finally {
			closeFinally(null, ps, null);
		}
	}

	private void updateSessionModifiedAttributes(final Connection conn, final String session_id, final SessionAttributeRecord r) throws SQLException {
		PreparedStatement ps = null;
		try {
			final Timestamp now = new Timestamp(System.currentTimeMillis());
			ps = conn.prepareStatement(updateSessionAttributeSQL);
			ps.setString(7, session_id);
			ps.setString(8, r.key);
			ps.setTimestamp(1, now);
			ps.setInt(2, r.update_count);
			ps.setInt(3, r.data_length);
			ps.setString(4, r.data_checksum);
			ps.setString(5, r.data_type);
			int blobLength = 0;
			final byte[] blob = r.data;
			blobLength = blob.length;
			if (blobLength > 0) {
				ps.setBytes(6, blob);
			} else {
				ps.setBytes(6, null);
			}
			ps.addBatch();
			log.info("Update attribute [" + r.key + "] for " + r.data_length + " bytes");
			ps.executeBatch();
			ps.close();
			ps = null;
			//b.free();
		} finally {
			closeFinally(null, ps, null);
		}
	}

	private boolean isSessionModifiedAttributeExists(final Connection conn, final String session_id, final SessionAttributeRecord r) throws SQLException {
		PreparedStatement ps = null;
		boolean result = false;
		ResultSet rs = null;
		try {
			ps = conn.prepareStatement(getSessionAttributeSQL);
			ps.setString(1, session_id);
			ps.setString(2, r.key);
			rs = ps.executeQuery();
			while (rs.next()) {
				result = true;
			}
			ps.close();
			ps = null;
		} finally {
			closeFinally(null, ps, rs);
		}
		return result;
	}

	protected void persistSessionModifiedAttributes(final Connection conn, final String session_id, final SessionSerializationData ssd) throws SQLException {
		final Collection<SessionAttributeRecord> modifiedSessionAttributeRecords = ssd.getModifiedSessionAttributeRecords();
		if (!modifiedSessionAttributeRecords.isEmpty()) {
			for (final SessionAttributeRecord r : modifiedSessionAttributeRecords) {
				if (isSessionModifiedAttributeExists(conn, session_id, r)) {
					updateSessionModifiedAttributes(conn, session_id, r);
				} else {
					insertSessionModifiedAttributes(conn, session_id, r);
				}
			}
		}
	}

	protected void persistSessionRemovedAttributes(final Connection conn, final String session_id, final SessionSerializationData ssd) throws SQLException {
		PreparedStatement ps = null;
		try {
			final Collection<String> removedSessionAttributes = ssd.getRemovedSessionAttributes();
			if (!removedSessionAttributes.isEmpty()) {
				ps = conn.prepareStatement(deleteSessionAttributeSQL);
				ps.setString(1, session_id);
				for (final String key : removedSessionAttributes) {
					ps.setString(2, key);
					ps.addBatch();
					log.info("Removing attribute [" + key + "]");
				}
				ps.executeBatch();
				ps.close();
				ps = null;
			}
		} finally {
			closeFinally(null, ps, null);
		}
	}

	private static final String expiredSessionKeysSQL = //
	"SELECT session_id" // 
			+ " FROM ps_foundation_session s" //
			+ " WHERE s.node_id = ?" //
			+ " AND s.webapp = ?" //
			+ " AND expiration_time < ?" //
			+ " ORDER BY expiration_time" //
	;

	@Override
	public Set<String> getExpiredSessionKeys() throws IOException {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		final HashSet<String> result = new HashSet<>();
		final String node_id = getNodeID();
		final String webapp = getWebapp();
		final Timestamp now = new Timestamp(System.currentTimeMillis());
		try {
			conn = getConnection();
			ps = conn.prepareStatement(expiredSessionKeysSQL);
			ps.setString(1, node_id);
			ps.setString(2, webapp);
			ps.setTimestamp(3, now);
			rs = ps.executeQuery();
			while (rs.next()) {
				final String session_id = rs.getString(1);
				result.add(session_id);
			}
			rs.close();
			rs = null;
			ps.close();
			ps = null;
			conn.close();
			conn = null;
		} catch (final DataSourceNotAvailableException e) {
			// ignore, this WebApp doesn't have a DataSource, or it is not started yet, so we can skip background processing
		} catch (final SQLException e) {
			final String message = "Problem getting expired session IDs for node: " + node_id + " and webapp: " + webapp;
			//log.error(message, e);
			throw new IOException(message, e);
		} finally {
			closeFinally(conn, ps, rs);
		}

		return result;
	}

	private void closeFinally(final Connection conn, final PreparedStatement ps, final ResultSet rs) {
		try {
			if (rs != null)
				rs.close();
		} catch (final SQLException e) {
			// ignore
		} finally {
			try {
				if (ps != null)
					ps.close();
			} catch (final SQLException e) {
				// ignore
			} finally {
				try {
					if (conn != null)
						conn.rollback();
				} catch (final SQLException e) {
					// ignore
				} finally {
					try {
						if (conn != null)
							conn.setAutoCommit(true);
					} catch (final SQLException e) {
						// ignore
					} finally {
						try {
							if (conn != null)
								conn.close();
						} catch (final SQLException e) {
							// ignore
						}
					}
				}
			}
		}
	}

	private volatile DataSource dataSource;
	private final Object lock = new Object();

	private DataSource getDataSource() throws SQLException {
		BasicDataSource result = null;				
		if (result == null) {
			synchronized (lock) {
				if (result == null) {
					result = new BasicDataSource();
					result.setDriverClassName("org.mariadb.jdbc.Driver");
					result.setUrl("jdbc:mariadb://localhost:3306/sadb");
					result.setUsername("root");
					result.setPassword("root");        
					return result;
				}
			}
		}
		return result;
	}

	private Connection getConnection() throws SQLException {
		final DataSource ds = getDataSource();
		final Connection result = ds.getConnection();
		return result;
	}

	@Override
	public boolean isStoreAvailable() {
		boolean result;
		try {
			getDataSource();
			result = true;
		} catch (final Exception e) {
			result = false;
		}
		return result;
	}
	
	public static void main(String[] args){
		DBFoundationSessionStore s = new DBFoundationSessionStore();
		try {
			Connection c = s.getConnection();
			Statement statement = c.createStatement();
			statement.executeUpdate("insert into ps_foundation_session(session_id) value('F85F58D97852EF7A497C80E868CDCAA9')");
			
			c.commit();
			
			ResultSet rs = statement.executeQuery("select count(*) from ps_foundation_session");
			if(rs.next()){
				log.info("DataSource works!!");
			}
			
			statement.executeUpdate("delete from ps_foundation_session");
			c.commit();

			c.setAutoCommit(true);
			
			statement.close();
			statement=null;
			c.close();
			c = null;			
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
