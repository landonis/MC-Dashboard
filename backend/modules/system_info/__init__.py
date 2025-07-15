"""
System Info Module

This module provides comprehensive system information for the dashboard application.
It includes real-time monitoring of CPU, memory, disk usage, and system processes.

Features:
- Real-time system metrics
- Process monitoring
- Network interface information
- Auto-refresh capabilities
- Admin-only access control
"""

from .api import system_info_bp

__all__ = ['system_info_bp']
