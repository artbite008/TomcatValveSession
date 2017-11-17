package org.apache.catalina.session;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.Store;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;

import java.util.logging.Logger;

/**
 * {@link org.apache.catalina.Session} {@link org.apache.catalina.Manager} that can read a session from a {@link org.apache.catalina.Store} if it is not found in memory
 * and write sessions back out to a Store after each {@link org.apache.catalina.connector.Request}.
 *
 * @author jim631@sina.com
 */
public class FoundationSessionManager extends BaseFoundationSessionManager {

  private static final Logger log = Logger.getLogger(FoundationSessionManager.class.getName());

  private static final String NAME = FoundationSessionManager.class.getSimpleName();
  private static final String INFO = NAME + "/1.0";

  //private static final boolean isFoundationSessionManagerEnabled = System.getProperty("catalina.enableFoundationSessionManager", "false").equals("true");
  private static final boolean isFoundationSessionManagerEnabled =true;
  private static final boolean isFoundationSessionManagerTestModeEnabled = System.getProperty("catalina.enableFoundationSessionManagerTestMode", "false")
      .equals("true");

  private static final boolean isInactiveSessionEvictionEnabled = !System.getProperty("catalina.enableInactiveSessionEviction", "true").equals("false");
  private static final int inactiveSessionEvictionTimeoutSeconds = FoundationUtil.getInteger("catalina.inactiveSessionEvictionTimeoutSeconds", 600); // ten minutes
  
  //private static final boolean enableSfedisZookeeper = System.getProperty(SfedisConstants.ENABLE_SFEDIS_ZOOKEEPER).equals("true");
  //private static final String zookeeperAddress = System.getProperty(SfedisConstants.ZOOKEEPER_ADDRESS);
  
  private FoundationSessionStore store;
  private final Object maxActiveUpdateLock;

  //private String zookeeperAddress;
  //private String enableSfedisZookeeper;

  public FoundationSessionManager() {
    super();

    //SfedisSetting.setZkAddress(zookeeperAddress);
    //SfedisSetting.setEnableSfedisZookeeper(enableSfedisZookeeper);

    AsyncFoundationSessionStoreWrapper store = new AsyncFoundationSessionStoreWrapper(new DBFoundationSessionStore());
    setStore(store);

    registerMBean(store);

    Object lock;
    try {
      final Field maxActiveUpdateLockField = ManagerBase.class.getDeclaredField("maxActiveUpdateLock");
      lock = FoundationUtil.getFieldValue(this, maxActiveUpdateLockField);
    } catch (NoSuchFieldException | SecurityException e) {
      //log.error("Could not obtain super-class's maxActiveUpdateLock field", e);
      lock = new Object();
    }
    maxActiveUpdateLock = lock;

    if (isFoundationSessionManagerEnabled) {
      store.sessionCache().startCleanUpJob();
      log.info("startCleanUpJob started.");
    }

  }

  @Override
  public String getName() {
    return NAME;
  }

  //@Override
  public String getInfo() {
    return INFO;
  }

  private boolean isFoundationSessionManagerEnabledAndDataSourceAvailable() {
    final boolean result;
    if (isFoundationSessionManagerEnabled) {
      result = store.isStoreAvailable();
    } else {
      result = false;
    }
    return result;
  }

  @Override
  public void load() throws ClassNotFoundException, IOException {
    return;
  }

  @Override
  public void unload() throws IOException {
    return;
  }

  /**
   * Return the Store object which manages persistent Session storage.
   */
  @Override
  public Store getStore() {
    return store;
  }

  /**
   * Set the Store object which will manage persistent Session storage.
   *
   * @param store the associated Store
   */
  public void setStore(final Store store) {
    this.store = (FoundationSessionStore) store;
    store.setManager(this);
  }

