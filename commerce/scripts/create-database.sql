-- Commerce's own database and user (edit the password first):  mysql -u root -p < create-database.sql
-- The user has rights on this database only: Commerce cannot read or change any PSP table.
CREATE DATABASE IF NOT EXISTS payport_commerce_staging CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'payport_commerce'@'127.0.0.1' IDENTIFIED BY 'CHANGE-ME';
CREATE USER IF NOT EXISTS 'payport_commerce'@'localhost' IDENTIFIED BY 'CHANGE-ME';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON payport_commerce_staging.* TO 'payport_commerce'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON payport_commerce_staging.* TO 'payport_commerce'@'localhost';
FLUSH PRIVILEGES;
