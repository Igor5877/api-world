from fastapi import FastAPI, HTTPException, Depends, Header
from pydantic import BaseModel
import subprocess
import time
import json
import mysql.connector
from datetime import datetime, timedelta
import ipaddress # For IP address validation and type checking
import os

app = FastAPI()

# --- Configuration from Environment Variables with Defaults ---

# Database Connection
DB_HOST = os.getenv('DB_HOST', 'localhost')
DB_USER = os.getenv('DB_USER', 'api_user')
DB_PASSWORD = os.getenv('DB_PASSWORD', 'changeme_password')
DB_NAME = os.getenv('DB_NAME', 'api_world_db')

# Application Logic
try:
    MAX_CONCURRENT_CONTAINERS = int(os.getenv('MAX_CONCURRENT_CONTAINERS', '5'))
except ValueError:
    print("Warning: Invalid MAX_CONCURRENT_CONTAINERS value, using default (5).")
    MAX_CONCURRENT_CONTAINERS = 5

try:
    INACTIVITY_PERIOD_DAYS = int(os.getenv('INACTIVITY_PERIOD_DAYS', '14'))
except ValueError:
    print("Warning: Invalid INACTIVITY_PERIOD_DAYS value, using default (14).")
    INACTIVITY_PERIOD_DAYS = 14

ADMIN_API_KEY = os.getenv('ADMIN_API_KEY', 'supersecretkey_pleasereplaceme')
LXC_BASE_IMAGE_NAME = os.getenv('LXC_BASE_IMAGE_NAME', 'wan-blok')


# --- IP Address Helper ---
def is_private_ip(ip_str):
    if not ip_str: return False
    try:
        ip = ipaddress.ip_address(ip_str)
        return ip.is_private and not ip.is_loopback and not ip.is_link_local
    except ValueError:
        return False

def is_global_ip(ip_str):
    if not ip_str: return False
    try:
        ip = ipaddress.ip_address(ip_str)
        return ip.is_global and not ip.is_loopback and not ip.is_link_local
    except ValueError:
        return False

def get_container_ip(container_name: str) -> str | None:
    """
    Retrieves a suitable IPv4 address for the given container.
    Prefers global IPs, then private IPs. Avoids loopback and link-local.
    """
    try:
        ip_result = subprocess.run(
            ["lxc", "info", container_name, "--format", "json"],
            capture_output=True, text=True, check=True
        )
        info = json.loads(ip_result.stdout)

        if not info or not info.get("state") or not info["state"].get("network"):
            print(f"IP Retrieval: No network state information for {container_name}")
            return None

        network_info = info["state"]["network"]
        candidate_ips = []

        for if_name, if_data in network_info.items():
            if if_data.get("addresses"):
                for addr_info in if_data["addresses"]:
                    if addr_info.get("family") == "inet" and addr_info.get("scope") == "global":
                        ip_str = addr_info.get("address")
                        try:
                            ipaddress.ip_address(ip_str) 
                            candidate_ips.append(ip_str)
                        except ValueError:
                            print(f"IP Retrieval: Invalid IP format '{ip_str}' for {container_name} on {if_name}")
        
        if not candidate_ips:
            print(f"IP Retrieval: No 'inet' (IPv4) global scope addresses found for {container_name}")
            return None

        global_ips = [ip for ip in candidate_ips if is_global_ip(ip)]
        if global_ips:
            print(f"IP Retrieval: Found global IP {global_ips[0]} for {container_name}")
            return global_ips[0]

        private_ips = [ip for ip in candidate_ips if is_private_ip(ip)]
        if private_ips:
            print(f"IP Retrieval: Found private IP {private_ips[0]} for {container_name}")
            return private_ips[0]
        
        other_valid_ips = [ip for ip in candidate_ips if not ipaddress.ip_address(ip).is_loopback and not ipaddress.ip_address(ip).is_link_local]
        if other_valid_ips:
            print(f"IP Retrieval: Found other valid IP {other_valid_ips[0]} for {container_name}")
            return other_valid_ips[0]

        print(f"IP Retrieval: No suitable IP found from candidates for {container_name}: {candidate_ips}")
        return None

    except subprocess.CalledProcessError as e:
        print(f"IP Retrieval: LXC command failed for {container_name}: {e.stderr or e.stdout}")
        return None
    except json.JSONDecodeError as e:
        print(f"IP Retrieval: Failed to parse JSON output for {container_name}: {e}")
        return None
    except Exception as e:
        print(f"IP Retrieval: An unexpected error occurred while getting IP for {container_name}: {e}")
        return None

# --- Database Helper Functions ---
def get_db_connection():
    try:
        conn = mysql.connector.connect(
            host=DB_HOST,
            user=DB_USER,
            password=DB_PASSWORD
            # DB_NAME is used after connection to create DB if not exists
        )
        return conn
    except mysql.connector.Error as err:
        print(f"Error connecting to MySQL server: {err}")
        return None

