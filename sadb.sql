CREATE DATABASE  IF NOT EXISTS `sadb` /*!40100 DEFAULT CHARACTER SET utf8mb4 */;
USE `sadb`;

DROP TABLE IF EXISTS `ps_foundation_session`;
CREATE TABLE `ps_foundation_session` (
  `session_id` varchar(32) NOT NULL,
  `tenant_id` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  `node_id` varchar(255) DEFAULT NULL,
  `webapp` varchar(255) DEFAULT NULL,
  `creation_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `last_accessed_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
  `max_inactive_interval` int(11) DEFAULT NULL,
  `expiration_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
  `is_new` char(1) DEFAULT NULL,
  `is_valid` char(1) DEFAULT NULL,
  `this_accessed_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
  `request_count` int(11) DEFAULT NULL,
  `attributes_count` int(11) DEFAULT NULL,
  `attributes_size` int(11) DEFAULT NULL,
  `user_agent` varchar(255) DEFAULT NULL,
  `remote_host` varchar(255) DEFAULT NULL,
  `remote_addr` varchar(255) DEFAULT NULL,
  `remote_port` varchar(255) DEFAULT NULL,
  `remote_user` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


DROP TABLE IF EXISTS `ps_foundation_session_attr`;
CREATE TABLE `ps_foundation_session_attr` (
  `session_id` varchar(32) NOT NULL,
  `attr_key` varchar(255) DEFAULT NULL,
  `last_updated_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_count` int(11) DEFAULT NULL,
  `data_length` int(11) DEFAULT NULL,
  `data_checksum` varchar(2000) DEFAULT NULL,
  `data_type` char(255) DEFAULT NULL,
  `data` longblob,
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

