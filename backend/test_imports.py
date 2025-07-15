#!/usr/bin/env python3
"""
Test script to verify all imports work correctly
"""

import os
import sys

# Add paths
project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
backend_dir = os.path.join(project_root, 'backend')
sys.path.insert(0, project_root)
sys.path.insert(0, backend_dir)

print("Testing imports...")
print(f"Project root: {project_root}")
print(f"Backend dir: {backend_dir}")
print(f"Python path: {sys.path}")

try:
    print("1. Testing app import...")
    from app import app, User
    print("   ✓ App and User imported successfully")
    
    print("2. Testing modules package...")
    import modules
    print("   ✓ Modules package imported successfully")
    
    print("3. Testing system_info module...")
    from modules.system_info import system_info_bp
    print("   ✓ System info module imported successfully")
    
    print("4. Testing module registration...")
    with app.app_context():
        print(f"   Registered blueprints: {[bp.name for bp in app.blueprints.values()]}")
    
    print("\n✅ All imports successful!")
    
except ImportError as e:
    print(f"❌ Import error: {e}")
    sys.exit(1)
except Exception as e:
    print(f"❌ General error: {e}")
    sys.exit(1)