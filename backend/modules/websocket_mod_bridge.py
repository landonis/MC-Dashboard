from starlette.endpoints import WebSocketEndpoint
from starlette.websockets import WebSocket, WebSocketDisconnect
from starlette.routing import WebSocketRoute, Route
from starlette.requests import Request
from starlette.responses import JSONResponse
import asyncio
import json

mod_socket = {"conn": None, "lock": asyncio.Lock()}


class ModWebSocket(WebSocketEndpoint):
    encoding = "text"

    async def on_connect(self, websocket: WebSocket):
        await websocket.accept()
        async with mod_socket["lock"]:
            mod_socket["conn"] = websocket
        print("[Backend] Mod connected via WebSocket")

    async def on_receive(self, websocket: WebSocket, data):
        print("[Backend] Received from mod:", data)
        try:
            payload = json.loads(data)
            if isinstance(payload, dict) and payload.get("event") == "reconnected":
                print("[Backend] ⚡ Mod has reconnected to dashboard.")
        except Exception as e:
            print("[Backend] Failed to parse mod message:", e)

    async def on_disconnect(self, websocket: WebSocket, close_code: int):
        async with mod_socket["lock"]:
            mod_socket["conn"] = None
        print("[Backend] Mod WebSocket disconnected")


async def send_to_mod(message: dict) -> dict:
    async with mod_socket["lock"]:
        conn = mod_socket["conn"]
        if conn is None:
            return {"success": False, "error": "Mod not connected"}
        try:
            await conn.send_text(json.dumps(message))
            return {"success": True}
        except Exception as e:
            return {"success": False, "error": str(e)}


async def send_message_to_mod(request: Request):
    body = await request.json()
    content = body.get("content")
    if not content:
        return JSONResponse({"error": "Missing content"}, status_code=400)
    result = await send_to_mod({"type": "sendMessage", "content": content})
    return JSONResponse(result)


async def set_day(request: Request):
    result = await send_to_mod({"type": "setDay"})
    return JSONResponse(result)


async def list_players(request: Request):
    result = await send_to_mod({"type": "listPlayers"})
    return JSONResponse(result)


# Export Starlette-compatible routes
routes = [
    WebSocketRoute("/ws/minecraft", ModWebSocket),
    Route("/mod/send_message", send_message_to_mod, methods=["POST"]),
    Route("/mod/set_day", set_day, methods=["POST"]),
    Route("/mod/list_players", list_players, methods=["GET"]),
]
