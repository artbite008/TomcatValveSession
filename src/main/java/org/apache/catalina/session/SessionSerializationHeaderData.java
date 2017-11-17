/*
 * $Id$
 */
package org.apache.catalina.session;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.sql.Timestamp;

import org.apache.catalina.connector.Request;
import org.apache.catalina.session.StandardSession;

import java.util.logging.Logger;

public class SessionSerializationHeaderData implements ManualSerializable, Serializable {
  public static final long timeChangedSkew= 1*60*1000;  // 1 min.

  private transient Logger logger ;

  String tenant_id;
  Object user;
  String user_id;
  String node_id;
  String webapp;
  Timestamp creation_time;
  Timestamp last_accessed_time;
  int max_inactive_interval;
  Timestamp expiration_time;
  String is_new;
  String is_valid;
  Timestamp this_accessed_time;
  int request_count;
  String user_agent;
  String remote_host;
  String remote_addr;
  String remote_port;
  String remote_user;


 @Override
 public void writeObjectData(ObjectOutputStream stream) throws IOException {
     stream.writeObject(tenant_id);
     stream.writeObject(user_id);
     stream.writeObject(node_id);
     stream.writeObject(webapp);
     stream.writeObject(creation_time);

     stream.writeObject(last_accessed_time);
     stream.writeObject(Integer.valueOf(max_inactive_interval));
     stream.writeObject(expiration_time);
     stream.writeObject(is_new);
     stream.writeObject(is_valid);

     stream.writeObject(this_accessed_time);
     stream.writeObject(Integer.valueOf(request_count));
     stream.writeObject(user_agent);
     stream.writeObject(remote_host);
     stream.writeObject(remote_addr);

     stream.writeObject(remote_port);
     stream.writeObject(remote_user);
     SerializationUtils.writeObject("user", user, stream, logger);


 }



 @Override
 public void readObjectData(ObjectInputStream stream) throws ClassNotFoundException, IOException {

     tenant_id = (String) stream.readObject();
     user_id = (String) stream.readObject();
     node_id = (String) stream.readObject();
     webapp   = (String) stream.readObject();
     creation_time = (Timestamp) stream.readObject();

     last_accessed_time = (Timestamp) stream.readObject();
     max_inactive_interval =  ((Integer)stream.readObject()).intValue();
     expiration_time = (Timestamp) stream.readObject();
     is_new = (String) stream.readObject();
     is_valid = (String) stream.readObject();

     this_accessed_time = (Timestamp) stream.readObject();
     request_count = ((Integer)stream.readObject()).intValue();
     user_agent = (String) stream.readObject();
     remote_host = (String) stream.readObject();
     remote_addr = (String) stream.readObject();

     remote_port = (String) stream.readObject();
     remote_user = (String) stream.readObject();
     user  = SerializationUtils.readObject("user", stream, logger);

 }


 public SessionSerializationHeaderData(final Logger logger) {
     this.logger = logger;
 }

 public SessionSerializationHeaderData( final Logger logger,
         final StandardSession s, final Request request, final String nodeID, final String webApp) {
     super();
     this.logger =logger;
     tenant_id = FoundationUtil.truncate(ReflectionUtils.getSessionAttributes(s).get("tenantToken"), 255);
     user = ReflectionUtils.getSessionAttributes(s).get("com.plateausystems.elms.client.javabean.TMSUserContextProvider");
     user_id = user == null ? null : FoundationUtil.truncate(user.toString(), 255);
     node_id = FoundationUtil.truncate(nodeID, 90);
     webapp = FoundationUtil.truncate(webApp, 90);
     creation_time = new Timestamp(s.getCreationTime());
     max_inactive_interval = s.getMaxInactiveInterval();
     is_new = s.isNew() ? "Y" : "N";
     is_valid = s.isValid() ? "Y" : "N";

     final Object request_count_object = s.getNote("request_count");
     final int temp_request_count = request_count_object == null ? 0 : (Integer)(request_count_object);
     request_count = temp_request_count + (request == null ? 0 : 1);
     user_agent = FoundationUtil.truncate(request == null ? null : request.getHeader("User-Agent"), 255);
     remote_host = FoundationUtil.truncate(request == null ? null : request.getRemoteHost(), 255);
     remote_addr = FoundationUtil.truncate(request == null ? null : request.getRemoteAddr(), 255);
     remote_port = FoundationUtil.truncate(request == null ? null : Integer.toString(request.getRemotePort()), 255);
     remote_user = FoundationUtil.truncate(request == null ? null : request.getRemoteUser(), 255);

     //below data will change on each request. and will trigger update to DB.
     // so put in a time skew.
     last_accessed_time = new Timestamp(s.getLastAccessedTime());
     expiration_time = new Timestamp(s.getLastAccessedTime() + s.getMaxInactiveInterval() * 1000);
     this_accessed_time = new Timestamp(s.getThisAccessedTime());
 }

