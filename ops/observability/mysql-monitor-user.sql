-- The read-only database user for mysqld-exporter (server health only: no access to PayPort's tables).
-- mysql -u root -p < mysql-monitor-user.sql   (replace the password first)
CREATE USER IF NOT EXISTS 'pp_monitor'@'localhost' IDENTIFIED BY 'CHANGE-ME' WITH MAX_USER_CONNECTIONS 3;
CREATE USER IF NOT EXISTS 'pp_monitor'@'127.0.0.1' IDENTIFIED BY 'CHANGE-ME' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'pp_monitor'@'localhost', 'pp_monitor'@'127.0.0.1';
GRANT SELECT ON performance_schema.* TO 'pp_monitor'@'localhost', 'pp_monitor'@'127.0.0.1';
FLUSH PRIVILEGES;
