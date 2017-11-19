## TomcatValveSession - 基于Tomcat Valve机制的分布式Session管理方案


### 应用场景
1. 本方案与Spring Session有本质的不同，Spring Session是解决不同的web站点能够共享同一份session数据的问题的。在本方案的应用场景中，我们已经使用了负载均衡并开启了session粘滞，来自同一个用户的请求只会被负载均衡服务器foward到一个固定的服务器，所以不存在解决不同的web站点能够共享同一份session数据的问题。
2. 为什么使用负载均衡+session粘滞取代Spring Session方案？因为企业级的应用中，用户每次操作会带来大量的session数据变更（特别是登陆），如果使用Spring Session方案，一次Http请求，后台会有大量的读取或者更新远程服务器的session数据的操作。如果是同步操作，效率极为低下，网页反应很慢。如果是异步操作，用户操作间隔很短的情况下，会有比较棘手的数据一致性问题。
3. 既然已经用了负载均衡+session粘滞解决不同的web站点能够共享同一份session数据的问题，为什么还要本方案？在负载均衡+session粘滞的前提下，如果有一个web站点宕机或者在灰度发布的情况下重启，这个时候session被粘滞到这台服务器上的用户就会受到影响，会突然转到重新登录的页面，因为session由于服务器重启已经丢失。这个时候，如果我们提前对每一个web站点的session已经在远程Session备份服务器上提前做好备份，在每一个web站点处理携带sessionId的HTTP请求的时候，如果sessionId本地不存在，都去远程Session备份服务器查询，一旦查询到这个sessionId存在，就主动恢复session，这样问题就迎刃而解了。
4. 大多数情况下，在负载均衡+session粘滞的场景下，一个用户的请求只会到达一个固定的web站点，无需同步访问远程远程Session备份服务器，只需用异步的方式往远程Session备份服务器备份自己的session数据。在极少数的情况下，譬如在web站点集群处于灰度发布或者部分宕机的情况下，原来粘滞的固定的web站点不再可用，负载均衡服务器把请求转向一个新的web站点，新的web站点根据这个请求上的sessionId去远程Session备份服务器取数据，并恢复session,这样用户在不知情的情况下被重新粘滞到另外一个web站点。

### 负载均衡+session粘滞配置
[看这里](https://github.com/artbite008/TomcatValveSession/blob/master/LoadBalancer.md)

### 配置Session持久化
1. 使用项目根目录下面的sadb.sql创建数据库和表。当然你也可以改成使用MongoDB或者Redis等非关系型数据库。
2. 修改DBFoundationSessionStore.java文件的getDataSource方法，连接改为指向独立的远程session备份服务器上的数据库。如果使用非关系型数据库，对应在DBFoundationSessionStore.java中的一些sql也需要改一下。

### Tomcat集成

1. 拷贝工程生成的tomcatValveSession-all.jar到每一个web站点的tomcat的lib目录,譬如/usr/share/tomcat7/lib
2. 在每一个web站点的tomcat的/conf/context.xml里做如下配置：
```
    <Context>
         <WatchedResource>WEB-INF/web.xml</WatchedResource>
         <WatchedResource>${catalina.base}/conf/web.xml</WatchedResource>
         <Resources cachingAllowed="false"/>
         <Valve className="org.apache.catalina.session.FoundationSessionManagerValve"/>
         <Manager className="org.apache.catalina.session.FoundationSessionManager" /> 
    </Context>
```

### Tomcat Valve机制
这个网上相关资料很多，请自行搜索。这里使用Valve机制主要是确保我们能在tomcat里面嵌入这样的代码：用户请求中的sessionId在当前web站点本地不存在的时候，不是由Tomcat默认机制新建一个session，而是依据这个sessionId到远程session备份服务器取下来，并恢复它。



