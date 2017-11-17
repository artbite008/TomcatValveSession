/* -----------------------------------------------------------------------------
 * Copyright (c) 2008 Plateau Systems, Ltd.
 *
 * This software and documentation is the confidential and proprietary
 * information of Plateau Systems.  Plateau Systems
 * makes no representation or warranties about the suitability of the
 * software, either expressed or implied.  It is subject to change
 * without notice.
 *
 * U.S. and international copyright laws protect this material.  No part
 * of this material may be reproduced, published, disclosed, or
 * transmitted in any form or by any means, in whole or in part, without
 * the prior written permission of Plateau Systems.
 * -----------------------------------------------------------------------------
 */

package org.apache.catalina.session;

import java.net.InetAddress;
import java.util.Locale;

/**
 * java.net utility methods. 
 *
 * @author jim631@sina.com
 */
public class NetUtil {

	/**
	 * Gets the full host name (including the domain, if available). 
	 */
	protected static String getCannonicalHostName() {
		String host;
		// JDK 1.3 version is getHostName() - 1.4 has getCannonicalHostName()
		try {
			final InetAddress address = InetAddress.getLocalHost();
			host = address.getCanonicalHostName();
			if (host.equals(address.getHostAddress()))
				host = address.getHostName();
		} catch (final Throwable t) {
			try {
				host = InetAddress.getLocalHost().getHostName();
			} catch (final Throwable t2) {
				host = "localhost";
			}
		}
		host = host.toLowerCase(Locale.getDefault());
		return host;
	}

	/**
	 * Gets the host name (without the domain). 
	 */
	public static String getHostName() {
		String hostname = getCannonicalHostName();
		final int idx = hostname.indexOf(".");
		if (idx >= 0)
			hostname = hostname.substring(0, idx);
		return hostname;
	}
}
