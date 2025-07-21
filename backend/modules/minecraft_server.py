import os
import subprocess
import json
import requests
from datetime import datetime
from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity
from functools import wraps
import logging

logger = logging.getLogger(__name__)

from dotenv import load_dotenv
load_dotenv()

MINECRAFT_DIR = os.getenv("MINECRAFT_DIR", "/opt/minecraft")
MINECRAFT_USER = os.getenv("MINECRAFT_USER", "minecraft")

# Create blueprint
minecraft_server_bp = Blueprint('minecraft_server', __name__, url_prefix='/server')

def get_minecraft_server_jar_url(version: str) -> str:
    try:
        manifest_url = "https://launchermeta.mojang.com/mc/game/version_manifest.json"
        manifest = requests.get(manifest_url, timeout=10).json()

        version_info = next((v for v in manifest["versions"] if v["id"] == version), None)
        if not version_info:
            raise ValueError(f"Version {version} not found in Mojang manifest")

        version_metadata = requests.get(version_info["url"], timeout=10).json()
        return version_metadata["downloads"]["server"]["url"]
    except Exception as e:
        raise RuntimeError(f"Failed to get server.jar URL for {version}: {e}")

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



@minecraft_server_bp.route('/status', methods=['GET'])
@admin_required
def get_server_status():
    """Get Minecraft server status"""
    try:
        # Check systemctl status
        status_result = run_command("/usr/bin/systemctl is-active minecraft.service")
        is_running = status_result['success'] and status_result['stdout'].strip() == 'active'
        
        # Get detailed status
        detail_result = run_command("/usr/bin/systemctl status minecraft.service --no-pager -l")
        
        # Check if server directory exists
        service_exists = os.path.exists('/etc/systemd/system/minecraft.service')
        server_exists = os.path.exists('/opt/minecraft/server.jar') and service_exists
        
        # Get world info if server exists
        world_info = {}
        if server_exists:
            world_path = '/opt/minecraft/world'
            if os.path.exists(world_path):
                world_info = {
                    'exists': True,
                    'name': 'world',
                    'size': get_directory_size(world_path)
                }
            else:
                world_info = {'exists': False}
        
        # Get memory usage if running
        memory_info = {}
        if is_running:
            mem_result = run_command("/usr/bin/ps aux | /usr/bin/grep java | /usr/bin/grep -i minecraft | /usr/bin/awk '{print $6}'")
            if mem_result['success'] and mem_result['stdout'].strip():
                try:
                    memory_kb = int(mem_result['stdout'].strip())
                    memory_info = {
                        'used_mb': round(memory_kb / 1024, 2),
                        'used_gb': round(memory_kb / (1024 * 1024), 2)
                    }
                except ValueError:
                    memory_info = {'used_mb': 0, 'used_gb': 0}
        
        return jsonify({
            'running': is_running,
            'server_exists': server_exists,
            'world_info': world_info,
            'memory_info': memory_info,
            'status_output': detail_result['stdout'],
            'service_enabled': check_service_enabled()
        })
    
    except Exception as e:
        logger.error(f"Status error: {str(e)}")
        return jsonify({'error': 'Failed to get server status'}), 500

@minecraft_server_bp.route('/enable', methods=['POST'])
@admin_required
def enable_service():
    """Enable the Minecraft systemd service"""
    try:
        run_command(f"/bin/sudo /usr/bin/systemctl daemon-reload")
        result = run_command(f"/bin/sudo /usr/bin/systemctl enable minecraft.service")

        if result['success']:
            return jsonify({'message': 'Minecraft service enabled successfully'})
        else:
            return jsonify({'error': f"Failed to enable service: {result['stderr']}"}), 500

    except Exception as e:
        logger.error(f"Enable service error: {str(e)}")
        return jsonify({'error': f"Failed to enable service: {e}"}), 500


@minecraft_server_bp.route('/disable', methods=['POST'])
@admin_required
def disable_service():
    """Disable the Minecraft systemd service"""
    try:
        run_command(f"/bin/sudo /usr/bin/systemctl daemon-reload")
        result = run_command(f"/bin/sudo /usr/bin/systemctl disable minecraft.service")

        if result['success']:
            return jsonify({'message': 'Minecraft service disabled successfully'})
        else:
            return jsonify({'error': f'Failed to disable service: {result["stderr"]}'}), 500

    except Exception as e:
        logger.error(f"Disable service error: {str(e)}")
        return jsonify({'error': 'Failed to disable service'}), 500


