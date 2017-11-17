package org.apache.catalina.session;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;

import javax.servlet.http.HttpServletRequest;

import java.util.logging.Logger;

/**
 * General util methods.
 *
 * @author jim631@sina.com
 */
public class FoundationUtil {
	private static final Logger log = Logger.getLogger(FoundationUtil.class.getName());

	public static String truncate(final Object o, final int length) {
		final String result;
		if (o == null) {
			result = null;
		} else {
			result = truncate(o.toString(), length);
		}
		return result;
	}

	public static String truncate(final String s, final int length) {
		final String result;
		if (s == null) {
			result = null;
		} else if (s.length() <= length) {
			result = s;
		} else {
			final Charset utf8 = Charset.forName("UTF-8");
			final byte[] b = s.getBytes(utf8);
			if (b.length <= length) {
				result = s;
			} else {
				result = new String(b, 0, length, utf8);
			}
		}
		return result;
	}

	/**
	 * Attempt to retrieve the property value named with propertyName and parse the value
	 * as an integer.  If the property value cannot be parsed or the system property has no
	 * value, use the provided default value.
	 *
	 * @param propertyName name of of property to look up
	 * @param defaultValue value to assume if property is not specified or cannot be prased
	 * @return int interpretation of the system property value, OR defaultValue if system property is not specified/unparseable
	 */
	public static int getInteger(final String propertyName, final int defaultValue) {
		final String propertyValue = System.getProperty(propertyName, String.valueOf(defaultValue));
		int result;
		try {
			result = Integer.parseInt(propertyValue);
		} catch (final NumberFormatException e) {
			//log.error("Could not parse property " + propertyName + " value " + propertyValue + "; using default value of " + String.valueOf(defaultValue));
			result = defaultValue;
		}
		return result;
	}

	/**
	 * Try the provided {@link java.util.concurrent.Callable} process, repeating up to 3x with 100ms wait in between tries if
	 * any of the provided retryIfThrown exceptions occur.
	 */
	@SafeVarargs
	public static <V> V tryMultipleTimes(final Callable<V> process, final Class<? extends Exception>... retryIfThrown) throws Exception {
		final V result = tryMultipleTimes(process, 3, 100, retryIfThrown);
		return result;
	}

	/**
	 * Try the provided {@link java.util.concurrent.Callable} process, repeating up to maximumTries times with pauseBetweenTriesMS wait in between tries if
	 * any of the provided retryIfThrown exceptions occur.
	 */
	@SafeVarargs
	public static <V> V tryMultipleTimes(final Callable<V> process, final int maximumTries, final long pauseBetweenTriesMS,
			final Class<? extends Exception>... retryIfThrown) throws Exception {
		V result = null;
		int attempts = maximumTries;
		while (attempts > 0) {
			try {
				result = process.call();
				break;
			} catch (final Exception e) {
				Exception exceptionToThrow = e;
				final Class<?> exceptionClass = e.getClass();
				for (final Class<? extends Exception> retryIf : retryIfThrown) {
					if (retryIf.isAssignableFrom(exceptionClass)) {
						attempts--;
						if (attempts > 0) {
							Thread.sleep(pauseBetweenTriesMS);
							exceptionToThrow = null;
						}
						break;
					}
				}
				if (exceptionToThrow != null)
					throw exceptionToThrow;
			}
		}
		return result;
	}

	/**
	 * For non-ConcurrentMaps, this tries atomically to put a value into the map
	 * for a given key only if that key doesn't already exist, and returns the
	 * value that should be in the map for that key at the time of the request,
	 * or the provided value if that key was not already in the map.
	 * <p/>
	 * Note it is acknowledged that this is not bullet-proof from a concurrency standpoint;
	 * this is here only to make a best-attempt for non-concurrent maps.
	 */
	public static <K, V> V putIfAbsentAndGet(final Map<K, V> map, final K key, final V value) {
		if (map == null)
			throw new NullPointerException("Cannot putIfAbsentAndGet on a null map");
		if (!map.containsKey(key)) {
			synchronized (map) {
				if (!map.containsKey(key)) {
					map.put(key, value);
				}
			}
		}
		final V result = map.get(key);
		return result;
	}

	public static class OldTCCL {
		final ClassLoader oldTCCL;
		final boolean tcclChanged;
		final Logger log;

		OldTCCL() {
			super();
			this.oldTCCL = null;
			this.tcclChanged = false;
			this.log = null;
		}

		OldTCCL(final ClassLoader oldTCCL, final ClassLoader newTCCL, final Logger log) {
			super();
			this.oldTCCL = oldTCCL;
			this.tcclChanged = true;
			this.log = log;
			log.info("Changing TCCL from " + oldTCCL + " to " + newTCCL);
		}

		public void restore() {
			if (tcclChanged) {
				Thread.currentThread().setContextClassLoader(oldTCCL);
				log.info("Restoring TCCL from to " + oldTCCL);
			}
		}
	}

	private static boolean isParentOf(final ClassLoader parent, final ClassLoader child) {
		// return true if the classloader given can be traced back to the org.apache.catalina.loader.StandardClassLoader
		boolean result = false;
		if (parent != null && child != null) {
			ClassLoader cl = child.getParent();
			while (cl != null) {
				if (cl == parent) {
					result = true;
					break;
				}
				cl = cl.getParent();
			}
		}
		return result;
	}

