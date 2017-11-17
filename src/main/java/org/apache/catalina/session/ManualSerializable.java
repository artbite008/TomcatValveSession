package org.apache.catalina.session;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Description: implement to allow read/write object data as bytes from/to the ObjectOutputStream
 *
 * @author jim631@sina.com
 */
public interface ManualSerializable {
	/**
	 * Write the data to the stream.
	 * @param stream
	 * @throws IOException
	 */
	public void writeObjectData(ObjectOutputStream stream) throws IOException ;

	/**
	 * Read data from the stream.
	 * @param stream
	 * @throws ClassNotFoundException
	 * @throws IOException
	 */
	public void readObjectData(ObjectInputStream stream) throws ClassNotFoundException, IOException ;
}