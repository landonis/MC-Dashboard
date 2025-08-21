#!/bin/bash

# Dashboard App Setup Script
# This script sets up the dashboard application with all required components
set -e

# Configuration
REPO_URL="https://github.com/landonis/MC-dashboard"
INSTALL_DIR="/opt/dashboard-app"
SERVICE_USER="dashboardapp"
MINECRAFT_USER="minecraft"
MINECRAFT_DIR="/opt/minecraft"
LOG_FILE="/var/log/dashboard-setup.log"
DOMAIN_NAME="${DOMAIN_NAME:-localhost}"
MINECRAFT_GROUP="mcgroup"
SUDOERS_FILE="/etc/sudoers.d/dashboard"


# Logging function
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# Error handling
error_exit() {
    log "ERROR: $1"
    exit 1
}

# Check if running as root
if [[ $EUID -ne 0 ]]; then
    error_exit "This script must be run as root"
fi

log "Starting dashboard application setup..."






# Update system packages
log "Updating system packages..."
apt-get update -y
apt-get upgrade -y

# Install required packages
log "Installing required packages..."
apt-get install -y \
    git \
    nginx \
    python3 \
    python3-pip \
    python3-venv \
    nodejs \
    npm \
    certbot \
    python3-certbot-nginx \
    iptables-persistent \
    netfilter-persistent \
    curl \
    wget \
    unzip \
    software-properties-common \
    openjdk-17-jdk \
    

### Install Gradle 8.8 ###
GRADLE_VERSION=8.8
GRADLE_DIR="/opt/gradle/gradle-${GRADLE_VERSION}"
GRADLE_SCRIPT="/etc/profile.d/gradle.sh"

echo "[INFO] Installing Gradle ${GRADLE_VERSION}..."

# Remove old Gradle if needed
sudo apt remove -y gradle >/dev/null 2>&1 || true

# Download and extract Gradle
if [ ! -d "$GRADLE_DIR" ]; then
    wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -P /tmp
    sudo unzip -q -d /opt/gradle /tmp/gradle-${GRADLE_VERSION}-bin.zip
    rm /tmp/gradle-${GRADLE_VERSION}-bin.zip
fi

# Set up Gradle in PATH
sudo tee "$GRADLE_SCRIPT" >/dev/null <<EOF
export GRADLE_HOME=${GRADLE_DIR}
export PATH=\$GRADLE_HOME/bin:\$PATH
EOF

sudo chmod +x "$GRADLE_SCRIPT"
source "$GRADLE_SCRIPT"

# Verify installation
if gradle -v | grep -q "Gradle ${GRADLE_VERSION}"; then
    echo "[INFO] Gradle ${GRADLE_VERSION} installed successfully"
else
    echo "[ERROR] Failed to install Gradle ${GRADLE_VERSION}"
    exit 1
fi


# Close running backend if needed
if systemctl list-units --type=service --all | grep -q "dashboard-backend.service"; then
    echo "Stopping dashboard-backend service..."
    systemctl stop dashboard-backend.service || true
else
    echo "dashboard-backend service not found, skipping stop"
fi


# Remove old nginx configuration files
sudo rm -rf /etc/nginx/sites-enabled/dashboard
sudo nginx -t && sudo systemctl reload nginx

# Create service user
log "Creating service user..."
if ! id "$SERVICE_USER" &>/dev/null; then
    useradd -r -s /bin/false -d "$INSTALL_DIR" "$SERVICE_USER"
    log "Service user '$SERVICE_USER' created"
else
    log "Service user '$SERVICE_USER' already exists"
fi

# Create minecraft user and directory
log "Setting up minecraft user and directory..."
if ! id "$MINECRAFT_USER" &>/dev/null; then
    useradd -r -s /bin/false "$MINECRAFT_USER"
    log "Minecraft user created"
else
    log "Minecraft user already exists"
fi

sudo mkdir -p "$MINECRAFT_DIR"

