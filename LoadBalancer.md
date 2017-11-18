# Load Banlancer Configuration
## How to configure the Load Banlancer & Sticky Session & URL Rewrite in Apache Server

### 1.	install apache server
```
    sudo apt-get install apache2
```
 
For mac, the "apachectl" is for Apache2 and it's installed by default.
### 2.	Then use "apache2 -v" to get the version, if it's not "Apache/2.4.18 (Ubuntu)", some file pathes in below steps may be different.
### 3.	load the necessary Apache componments
```
    sudo a2enmod rewrite
    sudo a2enmod proxy
    sudo a2enmod proxy_balancer
    sudo a2enmod proxy_http
    sudo a2enmod headers
    sudo a2enmod lbmethod_byrequests
```
 
For mac:
open the /etc/apache2/httpd.conf
remove the # of above listed mod

### 4.	config the load Load Banlancer, Sticky Session, and URL Rewrite
```
    sudo vi /etc/apache2/sites-available/000-default.conf
```
 
For mac add following "Include" directive into /etc/apahce2/httpd.conf
```
    Include /etc/apache2/users/xxx.conf file
```
Then, add following configuration into xxx.conf file
```
<VirtualHost localhost:8081>
     ProxyRequests Off
       ServerName localhost
     Header add Set-Cookie "ROUTEID=.%{BALANCER_WORKER_ROUTE}e; path=/" env=BALANCER_ROUTE_CHANGED
     CustomLog /var/log/apache2/mybalancer combined
      
     <Proxy balancer://proxy>
         Order Deny,Allow
         Allow from all
         BalancerMember http://<IP of Server A>:8080 route=1 timeout=6 retry=20
         BalancerMember http://<IP of Server B>:8080 route=2 timeout=6 retry=20
          
         ProxySet stickysession=ROUTEID
     </Proxy>
 
     <Location /balancer-manager>
            SetHandler balancer-manager
     </Location>
     ProxyPass /balancer-manager !
     Proxypass / balancer://proxy/ maxattempts=6 timeout=60
     ProxyPassReverse / balancer://proxy/
     RewriteEngine on
</VirtualHost>
```
### 5.	!!"RewriteEngine on" is quite important here, it's used to make the url to be always the same, not changed to the real server url(http://<IP of Server X>:8080)
 
### 6.	Edit /etc/apache2/apache2.conf, add below items (For mac, the file is /etc/apache2/httpd.conf) 
```
ServerName localhost
Listen localhost:8081
```
### 7.	 restart apache server. if meet any error, check /var/log/apache2/error.log for detail, you can use "apachectl -t" command to verify the configuration syntax for Apache.
```
    sudo service apache2 restart
```
### 8.	use below url to visit the balancer manager page
```
    http://localhost:8081/balancer-manager
```
