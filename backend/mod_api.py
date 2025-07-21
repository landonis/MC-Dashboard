# backend/mod_api.py

from flask import Flask
from backend.models import db
from backend.modules.minecraft_mods import minecraft_mods_bp
from backend.modules.websocket_mod_bridge import minecraft_ws_bp
import os

def create_mod_app():
    app = Flask(__name__)
    app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('DATABASE_URL', 'sqlite:///dashboard.db')
    app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

    db.init_app(app)

    app.register_blueprint(minecraft_mods_bp, url_prefix='/mod')
    app.register_blueprint(minecraft_ws_bp)

    return app