log "[INFO] Creating group '$MINECRAFT_GROUP' if it doesn't exist..."
if ! getent group "$MINECRAFT_GROUP" > /dev/null; then
  sudo groupadd "$MINECRAFT_GROUP"
  log "[INFO] Group '$MINECRAFT_GROUP' created."
else
  log "[INFO] Group '$MINECRAFT_GROUP' already exists."
fi

log "[INFO] Adding users to group '$MINECRAFT_GROUP'..."
sudo usermod -aG "$MINECRAFT_GROUP" "$MINECRAFT_USER"
sudo usermod -aG "$MINECRAFT_GROUP" "$SERVICE_USER"

log "[INFO] Assigning ownership of $MINECRAFT_DIR to user '$MINECRAFT_USER' and group '$MINECRAFT_GROUP'..."
sudo chown -R "$MINECRAFT_USER:$MINECRAFT_GROUP" "$MINECRAFT_DIR"

log "[INFO] Setting directory permissions to group-writable and setting group ID bit..."
sudo chmod -R 775 "$MINECRAFT_DIR"
sudo find "$MINECRAFT_DIR" -type d -exec chmod g+s {} \;

# Install rclone if missing
log "Installing rclone..."
if ! command -v rclone &>/dev/null; then
    curl https://rclone.org/install.sh | sudo bash
    log "rclone installed successfully"
else
    log "rclone already installed"
fi

# Create install directory
log "Creating install directory..."
mkdir -p "$INSTALL_DIR"
chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

# Clone or update repository
log "Setting up repository..."
if [ -d "$INSTALL_DIR/.git" ]; then
    log "Repository exists, updating..."
    cd "$INSTALL_DIR"
    # Preserve local .env file
    if [ -f ".env" ]; then
        cp .env .env.backup
    fi
    sudo -u "$SERVICE_USER" git fetch origin
    sudo -u "$SERVICE_USER" git reset --hard origin/main
    # Restore .env file
    if [ -f ".env.backup" ]; then
        mv .env.backup .env
        chown "$SERVICE_USER:$SERVICE_USER" .env
    fi
else
    log "Cloning repository..."
    sudo -u "$SERVICE_USER" git clone "$REPO_URL" "$INSTALL_DIR"
    cd "$INSTALL_DIR"
fi

# Setup Python virtual environment
log "Setting up Python virtual environment..."
cd "$INSTALL_DIR/backend"
sudo -u "$SERVICE_USER" python3 -m venv venv
sudo -u "$SERVICE_USER" ./venv/bin/pip install --upgrade pip
sudo -u "$SERVICE_USER" ./venv/bin/pip install -r requirements.txt

# Setup Node.js environment
log "Setting up Node.js environment..."
cd "$INSTALL_DIR/frontend"
sudo -u "$SERVICE_USER" npm install
sudo -u "$SERVICE_USER" npm run build

# Create .env file if it doesn't exist
log "Creating environment configuration..."
if [ ! -f "$INSTALL_DIR/.env" ]; then
    cat > "$INSTALL_DIR/.env" <<EOF
# Database
DATABASE_URL=sqlite:///dashboard.db

# Security
SECRET_KEY=$(openssl rand -base64 32)
JWT_SECRET_KEY=$(openssl rand -base64 32)

# Flask
FLASK_ENV=production
FLASK_DEBUG=false

# Frontend
VITE_API_URL=http://localhost:5000
EOF
    chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/.env"
    log "Environment file created"
fi
echo "MINECRAFT_DIR=/opt/minecraft" >> "$INSTALL_DIR/.env"
echo "MINECRAFT_USER=minecraft" >> "$INSTALL_DIR/.env"


# Grant dashboardapp passwordless sudo access for managing minecraft.service
log "Configuring sudo permissions for dashboardapp to manage Minecraft service..."

