from flask import Blueprint, request, jsonify
from flask_sock import Sock
import threading
import json

minecraft_ws_bp = Blueprint('minecraft_ws', __name__)
sock = Sock(minecraft_ws_bp)

# Keep track of active WebSocket connection to the Fabric mod
mod_socket = {"conn": None, "lock": threading.Lock()}


@sock.route('/ws/minecraft')
def ws_mod(sock):
    with mod_socket["lock"]:
        mod_socket["conn"] = sock
    print("[Backend] Mod connected via WebSocket")

    while True:
    try:
        data = sock.receive()
        if data is None:
            break

        print("[Backend] Received from mod:", data)

        try:
            payload = json.loads(data)
            if isinstance(payload, dict) and payload.get("event") == "reconnected":
                print("[Backend] ⚡ Mod has reconnected to dashboard.")
                # Optionally: emit to frontend, log to file, etc.

        except Exception as parse_error:
            print("[Backend] Failed to parse mod message:", parse_error)

    except Exception as e:
        print("[Backend] Mod WebSocket error:", str(e))
        break


    with mod_socket["lock"]:
        mod_socket["conn"] = None
    print("[Backend] Mod WebSocket disconnected")


def send_to_mod(message: dict) -> dict:
    with mod_socket["lock"]:
        conn = mod_socket["conn"]
        if conn is None:
            return {"success": False, "error": "Mod not connected"}
        try:
            conn.send(json.dumps(message))
            return {"success": True}
        except Exception as e:
            return {"success": False, "error": str(e)}    


@minecraft_ws_bp.route('/mod/send_message', methods=['POST'])
def send_message_to_mod():
    content = request.json.get("content")
    if not content:
        return jsonify({"error": "Missing content"}), 400
    result = send_to_mod({"type": "sendMessage", "content": content})
    return jsonify(result)


@minecraft_ws_bp.route('/mod/set_day', methods=['POST'])
def set_day():
    result = send_to_mod({"type": "setDay"})
    return jsonify(result)


@minecraft_ws_bp.route('/mod/list_players', methods=['GET'])
def list_players():
    result = send_to_mod({"type": "listPlayers"})
    return jsonify(result)
