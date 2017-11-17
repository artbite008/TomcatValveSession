package org.apache.catalina.session;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ConcurrentModificationException;
import java.util.concurrent.Callable;

import org.apache.catalina.Context;
import org.apache.catalina.Loader;
import org.apache.catalina.Manager; 
import org.apache.catalina.util.CustomObjectInputStream;

import java.util.logging.Logger;


/**
 * Description:  SerializationUtils
 *
 * @author jim631@sina.com
 */
public class SerializationUtils {

	protected static final String NOT_SERIALIZED =
			"___NOT_SERIALIZABLE_EXCEPTION___";

	public static void writeObject(String name, final Object _obj, final ObjectOutputStream stream, Logger logger) throws IOException {
		Object obj = _obj;
		if (obj == null)
			obj = NOT_SERIALIZED;  //make sure it always write something to the slot.

		if (!(obj instanceof Serializable)) {
			if (logger != null)
				logger.info("Object is not serializable, name=" + name + ",value=" + obj);
			stream.writeObject(NOT_SERIALIZED);
		} else {
			try {
				stream.writeObject(obj);
			} catch (NotSerializableException e) {
				if (logger != null)
					//logger.info("Object is not serializable, name=" + name + ",value=" + obj, e);
					logger.info("Object is not serializable, name=" + name + ",value=" + obj);
				stream.writeObject(NOT_SERIALIZED);
			}
		}
	}

	public static Object readObject(String name, ObjectInputStream stream, Logger logger) throws ClassNotFoundException, IOException {
		Object value = stream.readObject();
		if ((value instanceof String) && (value.equals(NOT_SERIALIZED)))
			return null;
		else {
		    logger.info("  readObjectData '" + name + "' with value '" + value + "'");
			return value;
		}
	}

//	public static int readInt(String name, ObjectInputStream stream, Log logger) throws ClassNotFoundException, IOException {
//		Object o = stream.readObjectData();
//		int value= o==null?0:((Integer)o).intValue();
//		if (logger!=null && logger.isDebugEnabled())
//			logger.debug("  read integer '" + name + "' with value '" + value + "'");
//		return value;
//	}
//
//
//	public static long readLong(String name, ObjectInputStream stream, Log logger) throws ClassNotFoundException, IOException {
//		Object o = stream.readObjectData();
//		long value= o==null?0:((Long)o).longValue();
//		if (logger!=null && logger.isDebugEnabled())
//			logger.debug("  read long '" + name + "' with value '" + value + "'");
//		return value;
//	}

//	public static boolean readBoolean(String name, ObjectInputStream stream, Log logger) throws ClassNotFoundException, IOException {
//		Object o = stream.readObjectData();
//		boolean value= o==null?false:((Boolean)o).booleanValue();
//		if (logger!=null && logger.isDebugEnabled())
//			logger.debug("  read boolean '" + name + "' with value '" + value + "'");
//		return value;
//	}

//	public static ClassLoader getWebappClassLoader(Manager manager) {
//		final ClassLoader result;
//
//		final Context context = manager.getContext();
//		if (context == null) {
//			result = null;
//		} else {
//			final Loader loader = context.getLoader();
//			if (loader == null) {
//				result = null;
//			} else {
//				result = loader.getClassLoader();
//			}
//		}
//
//		return result;
//	}

