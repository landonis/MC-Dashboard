from flask_sqlalchemy import SQLAlchemy
from datetime import datetime
from werkzeug.security import generate_password_hash, check_password_hash

db = SQLAlchemy()

class User(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(80), unique=True, nullable=False)
    password_hash = db.Column(db.String(120), nullable=False)
    role = db.Column(db.String(20), nullable=False, default='user')
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    failed_login_attempts = db.Column(db.Integer, default=0)
    locked_until = db.Column(db.DateTime, nullable=True)

    def set_password(self, password):
        self.password_hash = generate_password_hash(password)

    def check_password(self, password):
        return check_password_hash(self.password_hash, password)
    
    def is_locked(self):
        """Check if account is currently locked"""
        if self.locked_until is None:
            return False
        return datetime.utcnow() < self.locked_until
    
    def reset_failed_attempts(self):
        """Reset failed login attempts and unlock account"""
        self.failed_login_attempts = 0
        self.locked_until = None
    
    def increment_failed_attempts(self, max_attempts=5, lockout_hours=1):
        """Increment failed attempts and lock if threshold reached"""
        self.failed_login_attempts += 1
        if self.failed_login_attempts >= max_attempts:
            self.locked_until = datetime.utcnow() + timedelta(hours=lockout_hours)

    def to_dict(self):
        return {
            'id': self.id,
            'username': self.username,
            'role': self.role,
            'created_at': self.created_at.isoformat(),
            'failed_login_attempts': self.failed_login_attempts,
            'is_locked': self.is_locked()
        }
