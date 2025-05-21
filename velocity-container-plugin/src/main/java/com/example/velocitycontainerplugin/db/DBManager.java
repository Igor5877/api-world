package com.example.velocitycontainerplugin.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
// import com.example.velocitycontainerplugin.Config; // Assuming a config class

public class DBManager {

    // private final Config config; // Assuming a config class for DB details
    private String dbUrl;
    private String dbUsername;
    private String dbPassword;

    // Placeholder for actual configuration
    public DBManager(/* Config config */) {
        // this.config = config;
        // In a real scenario, these would come from the config object:
        // this.dbUrl = "jdbc:mysql://" + config.getDbHost() + ":" + config.getDbPort() + "/" + config.getDbName();
        // this.dbUsername = config.getDbUsername();
        // this.dbPassword = config.getDbPassword();

        // For now, use placeholder values or indicate they need to be configured
        this.dbUrl = "jdbc:mysql://localhost:3306/velocity_containers"; // Example
        this.dbUsername = "velocity_user"; // Example
        this.dbPassword = "password"; // Example
        System.out.println("DBManager initialized. DB connection details need to be configured.");

        // It's good practice to load the JDBC driver explicitly
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL JDBC Driver not found!");
            // In a real plugin, you might want to disable the plugin or throw an error
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
    }

    /**
     * Creates the necessary database table if it doesn't exist.
     * Table: player_containers
     * Columns:
     *  id INT PRIMARY KEY AUTO_INCREMENT
     *  player_uuid VARCHAR(36) NOT NULL
     *  container_name VARCHAR(255) NOT NULL UNIQUE
     *  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     *  last_active_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
     *  status VARCHAR(50) DEFAULT 'STOPPED' -- e.g., CREATING, RUNNING, STOPPED, DELETED
     *  base_image_name VARCHAR(255)
     */
    public void initializeDatabase() {
        String sql = "CREATE TABLE IF NOT EXISTS player_containers (" +
                     "id INT PRIMARY KEY AUTO_INCREMENT," +
                     "player_uuid VARCHAR(36) NOT NULL," +
                     "container_name VARCHAR(255) NOT NULL UNIQUE," +
                     "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                     "last_active_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                     "status VARCHAR(50) DEFAULT 'STOPPED'," +
                     "base_image_name VARCHAR(255)" +
                     ");";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
            System.out.println("Database table 'player_containers' initialized successfully.");
        } catch (SQLException e) {
            System.err.println("Error initializing database table: " + e.getMessage());
            e.printStackTrace(); // For more detailed logging
        }
    }

    /**
     * Records a new container in the database.
     * @param playerUuid The UUID of the player who owns the container.
     * @param containerName The name of the container.
     * @param baseImageName The name of the base image used for the container.
     * @return True if recording was successful, false otherwise.
     */
    public boolean recordNewContainer(String playerUuid, String containerName, String baseImageName) {
        String sql = "INSERT INTO player_containers (player_uuid, container_name, base_image_name, status) VALUES (?, ?, ?, 'CREATING');";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUuid);
            stmt.setString(2, containerName);
            stmt.setString(3, baseImageName);
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected > 0) {
                System.out.println("New container " + containerName + " recorded for player " + playerUuid);
                return true;
            }
            return false;
        } catch (SQLException e) {
            System.err.println("Error recording new container " + containerName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates the status of a container.
     * @param containerName The name of the container.
     * @param status The new status (e.g., RUNNING, STOPPED, DELETED).
     * @return True if update was successful, false otherwise.
     */
    public boolean updateContainerStatus(String containerName, String status) {
        String sql = "UPDATE player_containers SET status = ?, last_active_at = CURRENT_TIMESTAMP WHERE container_name = ?;";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, containerName);
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected > 0) {
                System.out.println("Status for container " + containerName + " updated to " + status);
                return true;
            }
            System.err.println("Container " + containerName + " not found for status update or status unchanged.");
            return false;
        } catch (SQLException e) {
            System.err.println("Error updating container status for " + containerName + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Updates the last active time of a container.
     * @param containerName The name of the container.
     * @return True if update was successful, false otherwise.
     */
    public boolean updateContainerLastActive(String containerName) {
        String sql = "UPDATE player_containers SET last_active_at = CURRENT_TIMESTAMP WHERE container_name = ?;";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, containerName);
            int rowsAffected = stmt.executeUpdate();
             if (rowsAffected > 0) {
                System.out.println("Last active time for container " + containerName + " updated.");
                return true;
            }
            System.err.println("Container " + containerName + " not found for last active time update.");
            return false;
        } catch (SQLException e) {
            System.err.println("Error updating container last active time for " + containerName + ": " + e.getMessage());
            return false;
        }
    }

    // Add other methods as needed:
    // - String getContainerStatus(String containerName)
    // - ContainerInfo getContainerInfo(String playerUuid) // Assuming a ContainerInfo data class
    // - ContainerInfo getContainerInfoByContainerName(String containerName)
    // - List<ContainerInfo> getInactiveContainers(Timestamp olderThan)
    // - int countActiveContainers()
    // - boolean deleteContainerRecord(String containerName)

    // Nested class for holding container information
    public static class ContainerInfo {
        public final String containerName;
        public final String playerUuid;
        // public final String status; // Optionally add status or other fields

        public ContainerInfo(String containerName, String playerUuid /*, String status */) {
            this.containerName = containerName;
            this.playerUuid = playerUuid;
            // this.status = status;
        }

        @Override
        public String toString() {
            return "ContainerInfo{" +
                   "containerName='" + containerName + '\'' +
                   ", playerUuid='" + playerUuid + '\'' +
                   // ", status='" + status + '\'' +
                   '}';
        }
    }

    /**
     * Retrieves a list of containers that are considered inactive based on their last_active_at time
     * and current status. This typically targets containers that are 'STOPPED' or 'RUNNING' but haven't
     * been active recently.
     *
     * @param olderThan The timestamp threshold. Containers last active before this time are considered.
     * @return A list of ContainerInfo objects for inactive containers.
     */
    public List<ContainerInfo> getInactiveContainers(Timestamp olderThan) {
        List<ContainerInfo> inactiveContainers = new ArrayList<>();
        // SQL to find containers that were last active before 'olderThan' and are in a state
        // that makes them eligible for cleanup (e.g., STOPPED, or RUNNING but stale).
        // We explicitly exclude states like DELETED, FAILED_*, CREATING as they are either terminal
        // or transitional states not relevant for typical "inactivity" cleanup.
        String sql = "SELECT container_name, player_uuid, status FROM player_containers " +
                     "WHERE last_active_at < ? AND (status = 'STOPPED' OR status = 'RUNNING');";

        System.out.println("Executing SQL for inactive containers: " + sql + " with timestamp: " + olderThan);

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, olderThan);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String containerName = rs.getString("container_name");
                String playerUuid = rs.getString("player_uuid");
                // String status = rs.getString("status");
                inactiveContainers.add(new ContainerInfo(containerName, playerUuid /*, status */));
            }
            System.out.println("Found " + inactiveContainers.size() + " inactive containers older than " + olderThan);
        } catch (SQLException e) {
            System.err.println("Error retrieving inactive containers: " + e.getMessage());
            e.printStackTrace(); // For more detailed logging
        }
        return inactiveContainers;
    }
}