	//requires the Object to be serializable.
	public static byte[] serialize(final Object o) throws IOException {
		final Callable<byte[]> process = new Callable<byte[]>() {
			@Override
			public byte[] call() throws IOException {
				final ByteArrayOutputStream bos = new ByteArrayOutputStream();
				final ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(bos));
				try {
					oos.writeObject(o);
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
			throw new IOException("Could not serialize data", e);
		}
	}


	public static Object createObjectFromData(final Manager manager, final byte[] data) throws IOException {
		if (data == null) {
			return null;
		}
		final Object result;
		try {
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			//if (loader == null)
			//	loader = getWebappClassLoader(manager);
			final ByteArrayInputStream bis = new ByteArrayInputStream(data);
			final ObjectInputStream ois = new CustomObjectInputStream(bis, loader);
			result = ois.readObject();
			ois.close();
		} catch (final ClassNotFoundException e) {
			throw new IOException("Problem deserializing", e);
		}
		return result;
	}


	/**
	 * Write (serialize) the object to byte array by calling  the  ManualSerializable.writeObjectData
	 *
	 * @param obj
	 * @return
	 * @throws java.io.IOException
	 */
	public static byte[] writeObject(final ManualSerializable obj) throws IOException {
		final Callable<byte[]> process = new Callable<byte[]>() {
			@Override
			public byte[] call() throws IOException {
				final ByteArrayOutputStream bos = new ByteArrayOutputStream();
				final ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(bos));
				try {
					obj.writeObjectData(oos);
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


	/**
	 * read (Deserialize) the object from data by using ManualSerializable.readObjectData
	 *
	 * @param manager
	 * @param data
	 * @param result
	 * @return
	 * @throws ClassNotFoundException
	 * @throws java.io.IOException
	 */
	public static <T extends ManualSerializable> T readObject(Manager manager, byte[] data, T result)
			throws ClassNotFoundException, IOException {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		//if (loader == null)
		//	loader = SerializationUtils.getWebappClassLoader(manager);
		final ByteArrayInputStream bis = new ByteArrayInputStream(data);
		final ObjectInputStream ois = new CustomObjectInputStream(bis, loader);
		result.readObjectData(ois);
		ois.close();
		return result;

	}


	/**
	 * Make a clone of the ManualSerializable
	 *
	 * @param manager
	 * @param obj
	 * @param clone
	 * @param <T>
	 * @return
	 * @throws ClassNotFoundException
	 * @throws java.io.IOException
	 */

	public static <T extends ManualSerializable> T clone(Manager manager, final T obj, T clone)
			throws ClassNotFoundException, IOException {
		byte[] bytes = writeObject(obj);
		clone = readObject(manager, bytes, clone);
		return clone;
	}

	/**
	 * Clone the SSD.
	 *
	 * @param manager
	 * @param ssd
	 * @return
	 * @throws ClassNotFoundException
	 * @throws java.io.IOException
	 */
	public static SessionSerializationData cloneSessionSerializationData(Manager manager, final SessionSerializationData ssd)
			throws ClassNotFoundException, IOException {
		SessionSerializationData cloneSSD = new SessionSerializationData(ssd.getLogger(), ssd.getId());
		cloneSSD = clone(manager, ssd, cloneSSD);

		return cloneSSD;

	}

	/*This method requies everying to be serializable.*/

	/**
	 * Method to do the deep copy.
	 * This method requies everying to be serializable.
	 * Object -> ObjectOutputStream -> ByteArrayOutputStream -> ByteArrayInputStream -> ObjectInputStream -> new Object.
	 *
	 * @param original The object to be copied.
	 * @return The deeply copied object.
	 * @throws Exception if an error occurs
	 */
	public static <T> T clone(final T original, final Manager manager) {
		if (original == null)
			return null;

		T result = null;
		ObjectOutputStream os = null;
		ObjectInputStream is = null;
		try {
			final ByteArrayOutputStream bytesOutput = new ByteArrayOutputStream();
			os = new ObjectOutputStream(bytesOutput);
			os.writeObject(original);
			os.close();
			os = null;
			final ByteArrayInputStream bytesInput = new ByteArrayInputStream(bytesOutput.toByteArray());

			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			//if (loader == null)
			//	loader = SerializationUtils.getWebappClassLoader(manager);
			is = new CustomObjectInputStream(bytesInput, loader);


			@SuppressWarnings("unchecked")
			final T tmp = (T) is.readObject();
			result = tmp;
			is.close();
			is = null;
		} catch (final ClassNotFoundException e) {
			// shouldn't happen with ByteArrayInput and Output streams
			throw new RuntimeException("Unexpected ClassNotFoundException while copying object: " + original, e);
		} catch (final IOException e) {
			// shouldn't happen with ByteArrayInput and Output streams
			throw new RuntimeException("Unexpected IOException while copying object: " + original, e);
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (final IOException e) {
					// ignore
				}
				is = null;
			}
			if (os != null) {
				try {
					os.close();
				} catch (final IOException e) {
					// ignore
				}
				os = null;
			}
		}
		return result;
	}


}
