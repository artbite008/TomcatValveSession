package org.apache.catalina.session;

import java.sql.SQLException;

public class DataSourceNotAvailableException extends SQLException {

	private static final long serialVersionUID = 1L;

	public DataSourceNotAvailableException() {
		super();
	}

	public DataSourceNotAvailableException(final String message, final Throwable cause) {
		super(message, cause);
	}

	public DataSourceNotAvailableException(final String message) {
		super(message);
	}

	public DataSourceNotAvailableException(final Throwable cause) {
		super(cause);
	}
}