	public static int[] integerCollectionToIntArray(final Collection<Integer> values) {
		final int[] result = new int[values.size()];
		int i = 0;
		for (final Integer value : values) {
			result[i++] = value.intValue();
		}
		return result;
	}

	public static Collection<Integer> intArrayToCollection(final int[] values) {
		final ArrayList<Integer> result = new ArrayList<>(values.length);
		for (int i = 0; i < values.length; i++) {
			result.add(Integer.valueOf(values[i]));
		}
		return result;
	}

	public static int[] getIntArrayFromCommaDelimitedString(final String s) throws NumberFormatException {
		final ArrayList<Integer> values = new ArrayList<>();
		final StringTokenizer st = new StringTokenizer(s, ",");
		while (st.hasMoreTokens()) {
			final String token = st.nextToken();
			final Integer i = Integer.valueOf(token);
			values.add(i);
		}
		final int[] result = FoundationUtil.integerCollectionToIntArray(values);
		return result;
	}

	public static String buildCommaDelimitedString(final int[] array) {
		final StringBuilder sb = new StringBuilder();
		if (array != null) {
			for (int i = 0; i < array.length; i++) {
				if (i > 0)
					sb.append(',');
				sb.append(array[i]);
			}
		}
		return sb.toString();
	}

	/**
	 * Generate a stack trace string for the given thread (or the current thread
	 * if null).  This stack trace simulates that which would be output by an
	 * exception stack trace but does not use an exception to get it.  The stack
	 * trace also will not include the call to this method nor Thread.getStackTrace().
	 *
	 * @param t               thread to dump the stack from, or null if currentThread() is desired
	 * @param stackTraceDepth depth to report the stack trace to, 0 == as deep as it goes
	 * @return string containing stack trace output
	 */
	public static String getThreadStackTrace(final Thread t, final int stackTraceDepth) {
		final Thread targetThread = t == null ? Thread.currentThread() : t;
		final StackTraceElement[] stes = targetThread.getStackTrace();
		final StringBuilder sb = new StringBuilder();
		// start loop @2 to skip Thread.getStackTrace() and this method
		for (int i = 2; i < stes.length; i++) {
			sb.append('\t').append(stes[i].toString()).append('\n');
			if (stackTraceDepth > 0 && i > stackTraceDepth)
				break;
		}
		return sb.toString();
	}

	/**
	 * A {@link java.security.PrivilegedAction} that sets the {@link java.lang.reflect.AccessibleObject#setAccessible(boolean)} flag that will allow access protected and private members of a {@link Class}.
	 */
	private static final class PrivilegedAccess implements PrivilegedExceptionAction<Object> {
		private final AccessibleObject member;

		private PrivilegedAccess(final AccessibleObject member) {
			this.member = member;
		}

		@Override
		public Object run() {
			member.setAccessible(true);
			return null;
		}
	}

	/**
	 * Make an {@link java.lang.reflect.AccessibleObject} accessible by calling its {@link java.lang.reflect.AccessibleObject#setAccessible(boolean)} with <code>true</code> using
	 * the {@link java.security.AccessController#doPrivileged(java.security.PrivilegedActionException)}.
	 */
	public static void setAccessible(final AccessibleObject member) {
		try {
			AccessController.doPrivileged(new PrivilegedAccess(member));
		} catch (final PrivilegedActionException e) {
			final Throwable cause = e.getException();
			if (cause instanceof RuntimeException)
				throw (RuntimeException) cause;
			throw new RuntimeException("Problem setting member accessible: " + member, cause);
		}
	}

	/**
	 * A version of {@link java.lang.reflect.Field#get} that allows access to private members.
	 */
	public static Object getFieldValue(final Object object, final Field field) {
		final Object result;
		try {
			if (!field.isAccessible())
				setAccessible(field);
			result = field.get(object);
		} catch (final IllegalAccessException e) {
			throw new RuntimeException("Problem getting value from " + field, e);
		}
		return result;
	}


	/**
	 * Returns an unmodifiable list of the specified items.
	 * This will copy the collection into a new list and return
	 * an unmodifiable view of that list.
	 */
	@SafeVarargs
	public static <T> List<T> unmodifiableList(final T... items) {
		final List<T> result;
		if (items == null) {
			result = Collections.unmodifiableList(new ArrayList<T>());
		} else {
			result = Collections.unmodifiableList(new ArrayList<T>(Arrays.asList(items)));
		}
		return result;
	}

	public static  boolean isStaticResource(final HttpServletRequest req) {
		return req!=null &&  //
				(req.getRequestURI().contains(".js;mod=") //
				|| req.getRequestURI().endsWith(".swf") //
				|| req.getRequestURI().contains(".css.swf;mod=") //
				|| req.getRequestURI().contains(".css;mod=") //
				|| req.getRequestURI().contains(".gif;mod=") //
				|| req.getRequestURI().contains(".png;mod=") //
				|| req.getRequestURI().contains(".jpg;mod=") //
				|| req.getRequestURI().contains(".jpeg;mod=")
				);

	}


}
