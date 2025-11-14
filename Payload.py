# payload_live_timer.py
import socket
import threading
from datetime import datetime
import os
import time

HOST = '0.0.0.0'
PORT = 45655
LOG_FILE = "connections.log"

# Track clients: addr -> {"message": str, "connected_at": datetime}
clients = {}
clients_lock = threading.Lock()

# Ensure log file exists
if not os.path.exists(LOG_FILE):
    open(LOG_FILE, "w").close()

def log_connection(addr, message):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    log_entry = f"[{timestamp}] {addr} - {message}"
    with open(LOG_FILE, "a") as f:
        f.write(log_entry + "\n")
    with clients_lock:
        if "Disconnected" not in message:
            if addr not in clients:
                clients[addr] = {"message": message, "connected_at": datetime.now()}
            else:
                clients[addr]["message"] = message
        else:
            clients.pop(addr, None)

def handle_client(conn, addr):
    log_connection(addr, "Connected")
    try:
        while True:
            data = conn.recv(1024)
            if not data:
                break
            log_connection(addr, f"Sent: {data.decode()}")
            conn.sendall(b"Message received!")
    except ConnectionResetError:
        log_connection(addr, "Connection lost")
    finally:
        conn.close()
        log_connection(addr, "Disconnected")

def start_server():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((HOST, PORT))
        s.listen()
        print(f"Listening on {HOST}:{PORT}")

        # Start display thread
        threading.Thread(target=display_clients, daemon=True).start()

        while True:
            conn, addr = s.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()

def format_duration(start_time):
    """Return duration as HH:MM:SS"""
    delta = datetime.now() - start_time
    hours, remainder = divmod(int(delta.total_seconds()), 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{hours:02}:{minutes:02}:{seconds:02}"

def display_clients():
    while True:
        os.system('clear')
        print(f"{'Connected Devices':^80}")
        print("="*80)
        print(f"{'Address':<25}{'Last Message':<40}{'Duration':<15}")
        print("-"*80)
        with clients_lock:
            if clients:
                for addr, info in clients.items():
                    duration = format_duration(info["connected_at"])
                    print(f"{addr:<25}{info['message']:<40}{duration:<15}")
            else:
                print("No devices connected")
        print("="*80)
        time.sleep(1)

if __name__ == "__main__":
    start_server()
