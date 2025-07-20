import os
import json
import subprocess
import requests
import shutil
from datetime import datetime
from flask import Blueprint, request, jsonify, current_app
from flask_jwt_extended import jwt_required, get_jwt_identity
from functools import wraps
import logging
from werkzeug.utils import secure_filename

logger = logging.getLogger(__name__)

from dotenv import load_dotenv
load_dotenv()

MINECRAFT_DIR = os.getenv("MINECRAFT_DIR", "/opt/minecraft")
MINECRAFT_USER = os.getenv("MINECRAFT_USER", "minecraft")
MODS_DIR = os.path.join(MINECRAFT_DIR, "mods")
DISABLED_MODS_DIR = os.path.join(MINECRAFT_DIR, "mods", "disabled")

# Create blueprint
minecraft_mods_bp = Blueprint('minecraft_mods', __name__, url_prefix='/mods')

def admin_required(f):
    @wraps(f)
    @jwt_required()
    def decorated_function(*args, **kwargs):
        from models import User
        current_user_id = get_jwt_identity()
        user = User.query.get(current_user_id)
        if not user or user.role != 'admin':
            return jsonify({'error': 'Admin access required'}), 403
        return f(*args, **kwargs)
    return decorated_function

def run_command(cmd, cwd=None):
    """Run shell command safely and return result"""
    try:
        result = subprocess.run(
            cmd, 
            shell=True, 
            capture_output=True, 
            text=True, 
            cwd=cwd,
            timeout=300
        )
        return {
            'success': result.returncode == 0,
            'stdout': result.stdout,
            'stderr': result.stderr,
            'returncode': result.returncode
        }
    except subprocess.TimeoutExpired:
        return {
            'success': False,
            'stdout': '',
            'stderr': 'Command timed out',
            'returncode': -1
        }
    except Exception as e:
        return {
            'success': False,
            'stdout': '',
            'stderr': str(e),
            'returncode': -1
        }

def ensure_mods_directory():
    """Ensure mods and disabled mods directories exist"""
    try:
        os.makedirs(MODS_DIR, exist_ok=True)
        os.makedirs(DISABLED_MODS_DIR, exist_ok=True)
        
        # Set proper permissions
        run_command(f"/usr/bin/chown -R {MINECRAFT_USER}:{MINECRAFT_USER} {MODS_DIR}")
        run_command(f"/usr/bin/chmod -R 755 {MODS_DIR}")
        
        return True
    except Exception as e:
        logger.error(f"Failed to create mods directory: {str(e)}")
        return False

def check_dashboard_mod_installed():
    """Check if dashboard mod is installed"""
    try:
        if not os.path.exists(MODS_DIR):
            return False
        
        for filename in os.listdir(MODS_DIR):
            if filename.endswith('.jar') and 'dashboard-mod' in filename.lower():
                return True
        return False
    except Exception as e:
        logger.error(f"Failed to check dashboard mod: {str(e)}")
        return False

def get_dashboard_mod_download_url():
    """Get dashboard mod download URL - placeholder for GitHub release or internal location"""
    # This would typically fetch from GitHub releases API or internal repository
    # For now, using a placeholder URL structure
    return {
        'url': 'https://github.com/landonis/dashboard-mod/releases/latest/download/dashboard-mod-1.0.0.jar',
        'filename': 'dashboard-mod-1.0.0.jar',
        'version': '1.0.0'
    }

def get_fabric_api_download_url(minecraft_version="1.20.1"):
    """Get latest Fabric API download URL from Modrinth"""
    try:
        # Search for Fabric API on Modrinth
        search_url = "https://api.modrinth.com/v2/search"
        params = {
            "query": "fabric-api",
            "facets": f'[["project_type:mod"],["categories:fabric"],["versions:{minecraft_version}"]]',
            "limit": 1
        }
        
        response = requests.get(search_url, params=params, timeout=10)
        if response.status_code != 200:
            raise Exception(f"Search failed: {response.status_code}")
        
        search_data = response.json()
        if not search_data.get('hits'):
            raise Exception("Fabric API not found in search results")
        
        project_id = search_data['hits'][0]['project_id']
        
        # Get project versions
        versions_url = f"https://api.modrinth.com/v2/project/{project_id}/version"
        params = {
            "game_versions": f'["{minecraft_version}"]',
            "loaders": '["fabric"]'
        }
        
        response = requests.get(versions_url, params=params, timeout=10)
        if response.status_code != 200:
            raise Exception(f"Versions fetch failed: {response.status_code}")
        
        versions = response.json()
        if not versions:
            raise Exception("No compatible Fabric API versions found")
        
        # Get the latest version
        latest_version = versions[0]
        if not latest_version.get('files'):
            raise Exception("No files found for latest version")
        
        # Find the primary file
        primary_file = None
        for file in latest_version['files']:
            if file.get('primary', False):
                primary_file = file
                break
        
        if not primary_file:
            primary_file = latest_version['files'][0]
        
        return {
            'url': primary_file['url'],
            'filename': primary_file['filename'],
            'version': latest_version['version_number']
        }
        
    except Exception as e:
        logger.error(f"Failed to get Fabric API download URL: {str(e)}")
        return None