  @Override
  public synchronized void startInternal() throws LifecycleException {
    super.startInternal();
    for (final Valve valve : getContainer().getPipeline().getValves()) {
      if (valve instanceof FoundationSessionManagerValve) {
        final FoundationSessionManagerValve trackerValve = (FoundationSessionManagerValve) valve;
        trackerValve.setManager(this);
        log.info("Attached to " + FoundationSessionManagerValve.class.getSimpleName());
        break;
      }
    }
    store.start();
    if (isFoundationSessionManagerEnabled) {
      log.info(NAME + " is enabled");
    } else {
      log.info(NAME + " is disabled");
    }
    if (store.isStoreAvailable()) {
      log.info("Session store is available");
    } else {
      log.info("Session store is not available");
    }
    log.info("Expiring sessions after " + maxInactiveInterval + " seconds");
    setState(LifecycleState.STARTING);
  }

  @Override
  public void stopInternal() throws LifecycleException {
    super.stopInternal();
    store.stop();
    setState(LifecycleState.STOPPING);
  }

  /**
   * Add this Session to the set of active Sessions for this Manager if it is not already in the cache.
   *
   * @param session Session to be added
   * @return session already in the map under the given session's ID, or null if the Session was added
   */
  public Session putIfAbsent(final Session session) {
    final ConcurrentMap<String, Session> cache = (ConcurrentMap<String, Session>) sessions;
    final String id = session.getIdInternal();
    final Session result = cache.putIfAbsent(id, session);
    if (result == null) {
      // since we know we put a new element into the map
      final int size = getActiveSessions();
      if (size > maxActive) {
        synchronized (maxActiveUpdateLock) {
          if (size > maxActive) {
            maxActive = size;
          }
        }
      }
    }
    return result;
  }

  @Override
  public Session findSession(final String id) throws IOException {
    if (id == null)
      return null;
    Session result = super.findSession(id);
    if (isFoundationSessionManagerEnabledAndDataSourceAvailable()) {
      if (isFoundationSessionManagerTestModeEnabled && result != null && result.getLastAccessedTime() + 5000 < System.currentTimeMillis()) {
        evict(id);
        result = null;
      }
      if (result == null) { // try loading this session from the persistent store
        StandardSession s;
        try {
          s = store.load(id);
        } catch (final ClassNotFoundException e) {
          final String message = "Unable to load session: " + id;
          //log.error(message, e);
          throw new IOException(message, e);
        }
        if (s != null && !s.isValid()) {
          log.info("Loaded session marked as invalid, forcing removal of the session from the store: " + id);
          store.remove(id);
          s = null;
        }
        if (s != null) {
          final Session oldSession = putIfAbsent(s); // need to add the session to the list of managed sessions before activation to prevent a StackOverflow findSession()->activate()->SessionActivationListener->findSession()
          if (oldSession == null) {
            s.activate();
            result = s;
          } else {
            // return the one in the map
            result = oldSession;
          }
        }
      }
    }
    return result;
  }

  @Override
  public void remove(final Session session, final boolean update) {
    try {
      super.remove(session, update);
    } finally {
      if (isFoundationSessionManagerEnabledAndDataSourceAvailable()) {
        final String id = session.getIdInternal();
        try {
          store.remove(id);
        } catch (final IOException e) {
          final String message = "Unable to remove session: " + id;
          //log.error(message, e);
          throw new RuntimeException(message, e); // would have preferred to throw an IOException, but cannot becase the parent's method signature doesn't allow it
        }
      }
    }
  }

  public void remove(final String id) throws IOException {
    if (id != null) {
      try {
        sessions.remove(id);
      } finally {
        if (isFoundationSessionManagerEnabledAndDataSourceAvailable()) {
          store.remove(id);
        }
      }
    }
  }

  @Override
  public void changeSessionId(final Session session) {
    //String oldId = session.getIdInternal();
    super.changeSessionId(session);
    //String newId = session.getIdInternal();
    // TODO maybe rename session with oldId to newId in the DB
  }

