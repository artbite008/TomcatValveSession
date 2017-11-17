package org.apache.catalina.session;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.zip.CRC32;

import java.util.logging.Logger;


/**
 * Record of a session attribute and metadata about that attribute.
 *
 * @author jim631@sina.com
 */
public class SessionAttributeRecord implements ManualSerializable, Serializable {

	private static Logger log = Logger.getLogger(SessionAttributeRecord.class.getName());

	 String key;
	 int update_count;
	 int data_length;
	 String data_checksum;
	 String data_type;
	 byte[] data;

	public SessionAttributeRecord() {
	}


	SessionAttributeRecord(final Logger logger, final String key, final Object value, final int update_count) throws IOException {
		super();
		this.log=logger;
		this.key = FoundationUtil.truncate(key, 4000);
		this.update_count = update_count;
		this.data = SerializationUtils.serialize(value);
		this.data_length = data.length;
		this.data_checksum = checksum(data);
		this.data_type = FoundationUtil.truncate(value.getClass().getName(), 1000);
	}

	SessionAttributeRecord(final String key, final int update_count, final int data_length, final String data_checksum, final String data_type,
			final byte[] data) {
		super();
		this.key = key;
		this.update_count = update_count;
		this.data_length = data_length;
		this.data_checksum = data_checksum;
		this.data_type = data_type;
		this.data = data;
	}


	private String checksum(final byte[] data) {
		final CRC32 crc = new CRC32();
		crc.update(data);
		final String result = Long.toHexString(crc.getValue());
		return result;
	}

	@Override
	public void writeObjectData(ObjectOutputStream stream) throws IOException {
		stream.writeObject(key);
		stream.writeObject(data_checksum);
		stream.writeObject(data_type);
		stream.writeObject(Integer.valueOf(update_count));
		stream.writeObject(Integer.valueOf(data_length));
		stream.writeObject(data);
	}

	@Override
	public void readObjectData(ObjectInputStream stream) throws ClassNotFoundException, IOException {
		key = (String)stream.readObject();
		data_checksum=  (String)stream.readObject();
		data_type=  (String)stream.readObject();
		update_count= ((Integer)stream.readObject()).intValue();
		data_length= ((Integer)stream.readObject()).intValue();
		data=  (byte[])stream.readObject();

	}
	    //test
	public static void main(String[] args) {
		class Foo implements  Serializable {
			private static final long serialVersionUID = 1L;
			String name;
			Long aInt;
		}

		Foo f = new Foo();
		f.aInt=2343l;
		f.name="data1";
		try {
			SessionAttributeRecord sa = new SessionAttributeRecord(null, "data1", f , 1);
			ObjectOutputStream stream = new ObjectOutputStream ( new FileOutputStream("s:\\tmp\\SessionAttributeRecord.dat") );
			sa.writeObjectData(stream);
			stream.close();


			ObjectInputStream fin = new ObjectInputStream ( new FileInputStream("s:\\tmp\\SessionAttributeRecord.dat") );
			SessionAttributeRecord sanew = new SessionAttributeRecord();
			sanew.readObjectData(fin);
			//System.out.println("read SessionAttributeRecord:" + sanew);
			Foo foo = (Foo)SerializationUtils.createObjectFromData(null, sanew.data);
			//System.out.println("foo=" + foo);

			//System.out.println(new Timestamp(1404426239531l));



		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		}


	}

}
