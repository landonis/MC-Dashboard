#!/usr/bin/env python3

import os
from dotenv import load_dotenv
from backend.app import create_app

# Load environment variables
load_dotenv()

# Create the main Flask app
app = create_app()

# Local development entry point
if __name__ == '__main__':
    port = int(os.getenv('PORT', 5000))
    debug = os.getenv('FLASK_DEBUG', 'False').lower() == 'true'
    app.run(host='0.0.0.0', port=port, debug=debug)