@minecraft_server_bp.route('/start', methods=['POST'])
@admin_required
def start_server():
    """Start Minecraft server"""
    try:
        if not os.path.exists('/opt/minecraft'):
            return jsonify({'error': 'Server not built yet'}), 400
        
        result = run_command(f"/bin/sudo /usr/bin/systemctl start minecraft.service")
        
        if result['success']:
            return jsonify({'message': 'Server started successfully'})
        else:
            return jsonify({
                'error': f'Failed to start server, user: {os.getlogin()}: {result["stderr"]}'
            }), 500
    
    except Exception as e:
        logger.error(f"Start server error: {str(e)}")
        return jsonify({'error': 'Failed to start server, user: {os.getlogin()}'}), 500

@minecraft_server_bp.route('/stop', methods=['POST'])
@admin_required
def stop_server():
    """Stop Minecraft server"""
    try:
        result = run_command(f"/bin/sudo /usr/bin/systemctl stop minecraft.service")
        
        if result['success']:
            return jsonify({'message': 'Server stopped successfully'})
        else:
            return jsonify({
                'error': f'Failed to stop server: {result["stderr"]}'
            }), 500
    
    except Exception as e:
        logger.error(f"Stop server error: {str(e)}")
        return jsonify({'error': 'Failed to stop server'}), 500

@minecraft_server_bp.route('/restart', methods=['POST'])
@admin_required
def restart_server():
    """Restart Minecraft server"""
    try:
        result = run_command(f"/bin/sudo /usr/bin/systemctl restart minecraft.service")
        
        if result['success']:
            return jsonify({'message': 'Server restarted successfully'})
        else:
            return jsonify({
                'error': f'Failed to restart server: {result["stderr"]}'
            }), 500
    
    except Exception as e:
        logger.error(f"Restart server error: {str(e)}")
        return jsonify({'error': 'Failed to restart server'}), 500

@minecraft_server_bp.route('/build', methods=['POST'])
@admin_required
def build_server():
    """Build new Minecraft server"""
    try:
        data = request.get_json()
        minecraft_version = data.get('minecraft_version', '1.20.1')
        fabric_version = data.get('fabric_version', '0.16.10')
        installer_version = '1.0.3'
        memory_gb = data.get('memory_gb', 10)
        
        build_log = []
        
        # Stop existing server if running
        build_log.append("Stopping existing server...")
        run_command(f"/bin/sudo /usr/bin/systemctl stop minecraft.service")
        
        # Ensure minecraft directory exists
        build_log.append("Setting up minecraft directory...")
        run_command(f"/bin/mkdir -p {MINECRAFT_DIR}")
        
        # Download Minecraft Server Jar

        url = get_minecraft_server_jar_url(minecraft_version)
        dl_result = run_command(f"/usr/bin/curl -L -o '{MINECRAFT_DIR}/server.jar' '{url}'")
        if not dl_result['success']:
            return jsonify({
                'error': f'Failed to download Minecraft Server: {dl_result["stderr"]}',
                'log': build_log
            }), 500
        
        # Download Fabric installer
        build_log.append(f"Downloading Fabric installer for Minecraft {minecraft_version}...")
        fabric_url = f"https://meta.fabricmc.net/v2/versions/loader/{minecraft_version}/{fabric_version}/{installer_version}/server/jar"
        
        installer_path = f"{MINECRAFT_DIR}/fabric-installer-{fabric_version}.jar"
        download_result = run_command(f"/usr/bin/curl -L -o '{installer_path}' '{fabric_url}'")
        
        if not download_result['success']:
            return jsonify({
                'error': f'Failed to download Fabric installer: {download_result["stderr"]}',
                'log': build_log
            }), 500
        
        # Accept EULA
        build_log.append("Accepting Minecraft EULA...")
        with open(f'{MINECRAFT_DIR}/eula.txt', 'w') as f:
            f.write('eula=true\n')
        
        # Create server.properties
        build_log.append("Creating server configuration...")
        server_properties = f"""
server-port=25565
gamemode=survival
difficulty=normal
spawn-protection=16
max-players=20
online-mode=true
white-list=false
motd=Minecraft Server managed by Dashboard
"""
        with open(f'{MINECRAFT_DIR}/server.properties', 'w') as f:
            f.write(server_properties.strip())
        
        # Create systemd service
        build_log.append("Creating systemd service...")
        service_content = f"""[Unit]
Description=Minecraft Server
After=network.target

[Service]
Type=simple
User={MINECRAFT_USER}
Group={MINECRAFT_USER}
WorkingDirectory={MINECRAFT_DIR}
ExecStart=/usr/bin/java -Xmx{memory_gb}G -jar {MINECRAFT_DIR}/fabric-installer-{fabric_version}.jar nogui
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
"""
        
        temp_service_path = f"{MINECRAFT_DIR}/minecraft.service"
        with open(temp_service_path, 'w') as f:
            f.write(service_content)

        run_command(f"/bin/cp {temp_service_path} /etc/systemd/system/minecraft.service")
        os.remove(temp_service_path)

        # Set permissions
        build_log.append("Setting permissions...")
        run_command(f"/bin/chown -R {MINECRAFT_USER}:{MINECRAFT_USER} {MINECRAFT_DIR}")
        run_command(f"/bin/chmod -R 755 {MINECRAFT_DIR}")
        
        # Reload systemd and enable service
        build_log.append("Enabling systemd service...")
        run_command("/bin/sudo /usr/bin/systemctl daemon-reload")
        run_command("/bin/sudo /usr/bin/systemctl enable minecraft.service")


        
        build_log.append("Server build completed successfully!")
        
        return jsonify({
            'message': 'Server built successfully',
            'log': build_log,
            'minecraft_version': minecraft_version,
            'fabric_version': fabric_version,
            'memory_gb': memory_gb
        })
    
    except Exception as e:
        logger.error(f"Build server error: {str(e)}")
        return jsonify({
            'error': f'Build failed: {str(e)}',
            'log': build_log if 'build_log' in locals() else []
        }), 500