@minecraft_mods_bp.route('/status', methods=['GET'])
@admin_required
def get_mods_status():
    """Get mods directory status and mod list"""
    try:
        world_exists = os.path.exists(os.path.join(MINECRAFT_DIR, "world"))
        
        if not world_exists:
            return jsonify({
                'world_exists': False,
                'mods': [],
                'disabled_mods': [],
                'fabric_api_installed': False
            })
        
        # Ensure mods directory exists
        ensure_mods_directory()
        
        # Get list of mod files
        mods = []
        disabled_mods = []
        fabric_api_installed = False
        
        if os.path.exists(MODS_DIR):
            for filename in os.listdir(MODS_DIR):
                if filename.endswith('.jar') and os.path.isfile(os.path.join(MODS_DIR, filename)):
                    file_path = os.path.join(MODS_DIR, filename)
                    file_size = os.path.getsize(file_path)
                    file_modified = datetime.fromtimestamp(os.path.getmtime(file_path))
                    
                    mod_info = {
                        'filename': filename,
                        'size': file_size,
                        'modified': file_modified.isoformat(),
                        'is_fabric_api': 'fabric-api' in filename.lower()
                    }
                    
                    if mod_info['is_fabric_api']:
                        fabric_api_installed = True
                    
                    mods.append(mod_info)
        
        if os.path.exists(DISABLED_MODS_DIR):
            for filename in os.listdir(DISABLED_MODS_DIR):
                if filename.endswith('.jar') and os.path.isfile(os.path.join(DISABLED_MODS_DIR, filename)):
                    file_path = os.path.join(DISABLED_MODS_DIR, filename)
                    file_size = os.path.getsize(file_path)
                    file_modified = datetime.fromtimestamp(os.path.getmtime(file_path))
                    
                    disabled_mods.append({
                        'filename': filename,
                        'size': file_size,
                        'modified': file_modified.isoformat()
                    })
        
        # Sort by modification time (newest first)
        mods.sort(key=lambda x: x['modified'], reverse=True)
        disabled_mods.sort(key=lambda x: x['modified'], reverse=True)
        
        return jsonify({
            'world_exists': True,
            'mods': mods,
            'disabled_mods': disabled_mods,
            'fabric_api_installed': fabric_api_installed,
            'dashboard_mod_installed': check_dashboard_mod_installed(),
            'mods_count': len(mods),
            'disabled_count': len(disabled_mods)
        })
        
    except Exception as e:
        logger.error(f"Get mods status error: {str(e)}")
        return jsonify({'error': 'Failed to get mods status'}), 500

@minecraft_mods_bp.route('/upload', methods=['POST'])
@admin_required
def upload_mod():
    """Upload a mod file to the mods directory"""
    try:
        if 'file' not in request.files:
            return jsonify({'error': 'No file provided'}), 400
        
        file = request.files['file']
        if file.filename == '':
            return jsonify({'error': 'No file selected'}), 400
        
        if not file.filename.endswith('.jar'):
            return jsonify({'error': 'File must be a .jar file'}), 400
        
        # Ensure mods directory exists
        if not ensure_mods_directory():
            return jsonify({'error': 'Failed to create mods directory'}), 500
        
        # Secure the filename
        filename = secure_filename(file.filename)
        if not filename.endswith('.jar'):
            filename += '.jar'
        
        file_path = os.path.join(MODS_DIR, filename)
        
        # Check if file already exists
        if os.path.exists(file_path):
            return jsonify({'error': f'Mod {filename} already exists'}), 400
        
        # Save the file
        file.save(file_path)
        
        # Set proper permissions
        run_command(f"/usr/bin/chown {MINECRAFT_USER}:{MINECRAFT_USER} '{file_path}'")
        run_command(f"/usr/bin/chmod 644 '{file_path}'")
        
        logger.info(f"Mod uploaded successfully: {filename}")
        return jsonify({
            'message': f'Mod {filename} uploaded successfully',
            'filename': filename
        })
        
    except Exception as e:
        logger.error(f"Upload mod error: {str(e)}")
        return jsonify({'error': 'Failed to upload mod'}), 500

