#!/usr/bin/env python3

import sys, os
from datetime import datetime, timedelta
from functools import wraps
import logging
import bcrypt
import json

from flask import Flask, request, jsonify
from flask_sqlalchemy import SQLAlchemy
from flask_cors import CORS
from flask_jwt_extended import (
    JWTManager, create_access_token, get_jwt_identity, jwt_required,
    set_access_cookies, unset_jwt_cookies
)
from dotenv import load_dotenv

# Import models and db
from backend.models import db, User

# Load environment variables
load_dotenv()

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def create_app():
    app = Flask(__name__)

    # App config
    app.config["JWT_TOKEN_LOCATION"] = ["cookies"]
    app.config["JWT_COOKIE_SECURE"] = True
    app.config["JWT_COOKIE_SAMESITE"] = "Lax"
    app.config["JWT_COOKIE_CSRF_PROTECT"] = False
    app.config['JWT_SECRET_KEY'] = os.getenv('JWT_SECRET_KEY', 'jwt-secret-key-change-this')
    app.config['JWT_ACCESS_TOKEN_EXPIRES'] = timedelta(hours=24)
    app.config['MAX_CONTENT_LENGTH'] = 1024 * 1024 * 1024
    app.config['SECRET_KEY'] = os.getenv('SECRET_KEY', 'your-secret-key-change-this')
    app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('DATABASE_URL', 'sqlite:///dashboard.db')
    app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

    # Initialize extensions
    jwt = JWTManager(app)
    db.init_app(app)
    CORS(app, supports_credentials=True, resources={r"/api/*": {"origins": "*"}})

    # Authentication decorators
    def admin_required(f):
        @wraps(f)
        @jwt_required()
        def decorated(*args, **kwargs):
            current_user_id = get_jwt_identity()
            user = User.query.get(current_user_id)
            if not user or user.role != 'admin':
                return jsonify({'error': 'Admin access required'}), 403
            return f(*args, **kwargs)
        return decorated

    def user_or_admin_required(f):
        @wraps(f)
        @jwt_required()
        def decorated(*args, **kwargs):
            current_user_id = get_jwt_identity()
            user = User.query.get(current_user_id)
            if not user or user.role not in ['user', 'admin']:
                return jsonify({'error': 'User access required'}), 403
            return f(*args, **kwargs)
        return decorated

    # Auth Routes
    @app.route('/auth/login', methods=['POST'])
    def login():
        try:
            data = request.get_json() or request.form.to_dict()
            username = data.get('username', '').strip().lower()
            password = data.get('password')
            if not username or not password:
                return jsonify({'error': 'Username and password required'}), 400
            user = User.query.filter_by(username=username).first()
            if user and user.check_password(password):
                access_token = create_access_token(identity=str(user.id))
                response = jsonify({'msg': 'Login successful'})
                set_access_cookies(response, access_token)
                return response
            return jsonify({'error': 'Invalid credentials'}), 401
        except Exception as e:
            logger.error(f"Login error: {str(e)}")
            return jsonify({'error': 'Login failed'}), 500

    @app.route('/auth/logout', methods=['POST'])
    @jwt_required()
    def logout():
        response = jsonify({"msg": "Logout successful"})
        unset_jwt_cookies(response)
        return response

    @app.route('/auth/me', methods=['GET'])
    @jwt_required()
    def get_current_user():
        current_user_id = get_jwt_identity()
        user = User.query.get(current_user_id)
        if user:
            return jsonify(user.to_dict())
        return jsonify({'error': 'User not found'}), 404

    # User Management
    @app.route('/users', methods=['GET'])
    @admin_required
    def get_users():
        users = User.query.all()
        return jsonify([user.to_dict() for user in users])

    @app.route('/users', methods=['POST'])
    @admin_required
    def create_user():
        data = request.get_json()
        username = data.get('username', '').strip().lower()
        password = data.get('password')
        role = data.get('role', 'user')
        if not username or not password:
            return jsonify({'error': 'Username and password required'}), 400
        if User.query.filter_by(username=username).first():
            return jsonify({'error': 'Username already exists'}), 400
        user = User(username=username, role=role)
        user.set_password(password)
        db.session.add(user)
        db.session.commit()
        return jsonify(user.to_dict()), 201

    @app.route('/users/<int:user_id>', methods=['PUT'])
    @admin_required
    def update_user(user_id):
        user = User.query.get_or_404(user_id)
        data = request.get_json()
        if 'username' in data:
            existing = User.query.filter_by(username=data['username']).first()
            if existing and existing.id != user_id:
                return jsonify({'error': 'Username already exists'}), 400
            user.username = data['username']
        if 'role' in data:
            user.role = data['role']
        db.session.commit()
        return jsonify(user.to_dict())

    @app.route('/users/<int:user_id>', methods=['DELETE'])
    @admin_required
    def delete_user(user_id):
        user = User.query.get_or_404(user_id)
        db.session.delete(user)
        db.session.commit()
        return jsonify({'message': 'User deleted successfully'})

    @app.route('/users/<int:user_id>/change-password', methods=['POST'])
    @admin_required
    def change_password(user_id):
        user = User.query.get_or_404(user_id)
        new_password = request.get_json().get('password')
        if not new_password:
            return jsonify({'error': 'Password required'}), 400
        user.set_password(new_password)
        db.session.commit()
        return jsonify({'message': 'Password changed successfully'})

    # Register Blueprints
    from modules.system_info.api import system_info_bp
    from modules.rclone_drive import rclone_drive_bp
    from modules.minecraft_server import minecraft_server_bp
    from modules.minecraft_mods import minecraft_mods_bp
    from backend.modules.websocket_mod_bridge import minecraft_ws_bp

    app.register_blueprint(system_info_bp, url_prefix='/modules')
    app.register_blueprint(rclone_drive_bp)
    app.register_blueprint(minecraft_server_bp)
    app.register_blueprint(minecraft_mods_bp, url_prefix='/mods')
    app.register_blueprint(minecraft_ws_bp)

    @app.route('/health')
    def health_check():
        return jsonify({'status': 'healthy', 'timestamp': datetime.utcnow().isoformat()})

    @app.errorhandler(404)
    def not_found(e):
        return jsonify({'error': 'Not found'}), 404

    @app.errorhandler(500)
    def internal_error(e):
        db.session.rollback()
        logger.error(f"Internal error: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500

    with app.app_context():
        db.create_all()
        if not User.query.filter_by(username='admin').first():
            admin = User(username='admin', role='admin')
            admin.set_password('admin')
            db.session.add(admin)
            db.session.commit()
            logger.info("Created default admin user")
        logger.info("Database initialized successfully")

    return app


def get_mod_only_app():
    app = Flask(__name__)
    app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('DATABASE_URL', 'sqlite:///dashboard.db')
    app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

    db.init_app(app)

    from modules.minecraft_mods import mod_bp
    app.register_blueprint(mod_bp, url_prefix='/mod')

    from backend.modules.websocket_mod_bridge import minecraft_ws_bp
    app.register_blueprint(minecraft_ws_bp)

    return app


# Dev mode entry
if __name__ == '__main__':
    app = create_app()
    port = int(os.getenv('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=True)