 @Override
 public int hashCode() {
     final int prime = 31;
     int result = 1;
     result = prime * result + ((creation_time == null) ? 0 : creation_time.hashCode());
     result = prime * result + ((expiration_time == null) ? 0 : expiration_time.hashCode());
     result = prime * result + ((is_new == null) ? 0 : is_new.hashCode());
     result = prime * result + ((is_valid == null) ? 0 : is_valid.hashCode());
     result = prime * result + ((last_accessed_time == null) ? 0 : last_accessed_time.hashCode());
     result = prime * result + max_inactive_interval;
     result = prime * result + ((node_id == null) ? 0 : node_id.hashCode());
     result = prime * result + ((remote_addr == null) ? 0 : remote_addr.hashCode());
     result = prime * result + ((remote_host == null) ? 0 : remote_host.hashCode());
     result = prime * result + ((remote_port == null) ? 0 : remote_port.hashCode());
     result = prime * result + ((remote_user == null) ? 0 : remote_user.hashCode());
     result = prime * result + request_count;
     result = prime * result + ((tenant_id == null) ? 0 : tenant_id.hashCode());
     result = prime * result + ((this_accessed_time == null) ? 0 : this_accessed_time.hashCode());
     result = prime * result + ((user == null) ? 0 : user.hashCode());
     result = prime * result + ((user_agent == null) ? 0 : user_agent.hashCode());
     result = prime * result + ((user_id == null) ? 0 : user_id.hashCode());
     result = prime * result + ((webapp == null) ? 0 : webapp.hashCode());
     return result;
 }




 private boolean isTimeChanged(Timestamp a,  Timestamp b ) {
     if (a == null) {
         if (b != null)
             return true;
     }
     else if (a != null) {
         if (b == null)
             return true;
     }
     else if (  Math.abs(b.getTime()-a.getTime()) > timeChangedSkew ) {
         return true;
     }
     return false;
 }


 @Override
 public boolean equals(final Object obj) {
     if (this == obj)
         return true;
     if (obj == null)
         return false;
     if (getClass() != obj.getClass())
         return false;
     final SessionSerializationHeaderData other = (SessionSerializationHeaderData) obj;
     if (creation_time == null) {
         if (other.creation_time != null)
             return false;
     } else if (!creation_time.equals(other.creation_time))
         return false;
//   if (expiration_time == null) {
//       if (other.expiration_time != null)
//           return false;
//   } else if (!expiration_time.equals(other.expiration_time))
//       return false;

     if (isTimeChanged(expiration_time,other.expiration_time ))
         return false;



     if (is_new == null) {
         if (other.is_new != null)
             return false;
     } else if (!is_new.equals(other.is_new))
         return false;
     if (is_valid == null) {
         if (other.is_valid != null)
             return false;
     } else if (!is_valid.equals(other.is_valid))
         return false;
//   if (last_accessed_time == null) {
//       if (other.last_accessed_time != null)
//           return false;
//   } else if (!last_accessed_time.equals(other.last_accessed_time))
//       return false;
     if (isTimeChanged(last_accessed_time,other.last_accessed_time ))
         return false;



     if (max_inactive_interval != other.max_inactive_interval)
         return false;
     if (node_id == null) {
         if (other.node_id != null)
             return false;
     } else if (!node_id.equals(other.node_id))
         return false;
     if (remote_addr == null) {
         if (other.remote_addr != null)
             return false;
     } else if (!remote_addr.equals(other.remote_addr))
         return false;
     if (remote_host == null) {
         if (other.remote_host != null)
             return false;
     } else if (!remote_host.equals(other.remote_host))
         return false;

//remove this check. let it be persisted only when the time has passed for the this_accessed_time etc..
//   if (remote_port == null) {
//       if (other.remote_port != null)
//           return false;
//   } else if (!remote_port.equals(other.remote_port))
//       return false;



     if (remote_user == null) {
         if (other.remote_user != null)
             return false;
     } else if (!remote_user.equals(other.remote_user))
         return false;
     if (request_count != other.request_count)
         return false;
     if (tenant_id == null) {
         if (other.tenant_id != null)
             return false;
     } else if (!tenant_id.equals(other.tenant_id))
         return false;
//   if (this_accessed_time == null) {
//       if (other.this_accessed_time != null)
//           return false;
//   } else if (!this_accessed_time.equals(other.this_accessed_time))
//       return false;

     if (isTimeChanged(this_accessed_time,other.this_accessed_time ))
         return false;


     if (user == null) {
         if (other.user != null)
             return false;
     } else if (!user.equals(other.user))
         return false;
     if (user_agent == null) {
         if (other.user_agent != null)
             return false;
     } else if (!user_agent.equals(other.user_agent))
         return false;
     if (user_id == null) {
         if (other.user_id != null)
             return false;
     } else if (!user_id.equals(other.user_id))
         return false;
     if (webapp == null) {
         if (other.webapp != null)
             return false;
     } else if (!webapp.equals(other.webapp))
         return false;
     return true;
 }

}