cat <<EOF > "$SUDOERS_FILE"
dashboardapp ALL=(ALL) NOPASSWD: \
    /usr/bin/journalctl -u minecraft.service, \
    /usr/bin/tee /etc/systemd/system/minecraft.service, \
    /usr/bin/tee /etc/systemd/system/dashboard-mod.service, \
    /usr/bin/chown, \
    /usr/bin/systemctl start minecraft.service, \
    /usr/bin/systemctl stop minecraft.service, \
    /usr/bin/systemctl restart minecraft.service, \
    /usr/bin/systemctl enable minecraft.service, \
    /usr/bin/systemctl disable minecraft.service, \
    /usr/bin/gunicorn, \
    /usr/bin/systemctl enable dashboard-mod.service, \
    /usr/bin/systemctl disable dashboard-mod.service, \
    /usr/bin/systemctl start dashboard-mod.service, \
    /usr/bin/systemctl stop dashboard-mod.service, \
    /bin/systemctl daemon-reload, \
    /bin/systemctl daemon-reexec, \
    /bin/mv /tmp/minecraft.service /etc/systemd/system/minecraft.service, \
    /opt/gradle/gradle-8.8/bin/gradle clean, \
    /opt/gradle/gradle-8.8/bin/gradle build

dashboardapp ALL=(minecraft) NOPASSWD: \
    /usr/bin/zip, \
    /usr/bin/unzip, \
    /usr/bin/rm, \
    /usr/bin/du
EOF

chmod 440 "$SUDOERS_FILE"

# Initialize database
log "Initializing database..."
cd "$INSTALL_DIR/backend"
sudo -u "$SERVICE_USER" PYTHONPATH="$INSTALL_DIR:$INSTALL_DIR/backend" ./venv/bin/python run.py &
sleep 5
pkill -f "python run.py" || true

# Configure systemd services
log "Configuring systemd services..."


# Create Gunicorn systemd service
log "Creating Gunicorn systemd service..."
cp /opt/dashboard-app/systemd/dashboard-backend.service /etc/systemd/system/dashboard-backend.service


# Enable and start Gunicorn service
log "Enabling and starting dashboard-backend service..."
systemctl daemon-reexec
systemctl daemon-reload
systemctl enable dashboard-backend
systemctl start dashboard-backend

# Frontend service (serving built files)
cat > /etc/systemd/system/dashboard-frontend.service <<EOF
[Unit]
Description=Dashboard Frontend Service
After=network.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$INSTALL_DIR/frontend
ExecStart=/usr/bin/npx serve -s dist -l 3000
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd and enable services
systemctl daemon-reload
systemctl daemon-reexec
systemctl enable dashboard-backend.service
systemctl enable dashboard-frontend.service

# Configure iptables
log "Configuring firewall..."
iptables -F
iptables -X
iptables -t nat -F
iptables -t nat -X
iptables -t mangle -F
iptables -t mangle -X
iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT ACCEPT

# Allow loopback
iptables -I INPUT -i lo -j ACCEPT
iptables -I OUTPUT -o lo -j ACCEPT

# Allow established connections
iptables -I INPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

# Allow SSH
iptables -I INPUT -p tcp --dport 22 -j ACCEPT

# Allow HTTP and HTTPS
iptables -I INPUT -p tcp --dport 80 -j ACCEPT
iptables -I INPUT -p tcp --dport 443 -j ACCEPT

# Allow Minecraft (default port 25565)
iptables -I INPUT -p tcp --dport 25565 -j ACCEPT

# Save iptables rules
netfilter-persistent save

# Configure Nginx
log "Configuring Nginx..."
cat > /etc/nginx/sites-available/dashboard <<EOF
server {
    listen 80;
    server_name $DOMAIN_NAME;
    
    # Redirect HTTP to HTTPS
    return 301 https://\$server_name\$request_uri;
}

