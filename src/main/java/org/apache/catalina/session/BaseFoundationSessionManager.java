package org.apache.catalina.session;

import java.io.IOException;

import javax.management.ObjectName;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.Session;
import org.apache.catalina.Store;
import org.apache.catalina.connector.Request;

public abstract class BaseFoundationSessionManager extends ManagerBase {
	protected  ObjectName storeMBeanName;

	@Override
	protected void destroyInternal() throws LifecycleException {
		super.destroyInternal();
		unregister(storeMBeanName);
	}


	abstract public void processSessionChanges(final Session session, final Request request) throws IOException;

	abstract  public Store getStore();

	abstract public boolean evict(final String sessionID);

}