@minecraft_mods_bp.route('/install-fabric-api', methods=['POST'])
@admin_required
def install_fabric_api():
    """Install Fabric API from Modrinth"""
    try:
        # Ensure mods directory exists
        if not ensure_mods_directory():
            return jsonify({'error': 'Failed to create mods directory'}), 500
        
        # Get Fabric API download info
        fabric_info = get_fabric_api_download_url()
        if not fabric_info:
            return jsonify({'error': 'Failed to get Fabric API download information'}), 500
        
        # Download Fabric API
        response = requests.get(fabric_info['url'], timeout=60)
        if response.status_code != 200:
            return jsonify({'error': 'Failed to download Fabric API'}), 500
        
        # Save to mods directory
        file_path = os.path.join(MODS_DIR, fabric_info['filename'])
        with open(file_path, 'wb') as f:
            f.write(response.content)
        
        # Set proper permissions
        run_command(f"/usr/bin/chown {MINECRAFT_USER}:{MINECRAFT_USER} '{file_path}'")
        run_command(f"/usr/bin/chmod 644 '{file_path}'")
        
        logger.info(f"Fabric API installed successfully: {fabric_info['filename']}")
        return jsonify({
            'message': f'Fabric API {fabric_info["version"]} installed successfully',
            'filename': fabric_info['filename'],
            'version': fabric_info['version']
        })
        
    except Exception as e:
        logger.error(f"Install Fabric API error: {str(e)}")
        return jsonify({'error': 'Failed to install Fabric API'}), 500

@minecraft_mods_bp.route('/check-dashboard-mod', methods=['GET'])
@admin_required
def check_dashboard_mod():
    """Check if dashboard mod is installed"""
    try:
        installed = check_dashboard_mod_installed()
        return jsonify({
            'installed': installed,
            'message': 'Dashboard mod is installed' if installed else 'Dashboard mod not found'
        })
    except Exception as e:
        logger.error(f"Check dashboard mod error: {str(e)}")
        return jsonify({'error': 'Failed to check dashboard mod'}), 500

@minecraft_mods_bp.route('/install-dashboard-mod', methods=['POST'])
@admin_required
def install_dashboard_mod():
    """Install dashboard mod from GitHub release or internal location"""
    try:
        # Ensure mods directory exists
        if not ensure_mods_directory():
            return jsonify({'error': 'Failed to create mods directory'}), 500
        
        # Check if already installed
        if check_dashboard_mod_installed():
            return jsonify({'error': 'Dashboard mod is already installed'}), 400
        
        # Get download information
        mod_info = get_dashboard_mod_download_url()
        if not mod_info:
            return jsonify({'error': 'Failed to get dashboard mod download information'}), 500
        
        # Download dashboard mod
        try:
            response = requests.get(mod_info['url'], timeout=60)
            if response.status_code == 200:
                # Save to mods directory
                file_path = os.path.join(MODS_DIR, mod_info['filename'])
                with open(file_path, 'wb') as f:
                    f.write(response.content)
                
                # Set proper permissions
                run_command(f"/usr/bin/chown {MINECRAFT_USER}:{MINECRAFT_USER} '{file_path}'")
                run_command(f"/usr/bin/chmod 644 '{file_path}'")
                
                logger.info(f"Dashboard mod installed successfully: {mod_info['filename']}")
                return jsonify({
                    'message': f'Dashboard mod {mod_info["version"]} installed successfully',
                    'filename': mod_info['filename'],
                    'version': mod_info['version']
                })
            else:
                return jsonify({'error': f'Download failed with status {response.status_code}'}), 500
        except requests.RequestException as e:
            logger.error(f"Download error: {str(e)}")
            return jsonify({'error': 'Failed to download dashboard mod - mod may not be available yet'}), 500
        
    except Exception as e:
        logger.error(f"Install dashboard mod error: {str(e)}")
        return jsonify({'error': 'Failed to install dashboard mod'}), 500

@minecraft_mods_bp.route('/delete', methods=['POST'])
@admin_required
def delete_mod():
    """Delete a mod file"""
    try:
        data = request.get_json()
        filename = data.get('filename')
        
        if not filename:
            return jsonify({'error': 'Filename required'}), 400
        
        if not filename.endswith('.jar'):
            return jsonify({'error': 'Invalid file type'}), 400
        
        file_path = os.path.join(MODS_DIR, filename)
        
        if not os.path.exists(file_path):
            return jsonify({'error': 'Mod file not found'}), 404
        
        os.remove(file_path)
        
        logger.info(f"Mod deleted successfully: {filename}")
        return jsonify({'message': f'Mod {filename} deleted successfully'})
        
    except Exception as e:
        logger.error(f"Delete mod error: {str(e)}")
        return jsonify({'error': 'Failed to delete mod'}), 500

