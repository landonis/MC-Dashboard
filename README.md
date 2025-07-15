# Deploy

copy updated_setup.sh to machine and run it
add a domain name to the setup script to add SSL


# Modular Dashboard Web App

A secure, production-ready dashboard web application designed as a central control panel for microservice systems. Features authentication, role-based access control (RBAC), SSL encryption, firewall protection, and GitHub-based deployment.

## Architecture & Tech Stack

- **Frontend**: React + Tailwind CSS (Vite)
- **Backend**: Flask + SQLite
- **Reverse Proxy**: Nginx
- **Security**: Let's Encrypt SSL + iptables firewall
- **Deployment**: GitHub-based with systemd services
- **User Management**: Local SQLite authentication with RBAC

## GitHub Repository

This application is deployed from and updated via:
```
https://github.com/landonis/dashboard-app
```

## Quick Setup (Ubuntu 24.04)

1. **Clone or download this repository**
2. **Run the setup script as root**:
   ```bash
   sudo ./scripts/setup.sh
   ```
3. **Access the dashboard**:
   - HTTPS: `https://your-domain.com` or `https://your-ip`
   - Default credentials: `admin` / `admin`

## Setup Script Features

The `setup.sh` script is idempotent and handles:

- **System user creation** (`dashboardapp` with no SSH login)
- **GitHub repository cloning/updating** from the official repo
- **System package installation** (nginx, certbot, python3, nodejs, etc.)
- **iptables firewall configuration** (ports 22, 80, 443 only)
- **SSL certificate setup** (Let's Encrypt with self-signed fallback)
- **Nginx reverse proxy configuration**
- **systemd service management** for frontend and backend
- **Database initialization** with admin account and RBAC roles
- **Frontend build process** with environment validation
- **Comprehensive logging** to `/var/log/dashboard-setup.log`

## SSL Certificate Logic

1. **Let's Encrypt**: Attempts automatic SSL certificate generation
2. **Self-signed fallback**: Creates local certificate if Let's Encrypt fails
3. **Nginx configuration**: Automatically configures HTTPS with proper redirects

## Role-Based Access Control

### Default Roles:
- **admin**: Full access to all modules and user management
- **user**: Restricted access to basic dashboard features

### Default Users:
- **Username**: `admin`, **Password**: `admin`, **Role**: `admin`

### Admin Features:
- User management (create, edit, delete users)
- Role assignment
- Password changes
- Access to all modules

## Firewall Configuration (iptables)

The setup script configures iptables with:
- **Allow**: SSH (22), HTTP (80), HTTPS (443)
- **Block**: All other incoming ports
- **Whitelist**: Localhost traffic
- **Persistence**: Rules saved with `netfilter-persistent`

## Sample Module: System Info

The System Info module demonstrates the modular architecture:

- **Backend**: `/modules/system-info/api.py`
- **Frontend**: `/modules/system-info/SystemInfoPage.tsx`
- **Features**: System uptime, memory usage, CPU load
- **Access**: Admin role required
- **API Endpoint**: `/api/modules/system-info`

## Adding New Modules

1. **Create module directory**: `/modules/your-module/`
2. **Backend API**: Create `api.py` with Flask routes
3. **Frontend Component**: Create React component
4. **Register routes**: Add to backend router
5. **Add navigation**: Update frontend menu
6. **Configure RBAC**: Set role requirements

### Module Structure:
```
/modules/your-module/
├── api.py          # Flask API routes
├── Component.tsx   # React component
└── README.md       # Module documentation
```

## GitHub Deployment & Updates

### Initial Deployment:
```bash
sudo ./scripts/setup.sh
```

### Updates:
```bash
sudo ./scripts/setup.sh
```

The script automatically:
- Pulls latest changes from GitHub
- Preserves local `.env` and configurations
- Rebuilds and restarts services
- Validates deployment integrity

## File Structure

```
/opt/dashboard-app/
├── frontend/           # React application
│   ├── src/
│   ├── public/
│   └── package.json
├── backend/            # Flask application
│   ├── app.py
│   ├── auth.py
│   ├── database.py
│   └── requirements.txt
├── modules/            # Modular components
│   └── system-info/
│       ├── api.py
│       └── SystemInfoPage.tsx
├── scripts/            # Deployment scripts
│   └── setup.sh
├── systemd/            # Service configurations
│   ├── dashboard-backend.service
│   └── dashboard-frontend.service
├── nginx/              # Nginx configuration
│   └── dashboard.conf
├── .env                # Environment variables
└── README.md
```

## Services

### Backend Service: `dashboard-backend.service`
- **Port**: 5000
- **User**: `dashboardapp`
- **Auto-restart**: Yes
- **Logs**: `journalctl -u dashboard-backend`

### Frontend Service: `dashboard-frontend.service`
- **Port**: 3000
- **User**: `dashboardapp`
- **Auto-restart**: Yes
- **Logs**: `journalctl -u dashboard-frontend`

## Security Features

- **Authentication**: SQLite-based with bcrypt password hashing
- **RBAC**: Role-based access control for all routes
- **SSL/TLS**: HTTPS enforcement with automatic redirects
- **Firewall**: iptables with minimal port exposure
- **Service User**: Non-privileged `dashboardapp` user
- **Input Validation**: SQL injection and XSS protection

## Troubleshooting

### Check service status:
```bash
sudo systemctl status dashboard-backend
sudo systemctl status dashboard-frontend
sudo systemctl status nginx
```

### View logs:
```bash
sudo tail -f /var/log/dashboard-setup.log
sudo journalctl -u dashboard-backend -f
sudo journalctl -u dashboard-frontend -f
```

### Restart services:
```bash
sudo systemctl restart dashboard-backend
sudo systemctl restart dashboard-frontend
sudo systemctl restart nginx
```

### SSL certificate renewal:
```bash
sudo certbot renew
sudo systemctl reload nginx
```

## Development

### Local development:
```bash
# Backend
cd backend
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python app.py

# Frontend
cd frontend
npm install
npm run dev
```

## License

Open-source compatible - modify and distribute freely.

## Support

For issues and contributions, please use the GitHub repository issues section.
