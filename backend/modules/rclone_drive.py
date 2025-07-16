import os
import json
import subprocess
import tempfile
import zipfile
from datetime import datetime
from flask import Blueprint, request, jsonify, current_app
from flask_jwt_extended import jwt_required, get_jwt_identity
from functools import wraps
import logging


MINECRAFT_DIR = os.getenv("MINECRAFT_DIR", "/opt/minecraft")
MINECRAFT_USER = os.getenv("MINECRAFT_USER", "minecraft")
SERVICE_USER = os.getenv("SERVICE_USER", "dashboardapp")
logger = logging.getLogger(__name__)

# Create blueprint
rclone_drive_bp = Blueprint('rclone_drive', __name__, url_prefix='/drive')

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

@rclone_drive_bp.route('/upload', methods=['POST'])
@admin_required
def upload_file():
    """Upload file to Google Drive"""
    try:
        if 'file' not in request.files:
            return jsonify({'error': 'No file provided'}), 400
        
        file = request.files['file']
        if file.filename == '':
            return jsonify({'error': 'No file selected'}), 400
        
        # Save file temporarily
        with tempfile.NamedTemporaryFile(delete=False) as temp_file:
            file.save(temp_file.name)
            temp_path = temp_file.name
        
        try:
            # Upload to Google Drive using rclone
            remote_path = f"gdrive:minecraft-backups/{file.filename}"
            cmd = f"/usr/bin/rclone copy '{temp_path}' 'gdrive:minecraft-backups/' --config /opt/dashboard-app/.rclone.conf"
            result = run_command(cmd)
            
            if result['success']:
                return jsonify({
                    'message': f'File {file.filename} uploaded successfully',
                    'filename': file.filename
                })
            else:
                return jsonify({
                    'error': f'Upload failed: {result["stderr"]}'
                }), 500
        
        finally:
            # Clean up temp file
            os.unlink(temp_path)
    
    except Exception as e:
        logger.error(f"Upload error: {str(e)}")
        return jsonify({'error': 'Upload failed'}), 500

@rclone_drive_bp.route('/backups', methods=['GET'])
@admin_required
def list_backups():
    """List world backups from Google Drive"""
    try:
        cmd = "/usr/bin/rclone lsjson 'gdrive:minecraft-backups/' --config /opt/dashboard-app/.rclone.conf"
        result = run_command(cmd)
        
        if result['success']:
            try:
                files = json.loads(result['stdout']) if result['stdout'].strip() else []
                backups = []
                
                for file in files:
                    backups.append({
                        'name': file.get('Name', ''),
                        'size': file.get('Size', 0),
                        'modified': file.get('ModTime', ''),
                        'is_dir': file.get('IsDir', False)
                    })
                
                return jsonify({
                    'backups': backups,
                    'count': len(backups)
                })
            except json.JSONDecodeError:
                return jsonify({'backups': [], 'count': 0})
        else:
            return jsonify({
                'error': f'Failed to list backups: {result["stderr"]}',
                'backups': [],
                'count': 0
            }), 500
    
    except Exception as e:
        logger.error(f"List backups error: {str(e)}")
        return jsonify({'error': 'Failed to list backups'}), 500

@rclone_drive_bp.route('/export-world', methods=['POST'])
@admin_required
def export_world():
    """Zip and upload world folder to Google Drive"""
    try:
        data = request.get_json()
        world_name = data.get('world_name', 'world')
        world_path = f"/opt/minecraft/{world_name}"
        
        if not os.path.exists(world_path):
            return jsonify({'error': f'World {world_name} not found'}), 404
        
        # Create zip file
        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        zip_filename = f"{world_name}_backup_{timestamp}.zip"
        temp_zip_path = f"/tmp/{zip_filename}"
        
        try:
            run_command(f"/usr/bin/chown -R {SERVICE_USER}:mcgroup {temp_zip_path}")
            cmd = f"/usr/bin/zip -r '{temp_zip_path}' '{world_path}'"
            result = run_command(cmd)

            if not result['success']:
                return jsonify({'error': f'Zip failed: {result['stderr']}, {result['stdout']}, {result}'}), 500
            
            
            # Upload to Google Drive
            cmd = f"/usr/bin/rclone copy '{temp_zip_path}' 'gdrive:minecraft-backups/' --config /opt/dashboard-app/.rclone.conf"
            result = run_command(cmd)
            
            if result['success']:
                return jsonify({
                    'message': f'World {world_name} exported successfully',
                    'filename': zip_filename,
                    'size': os.path.getsize(temp_zip_path)
                })
            else:
                return jsonify({
                    'error': f'Export failed: {result["stderr"]}'
                }), 500
        
        finally:
            # Clean up temp file
            if os.path.exists(temp_zip_path):
                os.unlink(temp_zip_path)
    
    except Exception as e:
        logger.error(f"Export world error: {str(e)}")
        return jsonify({'error': f"Export failed, {e}"}), 500