def create_database_and_table():
    conn = get_db_connection()
    if not conn:
        print("Initial DB connection failed. Cannot create database and table.")
        return

    cursor = conn.cursor()
    try:
        # Create database if it doesn't exist
        cursor.execute(f"CREATE DATABASE IF NOT EXISTS {DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        conn.database = DB_NAME  # Switch to the specified/created database
        print(f"Database '{DB_NAME}' ensured.")

        # Create table if it doesn't exist
        create_table_query = """
        CREATE TABLE IF NOT EXISTS player_containers (
            id INT AUTO_INCREMENT PRIMARY KEY,
            player_identifier VARCHAR(255) UNIQUE NOT NULL,
            container_name VARCHAR(255) UNIQUE NOT NULL,
            ip_address VARCHAR(45),
            port INT,
            creation_timestamp DATETIME NOT NULL,
            last_login_timestamp DATETIME NOT NULL,
            status VARCHAR(50) NOT NULL
        )
        """
        cursor.execute(create_table_query)
        print("Table 'player_containers' ensured.")
        conn.commit()
    except mysql.connector.Error as err:
        print(f"Error creating database/table: {err}")
    finally:
        if conn.is_connected(): # Check if connection is still alive
            cursor.close()
            conn.close()

create_database_and_table()

class BazaRequest(BaseModel):
    player: str
    name: str

class StopRequest(BaseModel):
    player_identifier: str

# --- Cleanup Function for Inactive Containers ---
def cleanup_inactive_containers():
    summary = {"cleaned_up_count": 0, "errors": []}
    conn = get_db_connection()
    if not conn:
        print("Cleanup: Database connection failed.")
        summary["errors"].append("Database connection failed during cleanup.")
        return summary

    conn.database = DB_NAME # Ensure correct DB is selected
    cursor = conn.cursor(dictionary=True)

    try:
        cutoff_date = datetime.now() - timedelta(days=INACTIVITY_PERIOD_DAYS)
        print(f"Cleanup: Looking for containers inactive since {cutoff_date.strftime('%Y-%m-%d %H:%M:%S')}")

        cursor.execute(
            "SELECT player_identifier, container_name, status FROM player_containers WHERE last_login_timestamp < %s",
            (cutoff_date,)
        )
        inactive_containers = cursor.fetchall()

        if not inactive_containers:
            print("Cleanup: No inactive containers found.")
            return summary

        print(f"Cleanup: Found {len(inactive_containers)} inactive container(s) to process.")

        for container in inactive_containers:
            player_id = container['player_identifier']
            container_name = container['container_name']
            db_status = container['status']
            print(f"Cleanup: Processing container '{container_name}' for player '{player_id}' (DB status: {db_status})")

            try:
                lxc_is_running = False
                try:
                    lxc_info_result = subprocess.run(["lxc", "info", container_name], capture_output=True, text=True, check=False)
                    if lxc_info_result.returncode == 0 and "Status: RUNNING" in lxc_info_result.stdout:
                        lxc_is_running = True
                except Exception as e:
                    print(f"Cleanup: Error checking LXC info for {container_name}: {e}")
                    summary["errors"].append(f"LXC info error for {container_name}: {e}")

                if lxc_is_running:
                    print(f"Cleanup: Attempting to stop LXC container '{container_name}'...")
                    stop_result = subprocess.run(["lxc", "stop", container_name, "--force"], capture_output=True, text=True, check=False)
                    if stop_result.returncode == 0:
                        print(f"Cleanup: LXC container '{container_name}' stopped successfully.")
                    else:
                        if "already stopped" not in stop_result.stderr.lower() and "is not running" not in stop_result.stderr.lower():
                             print(f"Cleanup: Warning - Failed to stop LXC container '{container_name}': {stop_result.stderr or stop_result.stdout}")
                             summary["errors"].append(f"LXC stop warning for {container_name}: {stop_result.stderr or stop_result.stdout}")
                        else:
                            print(f"Cleanup: LXC container '{container_name}' was already stopped or not running.")
                
                print(f"Cleanup: Attempting to delete LXC container '{container_name}'...")
                lxc_exists_result = subprocess.run(["lxc", "info", container_name], capture_output=True, text=True, check=False)
                if lxc_exists_result.returncode == 0:
                    delete_result = subprocess.run(["lxc", "delete", container_name, "--force"], capture_output=True, text=True, check=False)
                    if delete_result.returncode == 0:
                        print(f"Cleanup: LXC container '{container_name}' deleted successfully.")
                    else:
                        print(f"Cleanup: Error deleting LXC container '{container_name}': {delete_result.stderr or delete_result.stdout}")
                        summary["errors"].append(f"LXC delete error for {container_name}: {delete_result.stderr or delete_result.stdout}")
                else:
                     print(f"Cleanup: LXC container '{container_name}' not found for deletion.")

                print(f"Cleanup: Attempting to delete DB record for player '{player_id}', container '{container_name}'...")
                cursor.execute(
                    "DELETE FROM player_containers WHERE player_identifier = %s AND container_name = %s",
                    (player_id, container_name)
                )
                conn.commit()
                print(f"Cleanup: DB record for '{container_name}' deleted successfully.")
                summary["cleaned_up_count"] += 1

            except Exception as e:
                conn.rollback()
                print(f"Cleanup: An unexpected error occurred while processing container '{container_name}': {e}")
                summary["errors"].append(f"Unexpected error for {container_name}: {e}")
        
        return summary

    except mysql.connector.Error as err:
        print(f"Cleanup: Database operation failed: {err}")
        summary["errors"].append(f"Database operation failed: {err}")
        return summary
    except Exception as e:
        print(f"Cleanup: An unexpected error occurred during cleanup: {e}")
        summary["errors"].append(f"An unexpected error occurred: {e}")
        return summary
    finally:
        if conn.is_connected():
            cursor.close()
            conn.close()

async def verify_api_key(x_api_key: str = Header(None)):
    if x_api_key != ADMIN_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing API Key")
    return x_api_key

@app.post("/baza/add")
def create_or_start_container(data: BazaRequest):
    player_identifier = data.player
    container_base_name = data.name
    container_name = f"{player_identifier}-{container_base_name}"
    current_timestamp = datetime.now()

    conn = get_db_connection()
    if not conn:
        return {"status": "error", "message": "Database connection failed"}
    
    conn.database = DB_NAME # Ensure correct DB is selected
    cursor = conn.cursor(dictionary=True)

    try:
        cursor.execute(
            "SELECT * FROM player_containers WHERE player_identifier = %s",
            (player_identifier,)
        )
        existing_container_record = cursor.fetchone()

        if existing_container_record:
            db_container_name = existing_container_record['container_name']
            db_status = existing_container_record['status']
            db_ip = existing_container_record['ip_address']
            db_port = existing_container_record['port']

            if db_status == 'running' or db_status == 'creating':
                cursor.execute(
                    "UPDATE player_containers SET last_login_timestamp = %s WHERE player_identifier = %s",
                    (current_timestamp, player_identifier)
                )
                conn.commit()
                return {
                    "status": "success",
                    "message": "Active container already exists.",
                    "container": db_container_name,
                    "ip": db_ip,
                    "port": db_port
                }
            elif db_status == 'stopped':
                cursor.execute("SELECT COUNT(*) as count FROM player_containers WHERE status = 'running'")
                running_count = cursor.fetchone()['count']
                if running_count >= MAX_CONCURRENT_CONTAINERS:
                    return {"status": "error", "message": "Maximum number of concurrent containers reached. Please try again later."}

                print(f"Found stopped container '{db_container_name}' for player '{player_identifier}'. Attempting to start.")
                subprocess.run(["lxc", "start", db_container_name], check=True)
                time.sleep(5) 

                ipv4 = get_container_ip(db_container_name)
                if not ipv4:
                    print(f"CRITICAL: Could not retrieve IP for just-started container {db_container_name}. Stored IP was {db_ip}")
                    if not db_ip: 
                        return {"status": "error", "message": f"Failed to obtain IP for container {db_container_name} after start."}
                    ipv4 = db_ip 
                    print(f"Warning: Using stale IP {ipv4} for {db_container_name} as new IP could not be found.")

                cursor.execute(
                    "UPDATE player_containers SET status = %s, last_login_timestamp = %s, ip_address = %s WHERE player_identifier = %s",
                    ('running', current_timestamp, ipv4, player_identifier)
                )
                conn.commit()
                return {
                    "status": "success",
                    "message": "Started existing container.",
                    "container": db_container_name,
                    "ip": ipv4,
                    "port": db_port
                }

        cursor.execute("SELECT COUNT(*) as count FROM player_containers WHERE status = 'running'")
        running_count = cursor.fetchone()['count']
        if running_count >= MAX_CONCURRENT_CONTAINERS:
            return {"status": "error", "message": "Maximum number of concurrent containers reached. Please try again later."}

        if existing_container_record: 
            print(f"Removing old unusable record for player {player_identifier} with status {existing_container_record['status']}")
            cursor.execute("DELETE FROM player_containers WHERE player_identifier = %s", (player_identifier,))
            conn.commit()

        lxc_info_check = subprocess.run(["lxc", "info", container_name], capture_output=True, text=True)
        if lxc_info_check.returncode != 0: 
            print(f"No LXC container named '{container_name}' found. Attempting to clone from '{LXC_BASE_IMAGE_NAME}'.")
            copy_result = subprocess.run(["lxc", "copy", LXC_BASE_IMAGE_NAME, container_name], capture_output=True, text=True)
            if copy_result.returncode != 0:
                return {"status": "error", "message": f"Помилка при копіюванні контейнера: {copy_result.stderr or copy_result.stdout}"}
        
        start_result = subprocess.run(["lxc", "start", container_name], capture_output=True, text=True)
        if start_result.returncode != 0:
            return {"status": "error", "message": f"Помилка при старті контейнера {container_name}: {start_result.stderr or start_result.stdout}"}

        time.sleep(5) 

        ipv4 = get_container_ip(container_name)
        if not ipv4:
            subprocess.run(["lxc", "stop", container_name, "--force"], capture_output=True, text=True) 
            return {"status": "error", "message": f"IP не знайдено для {container_name} after creation and start."}

        insert_query = """
        INSERT INTO player_containers 
        (player_identifier, container_name, ip_address, port, creation_timestamp, last_login_timestamp, status)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        """
        default_port = 25565 
        cursor.execute(insert_query, (
            player_identifier, container_name, ipv4, default_port,
            current_timestamp, current_timestamp, 'running'
        ))
        conn.commit()
        print(f"New container record added for player '{player_identifier}', container '{container_name}'.")

        return {
            "status": "success",
            "container": container_name,
            "ip": ipv4,
            "port": default_port
        }

    except mysql.connector.Error as err:
        if conn.is_connected(): conn.rollback()
        return {"status": "error", "message": f"Database operation failed: {err}"}
    except subprocess.CalledProcessError as e:
        return {"status": "error", "message": f"LXC command failed: {e.stderr or e.stdout}"}
    except Exception as e:
        if conn.is_connected(): conn.rollback()
        return {"status": "error", "message": f"An unexpected error occurred: {e}"}
    finally:
        if conn.is_connected():
            cursor.close()
            conn.close()

@app.post("/baza/stop")
def stop_container(data: StopRequest):
    player_identifier = data.player_identifier
    current_timestamp = datetime.now()

    conn = get_db_connection()
    if not conn:
        return {"status": "error", "message": "Database connection failed"}

    conn.database = DB_NAME # Ensure correct DB is selected
    cursor = conn.cursor(dictionary=True)

    try:
        cursor.execute(
            "SELECT container_name, status FROM player_containers WHERE player_identifier = %s",
            (player_identifier,)
        )
        record = cursor.fetchone()

        if not record:
            return {"status": "error", "message": f"Container for player '{player_identifier}' not found."}

        container_name = record['container_name']
        db_status = record['status']

        if db_status == 'stopped':
            return {"status": "success", "message": f"Container '{container_name}' for player '{player_identifier}' is already stopped."}

        try:
            lxc_info_result = subprocess.run(["lxc", "info", container_name], capture_output=True, text=True, check=False)
            lxc_is_stopped = "Status: STOPPED" in lxc_info_result.stdout if lxc_info_result.returncode == 0 else True 

            if lxc_is_stopped:
                print(f"LXC container '{container_name}' is already stopped or not found. Updating DB.")
            else:
                stop_result = subprocess.run(["lxc", "stop", container_name, "--force"], capture_output=True, text=True, check=False)
                if stop_result.returncode != 0:
                    if "already stopped" not in stop_result.stderr.lower() and "is not running" not in stop_result.stderr.lower():
                         return {"status": "error", "message": f"Failed to stop LXC container '{container_name}': {stop_result.stderr or stop_result.stdout}"}
                print(f"LXC container '{container_name}' stopped successfully or was already stopped.")

        except Exception as e:
             return {"status": "error", "message": f"Unexpected error during LXC operation for '{container_name}': {str(e)}"}


        cursor.execute(
            "UPDATE player_containers SET status = %s, last_login_timestamp = %s WHERE player_identifier = %s",
            ('stopped', current_timestamp, player_identifier)
        )
        conn.commit()

        return {"status": "success", "message": f"Container '{container_name}' for player '{player_identifier}' stopped successfully."}

    except mysql.connector.Error as err:
        if conn.is_connected(): conn.rollback()
        return {"status": "error", "message": f"Database operation failed: {err}"}
    except Exception as e:
        if conn.is_connected(): conn.rollback()
        return {"status": "error", "message": f"An unexpected error occurred: {e}"}
    finally:
        if conn.is_connected():
            cursor.close()
            conn.close()

@app.post("/baza/admin/cleanup_inactive", dependencies=[Depends(verify_api_key)])
def trigger_cleanup_inactive_containers():
    print("Admin: Received request to cleanup inactive containers.")
    result = cleanup_inactive_containers()
    return {"status": "cleanup_triggered", "details": result}
```
