"""
Dashboard Modules Package

This package contains all modular components for the dashboard application.
Each module should have its own subdirectory with:
- api.py: Flask blueprint with API routes
- __init__.py: Module initialization
- README.md: Module documentation
"""

# Import all available modules here
from . import system_info

# List of available modules
AVAILABLE_MODULES = [
    'system_info'
]

def get_available_modules():
    """Return list of available modules"""
    return AVAILABLE_MODULES
