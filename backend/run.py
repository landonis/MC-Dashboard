#!/usr/bin/env python3
"""
Dashboard Backend Runner

This script properly sets up the Python path and runs the Flask application
with all modules correctly imported.
"""

import os
import sys
sys.path.append(os.path.abspath(os.path.dirname(__file__)))
# Add the project root to Python path
project_root = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(project_root)
sys.path.insert(0, parent_dir)
sys.path.insert(0, project_root)

# Now import and run the app
from app import app, init_db, logger

if __name__ == '__main__':
    with app.app_context():
        init_db()
    port = int(os.getenv('PORT', 5000))
    debug = os.getenv('FLASK_DEBUG', 'False').lower() == 'true'
    
    logger.info(f"Starting Flask application on port {port}")
    logger.info(f"Python path: {sys.path}")
    app.run(host='0.0.0.0', port=port, debug=debug)