@minecraft_server_bp.route('/versions', methods=['GET'])
@admin_required
def get_versions():
    """Get available Minecraft and Fabric versions"""
    try:
        # Get Minecraft versions from Mojang API
        minecraft_versions = []
        try:
            import requests
            response = requests.get('https://launchermeta.mojang.com/mc/game/version_manifest.json', timeout=10)
            if response.status_code == 200:
                data = response.json()
                # Get release versions only (not snapshots)
                minecraft_versions = [
                    version['id'] for version in data['versions'] 
                    if version['type'] == 'release'
                ][:20]  # Limit to latest 20 releases
            else:
                # Fallback versions if API fails
                minecraft_versions = [
                    '1.20.4', '1.20.3', '1.20.2', '1.20.1', '1.20',
                    '1.19.4', '1.19.3', '1.19.2', '1.19.1', '1.19'
                ]
        except Exception as e:
            logger.warning(f"Failed to fetch Minecraft versions: {str(e)}")
            minecraft_versions = [
                '1.20.4', '1.20.3', '1.20.2', '1.20.1', '1.20',
                '1.19.4', '1.19.3', '1.19.2', '1.19.1', '1.19'
            ]
        
        # Get Fabric versions from Fabric API
        fabric_versions = []
        try:
            import requests
            response = requests.get('https://meta.fabricmc.net/v2/versions/loader', timeout=10)
            if response.status_code == 200:
                data = response.json()
                # Get stable versions only
                fabric_versions = [
                    version['version'] for version in data 
                    if version.get('stable', False)
                ][:15]  # Limit to latest 15 stable versions
            else:
                # Fallback versions if API fails
                fabric_versions = [
                    '0.15.3', '0.15.2', '0.15.1', '0.15.0',
                    '0.14.24', '0.14.23', '0.14.22', '0.14.21'
                ]
        except Exception as e:
            logger.warning(f"Failed to fetch Fabric versions: {str(e)}")
            fabric_versions = [
                '0.15.3', '0.15.2', '0.15.1', '0.15.0',
                '0.14.24', '0.14.23', '0.14.22', '0.14.21'
            ]
        
        return jsonify({
            'minecraft_versions': minecraft_versions,
            'fabric_versions': fabric_versions
        })
    
    except Exception as e:
        logger.error(f"Get versions error: {str(e)}")

def get_directory_size(path):
    """Get directory size in MB"""
    try:
        total_bytes = 0
        for dirpath, dirnames, filenames in os.walk(path):
            for f in filenames:
                fp = os.path.join(dirpath, f)
                try:
                    total_bytes += os.path.getsize(fp)
                except Exception as fe:
                    logger.debug(f"Skipping {fp}: {fe}")

        total_mb = round(total_bytes / (1024 * 1024), 2)
        logger.debug(f"[walk] Calculated size for {path}: {total_mb} MB")
        return total_mb
    except Exception as e:
        logger.error(f"[walk] Failed to calculate size of {path}: {e}")
        return 0

    return 0

def check_service_enabled():
    """Check if minecraft service is enabled"""
    try:
        result = run_command("/usr/bin/systemctl is-enabled minecraft.service")
        return result['success'] and result['stdout'].strip() == 'enabled'
    except:
        return False
