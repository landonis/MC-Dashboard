import React, { useState, useEffect } from 'react'
import { 
  Play, 
  Square, 
  RotateCcw, 
  Settings, 
  Monitor, 
  HardDrive,
  Memory,
  AlertCircle,
  CheckCircle,
  Loader2,
  Terminal,
  Download
} from 'lucide-react'
import { useAuth } from '../contexts/AuthContext'
import api from '../services/api'

interface ServerStatus {
  running: boolean
  server_exists: boolean
  world_info: {
    exists: boolean
    name?: string
    size?: number
  }
  memory_info: {
    used_mb?: number
    used_gb?: number
  }
  status_output: string
  service_enabled: boolean
}

interface BuildConfig {
  minecraft_version: string
  fabric_version: string
  memory_gb: number
}

interface Versions {
  minecraft_versions: string[]
  fabric_versions: string[]
}

const Server: React.FC = () => {
  const { hasRole } = useAuth()
  const [status, setStatus] = useState<ServerStatus | null>(null)
  const [versions, setVersions] = useState<Versions | null>(null)
  const [loading, setLoading] = useState(true)
  const [actionLoading, setActionLoading] = useState('')
  const [building, setBuilding] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [buildLog, setBuildLog] = useState<string[]>([])
  const [showBuildForm, setShowBuildForm] = useState(false)
  const [buildConfig, setBuildConfig] = useState<BuildConfig>({
    minecraft_version: '1.20.1',
    fabric_version: '0.14.21',
    memory_gb: 2
  })

  useEffect(() => {
    if (hasRole('admin')) {
      fetchStatus()
      fetchVersions()
    }
  }, [hasRole])

  const fetchStatus = async () => {
    try {
      const response = await api.get('/api/server/status')
      setStatus(response.data)
    } catch (error) {
      console.error('Error fetching status:', error)
      setError('Failed to fetch server status')
    } finally {
      setLoading(false)
    }
  }

  const fetchVersions = async () => {
    try {
      const response = await api.get('/api/server/versions')
      setVersions(response.data)
    } catch (error) {
      console.error('Error fetching versions:', error)
    }
  }

  const handleServerAction = async (action: 'start' | 'stop' | 'restart') => {
    setActionLoading(action)
    setError('')
    setSuccess('')

    try {
      const response = await api.post(`/api/server/${action}`)
      setSuccess(response.data.message)
      
      // Refresh status after action
      setTimeout(fetchStatus, 2000)
    } catch (error: any) {
      setError(error.response?.data?.error || `Failed to ${action} server`)
    } finally {
      setActionLoading('')
    }
  }

  const handleBuildServer = async () => {
    setBuilding(true)
    setError('')
    setSuccess('')
    setBuildLog([])

    try {
      const response = await api.post('/api/server/build', buildConfig)
      setSuccess(response.data.message)
      setBuildLog(response.data.log || [])
      setShowBuildForm(false)
      
      // Refresh status after build
      setTimeout(fetchStatus, 2000)
    } catch (error: any) {
      setError(error.response?.data?.error || 'Build failed')
      setBuildLog(error.response?.data?.log || [])
    } finally {
      setBuilding(false)
    }
  }

  const formatMemory = (mb: number) => {
    if (mb >= 1024) {
      return `${(mb / 1024).toFixed(2)} GB`
    }
    return `${mb} MB`
  }

  const formatWorldSize = (mb: number) => {
    if (mb >= 1024) {
      return `${(mb / 1024).toFixed(2)} GB`
    }
    return `${mb} MB`
  }

  if (!hasRole('admin')) {
    return (
      <div className="bg-white rounded-lg shadow-sm p-6 text-center">
        <h1 className="text-2xl font-bold text-gray-900 mb-4">Access Denied</h1>
        <p className="text-gray-600">You don't have permission to access this page.</p>
      </div>
    )
  }

  if (loading) {
    return (
      <div className="bg-white rounded-lg shadow-sm p-6 text-center">
        <Loader2 className="h-8 w-8 animate-spin text-primary-600 mx-auto mb-2" />
        <p className="text-gray-600">Loading server status...</p>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-white shadow-sm rounded-lg p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <Monitor className="h-6 w-6 text-primary-600" />
            <h1 className="text-2xl font-bold text-gray-900">Minecraft Server</h1>
          </div>
          <div className="flex items-center space-x-2">
            {status?.running ? (
              <>
                <CheckCircle className="h-5 w-5 text-success-600" />
                <span className="text-sm font-medium text-success-600">Running</span>
              </>
            ) : (
              <>
                <AlertCircle className="h-5 w-5 text-error-600" />
                <span className="text-sm font-medium text-error-600">Stopped</span>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Status Messages */}
      {error && (
        <div className="bg-error-50 border border-error-200 text-error-700 px-4 py-3 rounded-md">
          <div className="flex items-center">
            <AlertCircle className="h-5 w-5 mr-2" />
            {error}
          </div>
        </div>
      )}

      {success && (
        <div className="bg-success-50 border border-success-200 text-success-700 px-4 py-3 rounded-md">
          <div className="flex items-center">
            <CheckCircle className="h-5 w-5 mr-2" />
            {success}
          </div>
        </div>
      )}

      {/* Server Stats */}
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-3">
        <div className="bg-white rounded-lg shadow-sm p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">Server Status</p>
              <p className={`text-xl font-bold ${status?.running ? 'text-success-600' : 'text-error-600'}`}>
                {status?.running ? 'Running' : 'Stopped'}
              </p>
            </div>
            <div className={`p-3 rounded-full ${status?.running ? 'bg-success-50' : 'bg-error-50'}`}>
              <Monitor className={`h-6 w-6 ${status?.running ? 'text-success-600' : 'text-error-600'}`} />
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">Memory Usage</p>
              <p className="text-xl font-bold text-gray-900">
                {status?.memory_info?.used_mb ? formatMemory(status.memory_info.used_mb) : 'N/A'}
              </p>
            </div>
            <div className="p-3 rounded-full bg-primary-50">
              <Memory className="h-6 w-6 text-primary-600" />
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">World Size</p>
              <p className="text-xl font-bold text-gray-900">
                {status?.world_info?.exists && status.world_info.size 
                  ? formatWorldSize(status.world_info.size) 
                  : 'N/A'}
              </p>
            </div>
            <div className="p-3 rounded-full bg-secondary-50">
              <HardDrive className="h-6 w-6 text-secondary-600" />
            </div>
          </div>
        </div>
      </div>

      {/* Server Controls */}
      {status?.server_exists && (
        <div className="bg-white rounded-lg shadow-sm p-6">
          <h3 className="text-lg font-semibold text-gray-900 mb-4">Server Controls</h3>
          <div className="flex items-center space-x-4">
            <button
              onClick={() => handleServerAction('start')}
              disabled={status.running || actionLoading === 'start'}
              className="flex items-center space-x-2 bg-success-600 text-white px-4 py-2 rounded-md hover:bg-success-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {actionLoading === 'start' ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Play className="h-4 w-4" />
              )}
              <span>Start</span>
            </button>

            <button
              onClick={() => handleServerAction('stop')}
              disabled={!status.running || actionLoading === 'stop'}
              className="flex items-center space-x-2 bg-error-600 text-white px-4 py-2 rounded-md hover:bg-error-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {actionLoading === 'stop' ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Square className="h-4 w-4" />
              )}
              <span>Stop</span>
            </button>

            <button
              onClick={() => handleServerAction('restart')}
              disabled={actionLoading === 'restart'}
              className="flex items-center space-x-2 bg-warning-600 text-white px-4 py-2 rounded-md hover:bg-warning-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {actionLoading === 'restart' ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RotateCcw className="h-4 w-4" />
              )}
              <span>Restart</span>
            </button>
          </div>
        </div>
      )}

      {/* Build Server Section */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold text-gray-900">Server Management</h3>
          {!status?.server_exists && (
            <button
              onClick={() => setShowBuildForm(true)}
              disabled={building}
              className="flex items-center space-x-2 bg-primary-600 text-white px-4 py-2 rounded-md hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <Settings className="h-4 w-4" />
              <span>Build New Server</span>
            </button>
          )}
        </div>

        {!status?.server_exists && (
          <div className="text-center py-8">
            <Download className="h-12 w-12 text-gray-400 mx-auto mb-4" />
            <p className="text-gray-600 mb-4">No server found. Build a new Minecraft server to get started.</p>
          </div>
        )}

        {/* Build Form Modal */}
        {showBuildForm && (
          <div className="fixed inset-0 bg-gray-600 bg-opacity-50 flex items-center justify-center z-50">
            <div className="bg-white rounded-lg p-6 w-full max-w-md">
              <h3 className="text-lg font-semibold mb-4">Build New Server</h3>
              
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Minecraft Version
                  </label>
                  <select
                    value={buildConfig.minecraft_version}
                    onChange={(e) => setBuildConfig({...buildConfig, minecraft_version: e.target.value})}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    {versions?.minecraft_versions.map(version => (
                      <option key={version} value={version}>{version}</option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Fabric Version
                  </label>
                  <select
                    value={buildConfig.fabric_version}
                    onChange={(e) => setBuildConfig({...buildConfig, fabric_version: e.target.value})}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    {versions?.fabric_versions.map(version => (
                      <option key={version} value={version}>{version}</option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Memory (GB)
                  </label>
                  <input
                    type="number"
                    min="1"
                    max="16"
                    value={buildConfig.memory_gb}
                    onChange={(e) => setBuildConfig({...buildConfig, memory_gb: parseInt(e.target.value)})}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
                  />
                </div>
              </div>

              <div className="flex justify-end space-x-3 pt-4">
                <button
                  onClick={() => setShowBuildForm(false)}
                  disabled={building}
                  className="px-4 py-2 text-gray-700 bg-gray-200 rounded-md hover:bg-gray-300 transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={handleBuildServer}
                  disabled={building}
                  className="flex items-center space-x-2 px-4 py-2 bg-primary-600 text-white rounded-md hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                >
                  {building ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Download className="h-4 w-4" />
                  )}
                  <span>Build Server</span>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Build Log */}
        {buildLog.length > 0 && (
          <div className="mt-4">
            <h4 className="text-sm font-medium text-gray-700 mb-2">Build Log:</h4>
            <div className="bg-gray-900 text-green-400 p-4 rounded-md font-mono text-sm max-h-64 overflow-y-auto">
              {buildLog.map((line, index) => (
                <div key={index}>{line}</div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Server Status Output */}
      {status?.status_output && (
        <div className="bg-white rounded-lg shadow-sm p-6">
          <h3 className="text-lg font-semibold text-gray-900 mb-4">System Status</h3>
          <div className="bg-gray-900 text-gray-300 p-4 rounded-md font-mono text-sm max-h-64 overflow-y-auto">
            <pre>{status.status_output}</pre>
          </div>
        </div>
      )}
    </div>
  )
}

export default Server