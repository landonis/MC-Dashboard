import React, { useEffect, useState } from 'react'
import { useAuth } from '../contexts/AuthContext'
import {
  Users,
  Monitor,
  Shield,
  Activity,
  TrendingUp,
  AlertCircle,
  CheckCircle,
  XCircle
} from 'lucide-react'

interface ServerStatus {
  running: boolean
  server_exists: boolean
  memory_info: { used_mb: number; used_gb: number }
  world_info: { exists: boolean; size?: number }
  service_enabled: boolean
}

const DashboardPage: React.FC = () => {
  const { user, hasRole } = useAuth()

  const [status, setStatus] = useState<ServerStatus | null>(null)

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const res = await fetch('/server/status', {
          headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
        })
        const data = await res.json()
        setStatus(data)
      } catch (error) {
        console.error('Failed to fetch server status:', error)
      }
    }

    fetchStatus()
  }, [])

  const stats = [
    {
      name: 'System Status',
      value: status ? (status.running ? 'Online' : 'Offline') : 'Loading...',
      icon: status ? (status.running ? CheckCircle : XCircle) : Monitor,
      color: status ? (status.running ? 'text-success-600' : 'text-error-600') : 'text-gray-400',
      bgColor: status ? (status.running ? 'bg-success-50' : 'bg-error-50') : 'bg-gray-50',
      change: '',
      changeColor: 'text-gray-400'
    },
    {
      name: 'Memory Used',
      value: status?.memory_info?.used_gb !== undefined ? `${status.memory_info.used_gb} GB` : 'N/A',
      icon: Monitor,
      color: 'text-primary-600',
      bgColor: 'bg-primary-50',
      change: '',
      changeColor: 'text-gray-400'
    },
    {
      name: 'World Size',
      value: status?.world_info?.exists && status.world_info.size !== undefined
        ? `${status.world_info.size} MB`
        : 'N/A',
      icon: Activity,
      color: 'text-secondary-600',
      bgColor: 'bg-secondary-50',
      change: '',
      changeColor: 'text-gray-400'
    },
    {
      name: 'Service Enabled',
      value: status ? (status.service_enabled ? 'Yes' : 'No') : 'N/A',
      icon: Shield,
      color: status?.service_enabled ? 'text-success-600' : 'text-error-600',
      bgColor: status?.service_enabled ? 'bg-success-50' : 'bg-error-50',
      change: '',
      changeColor: 'text-gray-400'
    }
  ]

  // The rest of the file remains unchanged


  const recentActivity = [
    { id: 1, action: 'User login', user: 'admin', time: '2 minutes ago', type: 'success' },
    { id: 2, action: 'System backup completed', user: 'system', time: '1 hour ago', type: 'info' },
    { id: 3, action: 'New user created', user: 'admin', time: '2 hours ago', type: 'success' },
    { id: 4, action: 'SSL certificate renewed', user: 'system', time: '1 day ago', type: 'success' },
    { id: 5, action: 'Database maintenance', user: 'system', time: '2 days ago', type: 'info' }
  ]

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-white shadow-sm rounded-lg p-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
            <p className="text-gray-600">
              Welcome back, <span className="font-medium">{user?.username}</span>
            </p>
          </div>
          <div className="flex items-center space-x-2">
            <Shield className="h-5 w-5 text-success-600" />
            <span className="text-sm font-medium text-success-600">System Secure</span>
          </div>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((stat) => {
          const Icon = stat.icon
          return (
            <div key={stat.name} className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-gray-600">{stat.name}</p>
                  <p className="text-2xl font-bold text-gray-900">{stat.value}</p>
                </div>
                <div className={`p-3 rounded-full ${stat.bgColor}`}>
                  <Icon className={`h-6 w-6 ${stat.color}`} />
                </div>
              </div>
              <div className="mt-4 flex items-center">
                <TrendingUp className={`h-4 w-4 ${stat.changeColor} mr-1`} />
                <span className={`text-sm font-medium ${stat.changeColor}`}>
                  {stat.change}
                </span>
                <span className="text-sm text-gray-500 ml-1">vs last week</span>
              </div>
            </div>
          )
        })}
      </div>

      {/* Two column layout */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Recent Activity */}
        <div className="bg-white rounded-lg shadow-sm p-6">
          <h3 className="text-lg font-semibold text-gray-900 mb-4">Recent Activity</h3>
          <div className="space-y-4">
            {recentActivity.map((activity) => (
              <div key={activity.id} className="flex items-center space-x-3 p-3 bg-gray-50 rounded-md">
                <div className={`w-2 h-2 rounded-full ${
                  activity.type === 'success' ? 'bg-success-600' : 'bg-primary-600'
                }`} />
                <div className="flex-1">
                  <p className="text-sm font-medium text-gray-900">{activity.action}</p>
                  <p className="text-xs text-gray-500">by {activity.user} • {activity.time}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Quick Actions */}
        <div className="bg-white rounded-lg shadow-sm p-6">
          <h3 className="text-lg font-semibold text-gray-900 mb-4">Quick Actions</h3>
          <div className="grid grid-cols-1 gap-3">
            {hasRole('admin') && (
              <>
                <button className="flex items-center space-x-3 p-3 bg-primary-50 hover:bg-primary-100 rounded-md transition-colors">
                  <Users className="h-5 w-5 text-primary-600" />
                  <span className="text-sm font-medium text-primary-700">Manage Users</span>
                </button>
                <button className="flex items-center space-x-3 p-3 bg-secondary-50 hover:bg-secondary-100 rounded-md transition-colors">
                  <Monitor className="h-5 w-5 text-secondary-600" />
                  <span className="text-sm font-medium text-secondary-700">System Monitor</span>
                </button>
              </>
            )}
            <button className="flex items-center space-x-3 p-3 bg-warning-50 hover:bg-warning-100 rounded-md transition-colors">
              <Activity className="h-5 w-5 text-warning-600" />
              <span className="text-sm font-medium text-warning-700">View Logs</span>
            </button>
          </div>
        </div>
      </div>

      {/* System Overview */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">System Overview</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="text-center">
            <div className="text-2xl font-bold text-primary-600">99.9%</div>
            <div className="text-sm text-gray-600">Uptime</div>
          </div>
          <div className="text-center">
            <div className="text-2xl font-bold text-success-600">2.4 GB</div>
            <div className="text-sm text-gray-600">Memory Usage</div>
          </div>
          <div className="text-center">
            <div className="text-2xl font-bold text-warning-600">15%</div>
            <div className="text-sm text-gray-600">CPU Load</div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default DashboardPage
