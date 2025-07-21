from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.cors import CORSMiddleware
from backend.modules import websocket_mod_bridge

# Optional: import Flask app if you want to mount any legacy Flask Blueprints
# from flask import Flask
# from flask_sqlalchemy import SQLAlchemy
# from backend.modules.minecraft_mods import minecraft_mods_bp
# from werkzeug.middleware.dispatcher import DispatcherMiddleware
# from starlette.middleware.wsgi import WSGIMiddleware

# Example if you still want to keep a hybrid approach with Flask:
# def get_flask_app():
#     app = Flask(__name__)
#     app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('DATABASE_URL', 'sqlite:///dashboard.db')
#     app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
#     db.init_app(app)
#     app.register_blueprint(minecraft_mods_bp, url_prefix='/mod')
#     return app

def create_mod_app() -> Starlette:
    app = Starlette(debug=True, routes=websocket_mod_bridge.routes)
    return app