  public void processSessionChanges(final Session session, final Request request) throws IOException {
	log.info("Manager processSessionChanges start.");
    if (isFoundationSessionManagerEnabledAndDataSourceAvailable()) {
      try {
        store.processChanges(session, request);
      } catch (final IOException e) {
        final String id = session == null ? "null" : session.getIdInternal();
        final String message = "Unable to save session data: " + id;
        //log.error(message, e);
        throw new IOException(message, e);
      }
    }
    log.info("Manager processSessionChanges end.");
  }

  /**
   * Invalidate all sessions that have expired.
   * <p/>
   * Also looks for sessions we should/can evict due to inactivity.
   * <p/>
   * (Copied from super class and modified so that we can hook into the same loop and gain efficiency in the background thread.)
   */
  @Override
  public void processExpires() {
    if (isFoundationSessionManagerEnabled) {
      final long timeNow = System.currentTimeMillis();
      final long cutoffTime = getSessionEvictionCutoffTime(timeNow);
      final Session sessions[] = findSessions();

      int expiredHere = 0;
      int evictedHere = 0;

      //if (log.isTraceEnabled())
        log.info("Start expire sessions " + getName() + " at " + timeNow);

      for (int i = 0; i < sessions.length; i++) {
        final Session session = sessions[i];
        if (session != null) {
          // note this will automatically evict sessions BECOMING invalid
          final boolean sessionIsInvalid = !session.isValid();
          if (sessionIsInvalid) {
            expiredHere++;

            // make sure that the session if marked invalid really IS out of the map
            final String sessionID = session.getIdInternal();
            evict(sessionID);
          } else if (evictSessionIfInactive(session, cutoffTime)) {
            // evict the session if it has been inactive
            evictedHere++;
          }
        }
      }

      // remove any expired sessions that are in the store only and not in the cache
      final int expiredStoredSessions = processExpiredStoredSessions();

      // mark end of time for the background process
      final long timeEnd = System.currentTimeMillis();
      //if (log.isDebugEnabled() && (expiredHere > 0 || expiredStoredSessions > 0 || evictedHere > 0)) {
        log.info("\tsessioncount " + sessions.length);
        log.info("\tExpired sessions: " + expiredHere + ", expired stored sessions: " + expiredStoredSessions + ", evicted sessions: " + evictedHere);
      //}
      //if (log.isTraceEnabled())
        log.info("End expire sessions " + getName() + " processingTime " + (timeEnd - timeNow));
      processingTime += (timeEnd - timeNow);
    } else {
      super.processExpires();
    }
  }

  private int processExpiredStoredSessions() {
    int result = 0;
    try {
      final Set<String> expiredSessionKeys = store.getExpiredSessionKeys();
      for (final String id : expiredSessionKeys) {
        try {
          //if (log.isDebugEnabled())
            log.info("Expiring stored session " + id);
          // attempt to do the load; if the session was stored invalid, or became invalid, 
          // the swapIn() will handle store and cache removal and return null
          final Session session = findSession(id);
          if (session == null) {
            //if (log.isDebugEnabled())
              log.info("Session " + id + " was expired and removed from the store");
            result++;
          } else {
            // if we're here, the session was in the session cache, so if it is truly invalid, we want to ensure it is booted now
            final boolean sessionIsInvalid = !session.isValid();
            if (sessionIsInvalid) {
              log.info("Session " + id + " was expired per the store, but was still in the session cache - evicting and removing now");
              remove(id);
              result++;
            }
          }
        } catch (final Exception e) {
          try {
            remove(id);
            result++;
          } catch (final Exception e2) {
            //log.error("Problem processing expired session: " + id, e2);
          }
        }

      }
    } catch (final Exception e) {
      //log.error("Problem processing expired sessions", e);
    }
    return result;
  }

