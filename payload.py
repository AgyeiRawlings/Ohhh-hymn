import socket
import threading

HOST = "0.0.0.0"  # listen on all interfaces
PORT = 45655

clients = {}
clients_lock = threading.Lock()
running = True

def handle_client(conn, addr):
    with conn:
        print(f"[+] Connected: {addr}")
        with clients_lock:
            clients[addr] = conn
        try:
            while True:
                data = conn.recv(1024)
                if not data:
                    break
                conn.sendall(b"Server received: " + data)
        except Exception as e:
            print(f"[!] Error from {addr}: {e}")
        finally:
            with clients_lock:
                clients.pop(addr, None)
            print(f"[-] Disconnected: {addr}")

def accept_loop(sock):
    while running:
        try:
            conn, addr = sock.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
        except OSError:
            break

def console_loop():
    global running
    while running:
        try:
            cmd = input("server> ").strip()
        except EOFError:
            cmd = "quit"

        if cmd == "list":
            with clients_lock:
                if clients:
                    print("Connected clients:")
                    for a in clients:
                        print(" -", a)
                else:
                    print("No connected clients.")
        elif cmd.startswith("sendall "):
            msg = cmd[len("sendall "):].encode()
            with clients_lock:
                for a, c in list(clients.items()):
                    try:
                        c.sendall(msg)
                    except Exception as e:
                        print(f"[!] Failed to send to {a}: {e}")
        elif cmd == "quit":
            print("Shutting down server...")
            running = False
        else:
            print("Commands: list, sendall <msg>, quit")

def main():
    sock = socket.socket()
    sock.bind((HOST, PORT))
    sock.listen()
    print(f"[*] Listening on {HOST}:{PORT}")
    threading.Thread(target=accept_loop, args=(sock,), daemon=True).start()
    console_loop()
    sock.close()

if __name__ == "__main__":
    main()
