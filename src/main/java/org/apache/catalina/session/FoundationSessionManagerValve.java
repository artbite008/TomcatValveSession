package org.apache.catalina.session;

import java.io.IOException;

import javax.servlet.ServletException;

import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import java.util.logging.Logger;

public class FoundationSessionManagerValve extends ValveBase{
	
	private static final Logger log = Logger.getLogger(FoundationSessionManagerValve.class.getName());
	
	private static final String NAME = FoundationSessionManagerValve.class.getSimpleName();
	private static final String INFO = NAME + "/1.0";
	
	public FoundationSessionManagerValve() {
		super();
	}
	
	public String getInfo() {
		return INFO;
	}

	private FoundationSessionManager manager;
	
	public void setManager(final FoundationSessionManager manager) {
		this.manager = manager;
	}

	@Override
	public void invoke(final Request request, final Response response) throws IOException, ServletException {
		try {
			final Valve nextValve = getNext();
			nextValve.invoke(request, response);
		} finally {
//			try {
//				final Session session = request.getSessionInternal(false);
//				manager.processSessionChanges(session, request);
//			} catch (final Exception e) {
//				log.info("Problem saving session:" + e.getMessage());
//			}
		}
	}
	
}