server {
    listen 443 ssl http2;
    server_name $DOMAIN_NAME;
    
    # SSL configuration (will be updated by certbot or self-signed)
    ssl_certificate /etc/ssl/certs/dashboard.crt;
    ssl_certificate_key /etc/ssl/private/dashboard.key;
    
    # Security headers
    add_header X-Frame-Options DENY;
    add_header X-Content-Type-Options nosniff;
    add_header X-XSS-Protection "1; mode=block";
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains";
    
    # Frontend (static files)
    location / {
        proxy_pass http://localhost:3000/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    
    # Backend API
    location ^~ /api/ {
        proxy_pass http://localhost:5000/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

# Enable site
ln -sf /etc/nginx/sites-available/dashboard /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default

# Setup SSL certificate
log "Setting up SSL certificate..."
if [ "$DOMAIN_NAME" != "localhost" ]; then
    # Try Let's Encrypt
    if certbot --nginx -d "$DOMAIN_NAME" --non-interactive --agree-tos --email admin@"$DOMAIN_NAME" --redirect; then
        log "Let's Encrypt certificate installed successfully"
    else
        log "Let's Encrypt failed, creating self-signed certificate..."
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
            -keyout /etc/ssl/private/dashboard.key \
            -out /etc/ssl/certs/dashboard.crt \
            -subj "/C=US/ST=State/L=City/O=Organization/CN=$DOMAIN_NAME"
    fi
else
    log "Creating self-signed certificate for localhost..."
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout /etc/ssl/private/dashboard.key \
        -out /etc/ssl/certs/dashboard.crt \
        -subj "/C=US/ST=State/L=City/O=Organization/CN=localhost"
fi

# Test nginx configuration
nginx -t || error_exit "Nginx configuration test failed"

# Start services
log "Starting services..."
systemctl restart dashboard-backend
systemctl restart dashboard-frontend
systemctl restart nginx

# Verify services are running
sleep 10
systemctl is-active --quiet dashboard-backend || error_exit "Backend service failed to start"
systemctl is-active --quiet dashboard-frontend || error_exit "Frontend service failed to start"
systemctl is-active --quiet nginx || error_exit "Nginx service failed to start"

# Ensure Gradle wrapper is executable for dashboard mod compilation
log "Setting up Gradle permissions for dashboard mod..."
if [ -f "$INSTALL_DIR/dashboard-mod/gradlew" ]; then
    chmod +x "$INSTALL_DIR/dashboard-mod/gradlew"
    chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/dashboard-mod"
    log "Gradle wrapper permissions set"
else
    log "Gradle wrapper not found, will be available after repository clone"
fi

# Set proper permissions
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
chmod -R 755 "$INSTALL_DIR"

log "Setup completed successfully!"
log "Dashboard is accessible at: https://$DOMAIN_NAME"
log "Default credentials: admin / admin"
log "Please change the default password after first login"
log "Services status:"
systemctl status dashboard-backend --no-pager -l
systemctl status dashboard-frontend --no-pager -l
systemctl status nginx --no-pager -l

# Create default admin user if it doesn't exist
echo "[*] Ensuring default admin user exists..."
sudo python3 <<EOF
import sqlite3
import bcrypt
import os

db_path = "/opt/dashboard-app/backend/database.db"
if os.path.exists(db_path):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='users'")
    if cursor.fetchone():
        cursor.execute("SELECT * FROM users WHERE username = ?", ("admin",))
        if not cursor.fetchone():
            password_hash = bcrypt.hashpw(b"admin", bcrypt.gensalt()).decode("utf-8")
            cursor.execute("INSERT INTO users (username, password, role) VALUES (?, ?, ?)", ("admin", password_hash, "admin"))
            conn.commit()
            print("[+] Default admin user created.")
        else:
            print("[*] Admin user already exists.")
    else:
        print("[!] 'users' table not found. Skipping admin creation.")
    conn.close()
else:
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password TEXT NOT NULL,
            role TEXT NOT NULL
        )
    ''')
    conn.commit()
    conn.close()
    print("[+] Database and users table created at", db_path)
EOF
