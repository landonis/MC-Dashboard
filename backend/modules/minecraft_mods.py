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
SERVICE_USER = os.getenv("SERVICE_USER", "dashboardapp")
MODS_DIR = os.path.join(MINECRAFT_DIR, "mods")
DISABLED_MODS_DIR = os.path.join(MINECRAFT_DIR, "mods", "disabled")
SYSTEMD_SERVICE_PATH = "/etc/systemd/system/dashboard-backend.service"

def run_command(cmd, cwd=None):
    """Run shell command safely and return result"""
    try:
        env = os.environ.copy()
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

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


def create_backend_service():
    # Ensure gunicorn is installed system-wide
    gunicorn_check = run_command("/usr/bin/which gunicorn")
    if not gunicorn_check["success"] or not gunicorn_check["stdout"].strip():
        install_gunicorn = run_command("/bin/sudo /usr/bin/apt install gunicorn")
        if not install_gunicorn["success"]:
            return jsonify({"error": "Failed to install gunicorn: " + install_gunicorn["stderr"]}), 500

    service_content = f"""[Unit]
    Description=MC Dashboard Mod WebSocket Backend
    After=network.target
    
    [Service]
    User=dashboardapp
    WorkingDirectory=/opt/dashboard-app/backend
    ExecStart=/usr/local/bin/gunicorn backend.app:get_mod_only_app --bind 0.0.0.0:3020 -k uvicorn.workers.UvicornWorker
    Restart=always
    Environment=PYTHONUNBUFFERED=1
    
    [Install]
    WantedBy=multi-user.target
    """
    
    run_command(f"echo '{service_content}' | /bin/sudo /usr/bin/tee /etc/systemd/system/dashboard-mod.service > /dev/null")

    run_command("/bin/sudo /usr/bin/systemctl daemon-reload")
    run_command("/bin/sudo /usr/bin/systemctl enable dashboard-mod.service")
    run_command("/bin/sudo /usr/bin/systemctl start dashboard-mod.service")



def destroy_backend_service():
    result = run_command("/usr/bin/systemctl status dashboard-mod.service")
    if result["success"]:
        run_command("/bin/sudo /usr/bin/systemctl stop dashboard-mod.service")
        run_command("/bin/sudo /usr/bin/systemctl disable dashboard-mod.service")
        print("[Mod] Dashboard backend service stopped and disabled.")

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
    env = os.environ.copy()
    env['PATH'] += os.pathsep + '/bin'  # Ensure /bin is included
    try:
        result = subprocess.run(
            cmd, 
            shell=True, 
            capture_output=True, 
            text=True, 
            cwd=cwd,
            timeout=300,
            env=env
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
    # Updated to use local compilation instead of external download
    return {
        'url': 'local_compilation',
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

def compile_dashboard_mod():
    """Compile dashboard mod from source using Gradle"""
    try:
        # Get the project root directory (parent of backend)
        project_root = '/opt/dashboard-app'
        dashboard_mod_dir = os.path.join(project_root, 'dashboard-mod')
        
        if not os.path.exists(dashboard_mod_dir):
            raise Exception(f"Dashboard mod source directory not found: {dashboard_mod_dir}")

        
        if not os.path.exists(os.path.join(dashboard_mod_dir, 'build.gradle')):
            raise Exception("build.gradle not found in dashboard-mod directory")
        
        logger.info(f"Compiling dashboard mod from: {dashboard_mod_dir}")
        
        # Clean previous builds
        clean_result = run_command("/opt/gradle/gradle-8.8/bin/gradle clean", cwd=dashboard_mod_dir)
        if not clean_result['success']:
            logger.warning(f"Gradle clean failed: {clean_result['stderr']}")
        
        # Build the mod
        build_result = run_command("/opt/gradle/gradle-8.8/bin/gradle build", cwd=dashboard_mod_dir)
        if not build_result['success']:
            raise Exception(f"Gradle build failed: {build_result['stderr']}")
        
        # Find the built jar file
        libs_dir = os.path.join(dashboard_mod_dir, 'build', 'libs')
        if not os.path.exists(libs_dir):
            raise Exception("Build libs directory not found")
        
        jar_files = [f for f in os.listdir(libs_dir) if f.endswith('.jar') and 'sources' not in f]
        if not jar_files:
            raise Exception("No jar file found in build/libs directory")
        
        # Use the first jar file found (should be the main mod jar)
        jar_filename = jar_files[0]
        jar_path = os.path.join(libs_dir, jar_filename)
        
        logger.info(f"Found compiled jar: {jar_filename}")
        return {
            'jar_path': jar_path,
            'jar_filename': jar_filename,
            'success': True
        }
        
    except Exception as e:
        logger.error(f"Dashboard mod compilation failed: {str(e)}")
        return {
            'jar_path': None,
            'jar_filename': None,
            'success': False,
            'error': str(e)
        }

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
    """Compile and install dashboard mod from source"""
    try:
        # Ensure mods directory exists
        if not ensure_mods_directory():
            return jsonify({'error': 'Failed to create mods directory'}), 500
        
        # Check if already installed
        if check_dashboard_mod_installed():
            return jsonify({'error': 'Dashboard mod is already installed'}), 400
        
        logger.info("Starting dashboard mod compilation and installation")
        
        # Compile the dashboard mod
        compile_result = compile_dashboard_mod()
        if not compile_result['success']:
            return jsonify({'error': f'Compilation failed: {compile_result.get("error", "Unknown error")}'}), 500
        
        # Copy compiled jar to mods directory
        source_jar = compile_result['jar_path']
        target_filename = compile_result['jar_filename']
        target_path = os.path.join(MODS_DIR, target_filename)
        
        # Copy the file
        import shutil
        shutil.copy2(source_jar, target_path)
        
        # Set proper permissions
        run_command(f"/usr/bin/chown {MINECRAFT_USER}:{MINECRAFT_USER} '{target_path}'")
        run_command(f"/usr/bin/chmod 644 '{target_path}'")
        create_backend_service()
        
        logger.info(f"Dashboard mod compiled and installed successfully: {target_filename}")
        return jsonify({
            'message': f'Dashboard mod compiled and installed successfully',
            'filename': target_filename,
            'compiled': True
        })
        
    except Exception as e:
        logger.error(f"Install dashboard mod error: {str(e)}")

@minecraft_mods_bp.route('/delete', methods=['POST'])
@admin_required
def delete_mod():
    """Delete a mod file"""
    try:
        data = request.get_json()
        filename = data.get('filename')
        destroy_backend_service()
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
            stop_result = run_command("/bin/sudo /usr/bin/systemctl stop minecraft.service")
            if not stop_result['success']:
                recovery_log.append(f"Warning: Failed to stop server cleanly")
            
            # Start server
            start_result = run_command("/bin/sudo /usr/bin/systemctl start minecraft.service")
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