  /**
   * Evict a session from the in-memory cache.  If the session gets evicted and is later
   * requested, a reload from the store will be triggered.
   *
   * @param sessionID id of session to evict
   */
  public boolean evict(final String sessionID) {
    final Session session = sessions.remove(sessionID);

    final boolean result = session != null;
    if (result) {
      log.info("Evicting session by id " + sessionID + ", old value=" + session);
    }
    //also remove from the cache.
    log.info("evict(): Removed the session from Session Cache: " + sessionID);
    store.sessionCache().remove(sessionID);
    return result;
  }

  private long getSessionEvictionCutoffTime(final long fromTime) {
    final long result;
    if (isInactiveSessionEvictionEnabled && inactiveSessionEvictionTimeoutSeconds > 0) {
      result = fromTime - inactiveSessionEvictionTimeoutSeconds * 1000L;
    } else {
      result = Long.MIN_VALUE;
    }
    return result;
  }

  /**
   * Iterate the sessions and for any sessions that have been inactive
   * for more than the inactiveSessionEvictionTimeoutSeconds, evict them.
   * <p/>
   * see PersistentValve.isSessionStale()
   */
  public void evictInactiveSessions() {
    if (isInactiveSessionEvictionEnabled) {
      final long currentTime = System.currentTimeMillis();
      final long cutoffTime = getSessionEvictionCutoffTime(currentTime);
      for (final Map.Entry<String, Session> entry : sessions.entrySet()) {
        final Session session = entry.getValue();
        evictSessionIfInactive(session, cutoffTime);
      }
    }
  }

  private boolean evictSessionIfInactive(final Session session, final long cutoffTime) {
    final long lastAccessedTime = session.getThisAccessedTimeInternal(); // don't need a validation check here!
    final boolean result;
    if (lastAccessedTime <= cutoffTime && session.isValid()) {
      final String sessionID = session.getIdInternal();

      // store the session data if anything is dirty
      try {
        store.flush(session.getId());
      } catch (final IOException e) {
        //log.error("Unable to save session: " + sessionID, e);
      }

      // evict the session if successful
      log.info("Session " + sessionID + " evicted, inactive for > " + inactiveSessionEvictionTimeoutSeconds + " second(s)");
      result = evict(sessionID);
    } else {
      result = false;
    }
    return result;
  }

  private ObjectName storeMBeanName;

  @Override
  protected void initInternal() throws LifecycleException {
    super.initInternal();
    final String jmxBeanName = getObjectNameKeyProperties() + ",store=" + store.getClass().getSimpleName();
    storeMBeanName = register(store, jmxBeanName);
  }

  @Override
  protected void destroyInternal() throws LifecycleException {
    super.destroyInternal();
    unregister(storeMBeanName);
  }


  public void registerMBean(AsyncFoundationSessionStoreWrapper store) {
    final String name = "com.successfactors.session:type=SessionStoreMonitoringMBean";
    try {
      final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      final ObjectName beanName = new ObjectName(name);
      mbs.registerMBean(store.getFoundationSessionStoreMonitoring(), beanName);
    } catch (final InstanceAlreadyExistsException e) {
      //just log it.
      log.info("FoundationSessionStoreMonitoringMBean already registered: " + e.getMessage());
    } catch (final MalformedObjectNameException | MBeanRegistrationException | NotCompliantMBeanException e) {
      throw new RuntimeException("can not register  FoundationSessionStoreMonitoringMBean", e);
    }
  }

//  public void setZookeeperAddress(String zookeeperAddress) {
//    SfedisSetting.setZkAddress(zookeeperAddress);
//  }
//
//  public String getZookeeperAddress() {
//    return SfedisSetting.getZkAddress();
//  }
//
//  public String isEnableSfedisZookeeper() {
//    return SfedisSetting.isEnableSfedisZookeeper() == true ? "true" : "false";
//  }
//
//  public void setEnableSfedisZookeeper(String enableSfedisZookeeper) {
//    SfedisSetting.setEnableSfedisZookeeper(enableSfedisZookeeper.equals("true") ? true : false);
//  }
}