@minecraft_mods_bp.route('/enable', methods=['POST'])
@admin_required
def enable_mod():
    """Move a mod from disabled back to mods directory"""
    try:
        data = request.get_json()
        filename = data.get('filename')
        
        if not filename:
            return jsonify({'error': 'Filename required'}), 400
        
        disabled_path = os.path.join(DISABLED_MODS_DIR, filename)
        enabled_path = os.path.join(MODS_DIR, filename)
        
        if not os.path.exists(disabled_path):
            return jsonify({'error': 'Disabled mod file not found'}), 404
        
        if os.path.exists(enabled_path):
            return jsonify({'error': 'Mod already exists in mods directory'}), 400
        
        shutil.move(disabled_path, enabled_path)
        
        # Set proper permissions
        run_command(f"/usr/bin/chown {MINECRAFT_USER}:{MINECRAFT_USER} '{enabled_path}'")
        run_command(f"/usr/bin/chmod 644 '{enabled_path}'")
        
        logger.info(f"Mod enabled successfully: {filename}")
        return jsonify({'message': f'Mod {filename} enabled successfully'})
        
    except Exception as e:
        logger.error(f"Enable mod error: {str(e)}")
        return jsonify({'error': 'Failed to enable mod'}), 500

@minecraft_mods_bp.route('/restart-with-recovery', methods=['POST'])
@admin_required
def restart_server_with_recovery():
    """Restart Minecraft server with automatic mod recovery on failure"""
    try:
        recovery_log = []
        max_attempts = 3
        
        for attempt in range(max_attempts):
            recovery_log.append(f"Attempt {attempt + 1}: Starting server...")
            
            # Stop server first
            stop_result = run_command("/usr/bin/systemctl stop minecraft.service")
            if not stop_result['success']:
                recovery_log.append(f"Warning: Failed to stop server cleanly")
            
            # Start server
            start_result = run_command("/usr/bin/systemctl start minecraft.service")
            if not start_result['success']:
                recovery_log.append(f"Attempt {attempt + 1}: Server failed to start")
                
                if attempt < max_attempts - 1:  # Not the last attempt
                    # Find most recently added mod
                    mods = []
                    if os.path.exists(MODS_DIR):
                        for filename in os.listdir(MODS_DIR):
                            if filename.endswith('.jar'):
                                file_path = os.path.join(MODS_DIR, filename)
                                if os.path.isfile(file_path):
                                    mods.append({
                                        'filename': filename,
                                        'modified': os.path.getmtime(file_path)
                                    })
                    
                    if mods:
                        # Sort by modification time (newest first)
                        mods.sort(key=lambda x: x['modified'], reverse=True)
                        newest_mod = mods[0]['filename']
                        
                        # Move to disabled directory
                        ensure_mods_directory()
                        src_path = os.path.join(MODS_DIR, newest_mod)
                        dst_path = os.path.join(DISABLED_MODS_DIR, newest_mod)
                        
                        try:
                            shutil.move(src_path, dst_path)
                            recovery_log.append(f"Disabled mod: {newest_mod}")
                        except Exception as e:
                            recovery_log.append(f"Failed to disable mod {newest_mod}: {str(e)}")
                    else:
                        recovery_log.append("No mods found to disable")
                        break
                else:
                    recovery_log.append("Max recovery attempts reached")
                    break
            else:
                recovery_log.append(f"Attempt {attempt + 1}: Server started successfully")
                
                # Wait a moment and check if it's still running
                import time
                time.sleep(5)
                
                status_result = run_command("/usr/bin/systemctl is-active minecraft.service")
                if status_result['success'] and status_result['stdout'].strip() == 'active':
                    recovery_log.append("Server is running stable")
                    return jsonify({
                        'success': True,
                        'message': 'Server restarted successfully',
                        'recovery_log': recovery_log,
                        'attempts': attempt + 1
                    })
                else:
                    recovery_log.append("Server started but became unstable")
                    if attempt < max_attempts - 1:
                        continue
        
        # If we get here, all attempts failed
        return jsonify({
            'success': False,
            'message': 'Server restart failed after all recovery attempts',
            'recovery_log': recovery_log,
            'attempts': max_attempts
        }), 500
        
    except Exception as e:
        logger.error(f"Restart with recovery error: {str(e)}")
        return jsonify({'error': 'Failed to restart server with recovery'}), 500