@rclone_drive_bp.route('/import-world', methods=['POST'])
@admin_required
def import_world():
    """Download and extract selected world from Google Drive"""
    try:
        run_command(f"/usr/bin/systemctl stop minecraft")
        
        data = request.get_json()
        backup_filename = data.get('backup_filename')
        
        if not backup_filename:
            return jsonify({'error': 'Backup filename required'}), 400

        run_command(f"/usr/bin/chown -R {SERVICE_USER}:mcgroup {MINECRAFT_DIR}")
        # Download from Google Drive
        with tempfile.NamedTemporaryFile(suffix='.zip', delete=False) as temp_file:
            temp_path = temp_file.name
        
        try:
            cmd = f"/usr/bin/rclone copy 'gdrive:minecraft-backups/{backup_filename}' '{os.path.dirname(temp_path)}/' --config /opt/dashboard-app/.rclone.conf"
            result = run_command(cmd)
            
            if not result['success']:
                return jsonify({
                    'error': f'Download failed: {result["stderr"]}'
                }), 500
            
            # Move downloaded file to temp path
            downloaded_path = os.path.join(os.path.dirname(temp_path), backup_filename)
            if os.path.exists(downloaded_path):
                os.rename(downloaded_path, temp_path)
            
            # Extract to minecraft directory
            run_command(f"/usr/bin/chown -R {SERVICE_USER}:mcgroup {MINECRAFT_DIR}/world/")
            result = run_command(f"/usr/bin/rm -rf {MINECRAFT_DIR}/world/*")
            if not result['success']:
                return jsonify({
                    'error': f'removing old world files failed: {result["stderr"]}'
                }), 500
            extract_path = MINECRAFT_DIR
            with zipfile.ZipFile(temp_path, 'r') as zipf:
                zipf.extractall(extract_path)
            
            return jsonify({
                'message': f'World imported successfully from {backup_filename}'
            })
        
        finally:
            # Clean up temp files
            
            for path in [temp_path, downloaded_path]:
                if os.path.exists(path):
                    os.unlink(path)
            
            run_command(f"/usr/bin/chown -R {MINECRAFT_USER}:mcgroup {MINECRAFT_DIR}")
            run_command(f"/usr/bin/systemctl start minecraft")
    
    except Exception as e:
        run_command(f"/usr/bin/chown -R {MINECRAFT_USER}:mcgroup {MINECRAFT_DIR}")
        run_command(f"/usr/bin/systemctl start minecraft")
        logger.error(f"Import world error: {str(e)}")
        return jsonify({'error': f"Import failed - {e}"}), 500

@rclone_drive_bp.route('/upload-rclone-key', methods=['POST'])
@admin_required
def upload_rclone_key():
    """Upload new rclone.conf file securely"""
    try:
        if 'file' not in request.files:
            return jsonify({'error': 'No file provided'}), 400
        
        file = request.files['file']
        if file.filename == '':
            return jsonify({'error': 'No file selected'}), 400
        
        if not file.filename.endswith('.conf'):
            return jsonify({'error': 'File must be a .conf file'}), 400
        
        # Save rclone config
        config_path = "/opt/dashboard-app/.rclone.conf"
        file.save(config_path)
        
        # Set proper permissions
        os.chmod(config_path, 0o600)
        run_command(f"/usr/bin/chown -R {SERVICE_USER}:mcgroup {config_path}")
        
        # Test rclone configuration
        test_result = run_command(f"/usr/bin/rclone lsd gdrive: --config {config_path}")
        
        if test_result['success']:
            return jsonify({
                'message': '/usr/bin/rclone configuration uploaded and verified successfully'
            })
        else:
            return jsonify({
                'error': f'Configuration test failed: {test_result["stderr"]}'
            }), 500
    
    except Exception as e:
        logger.error(f"Upload rclone key error: {str(e)}")
        return jsonify({'error': 'Failed to upload configuration'}), 500

@rclone_drive_bp.route('/status', methods=['GET'])
@admin_required
def get_status():
    """Get rclone and Google Drive status"""
    try:
        config_path = "/opt/dashboard-app/.rclone.conf"
        config_exists = os.path.exists(config_path)
        
        if config_exists:
            # Test connection
            test_result = run_command(f"/usr/bin/rclone lsd gdrive: --config {config_path}")
            connected = test_result['success']
        else:
            connected = False
        
        return jsonify({
            'config_exists': config_exists,
            'connected': connected,
            'rclone_installed': os.path.exists('/usr/bin/rclone')
        })
    
    except Exception as e:
        logger.error(f"Status error: {str(e)}")
        return jsonify({'error': 'Failed to get status'}), 500
