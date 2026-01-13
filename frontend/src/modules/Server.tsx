import React, { useState, useEffect } from 'react'
import { Play, Square, RotateCcw, Settings, Monitor, HardDrive, MemoryStick as Memory, AlertCircle, CheckCircle, Loader2, Terminal, Download, RefreshCw, Clock, Filter } from 'lucide-react'
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

interface JournalEntry {
  timestamp: string
  message: string
  priority: string
  level: string
  formatted_time: string
  pid: string
  cursor: string
}

interface JournalResponse {
  entries: JournalEntry[]
  total_entries: number
  requested_lines: number
  returned_lines: number
  has_more: boolean
  last_cursor: string | null
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

  // Journal state
  const [journalEntries, setJournalEntries] = useState<JournalEntry[]>([])
  const [journalLoading, setJournalLoading] = useState(false)
  const [journalError, setJournalError] = useState('')
  const [journalLines, setJournalLines] = useState(200)
  const [journalFilter, setJournalFilter] = useState<'all' | 'err' | 'warning' | 'info' | 'debug'>('all')
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [showJournalFilters, setShowJournalFilters] = useState(false)

useEffect(() => {
  if (hasRole('admin')) {
    fetchStatus()
    fetchVersions()
    fetchJournal()
  }
}, [])

// Separate useEffect for setting buildConfig defaults when versions load
useEffect(() => {
  if (versions) {
    // Set defaults to the first available versions, or specific known-good versions
    const defaultMinecraft = versions.minecraft_versions.includes('1.21.7') 
      ? '1.21.7' 
      : versions.minecraft_versions[0]
    
    const defaultFabric = versions.fabric_versions.includes('0.18.4')
      ? '0.18.4'
      : versions.fabric_versions[0]

    setBuildConfig({
      minecraft_version: defaultMinecraft,
      fabric_version: defaultFabric,
      memory_gb: 10
    })
  }
}, [versions])

// Auto-refresh journal when enabled
useEffect(() => {
  let interval: NodeJS.Timeout
  if (autoRefresh && status?.running) {
    interval = setInterval(() => {
      fetchJournal(50, journalFilter, true)
    }, 5000)
  }
  return () => {
    if (interval) clearInterval(interval)
  }
}, [autoRefresh, status?.running, journalFilter])

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

  const fetchJournal = async (lines?: number, filter?: string, isAutoRefresh?: boolean) => {
    setJournalLoading(true)
    setJournalError('')
    
    try {
      const params = new URLSearchParams()
      params.append('lines', (lines || journalLines).toString())
      if (filter && filter !== 'all') {
        params.append('priority', filter)
      } else if (journalFilter !== 'all') {
        params.append('priority', journalFilter)
      }

      // Use the simpler recent endpoint for auto-refresh
      const endpoint = isAutoRefresh ? '/api/server/journal/recent' : '/api/server/journal'
      const response = await api.get(`${endpoint}?${params}`)
      const data: JournalResponse = response.data
      setJournalEntries(data.entries)
    } catch (error: any) {
      console.error('Error fetching journal:', error)
      setJournalError(error.response?.data?.error || 'Failed to fetch journal entries')
    } finally {
      setJournalLoading(false)
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
      // Refresh journal to see action logs
      setTimeout(() => fetchJournal(), 3000)
    } catch (error: any) {
      setError(error.response?.data?.error || `Failed to ${action} server`)
    } finally {
      setActionLoading('')
    }
  }

  const toggleAutoRestart = async () => {
    try {
      const endpoint = status?.service_enabled ? '/api/server/disable' : '/api/server/enable'
      const response = await api.post(endpoint)
      setSuccess(response.data.message)
      fetchStatus()
    } catch (err: any) {
      setError(err.response?.data?.error || 'Failed to update auto-restart setting')
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

  const getLogLevelColor = (level: string) => {
    switch (level) {
      case 'error':
      case 'critical':
      case 'alert':
      case 'emergency':
        return 'text-red-400'
      case 'warning':
        return 'text-yellow-400'
      case 'info':
      case 'notice':
        return 'text-blue-400'
      case 'debug':
        return 'text-gray-400'
      default:
        return 'text-gray-300'
    }
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

        {/* Auto-Restart Toggle */}
        {status?.server_exists && (
          <div className="mt-6 border-t pt-4">
            <h4 className="text-sm font-semibold text-gray-700 mb-2">Service Settings</h4>
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-600">Auto-Restart on Boot</p>
                <p className="text-xs text-gray-400">
                  {status.service_enabled ? 'Enabled via systemd' : 'Disabled'}
                </p>
              </div>
              <button
                onClick={toggleAutoRestart}
                className={`px-4 py-2 rounded-md text-white transition-colors ${
                  status.service_enabled
                    ? 'bg-error-600 hover:bg-error-700'
                    : 'bg-success-600 hover:bg-success-700'
                }`}
              >
                {status.service_enabled ? 'Disable' : 'Enable'}
              </button>
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

      {/* Server Logs */}
      {status?.server_exists && (
        <div className="bg-white rounded-lg shadow-sm p-6">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center space-x-3">
              <Terminal className="h-5 w-5 text-primary-600" />
              <h3 className="text-lg font-semibold text-gray-900">Server Logs</h3>
            </div>
            <div className="flex items-center space-x-2">
              <button
                onClick={() => setShowJournalFilters(!showJournalFilters)}
                className="flex items-center space-x-1 px-3 py-1 text-sm bg-gray-100 text-gray-700 rounded-md hover:bg-gray-200 transition-colors"
              >
                <Filter className="h-4 w-4" />
                <span>Filter</span>
              </button>
              
              <button
                onClick={() => setAutoRefresh(!autoRefresh)}
                className={`flex items-center space-x-1 px-3 py-1 text-sm rounded-md transition-colors ${
                  autoRefresh 
                    ? 'bg-success-100 text-success-700 hover:bg-success-200' 
                    : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                }`}
              >
                <Clock className="h-4 w-4" />
                <span>Auto</span>
              </button>
              
              <button
                onClick={() => fetchJournal()}
                disabled={journalLoading}
                className="flex items-center space-x-1 px-3 py-1 text-sm bg-primary-100 text-primary-700 rounded-md hover:bg-primary-200 transition-colors disabled:opacity-50"
              >
                {journalLoading ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <RefreshCw className="h-4 w-4" />
                )}
                <span>Refresh</span>
              </button>
            </div>
          </div>

          {/* Journal Filters */}
          {showJournalFilters && (
            <div className="mb-4 p-4 bg-gray-50 rounded-md">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Lines to Show
                  </label>
                  <select
                    value={journalLines}
                    onChange={(e) => {
                      const lines = parseInt(e.target.value)
                      setJournalLines(lines)
                      fetchJournal(lines)
                    }}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    <option value={50}>50 lines</option>
                    <option value={100}>100 lines</option>
                    <option value={200}>200 lines</option>
                    <option value={500}>500 lines</option>
                    <option value={1000}>1000 lines</option>
                  </select>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Log Level
                  </label>
                  <select
                    value={journalFilter}
                    onChange={(e) => {
                      const filter = e.target.value as typeof journalFilter
                      setJournalFilter(filter)
                      fetchJournal(journalLines, filter)
                    }}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    <option value="all">All Levels</option>
                    <option value="err">Error Only</option>
                    <option value="warning">Warning & Above</option>
                    <option value="info">Info & Above</option>
                    <option value="debug">Debug & Above</option>
                  </select>
                </div>
              </div>
            </div>
          )}

          {/* Journal Error */}
          {journalError && (
            <div className="mb-4 bg-error-50 border border-error-200 text-error-700 px-4 py-3 rounded-md">
              <div className="flex items-center">
                <AlertCircle className="h-5 w-5 mr-2" />
                {journalError}
              </div>
            </div>
          )}

          {/* Journal Entries */}
          <div className="bg-gray-900 text-gray-300 p-4 rounded-md font-mono text-sm max-h-96 overflow-y-auto">
            {journalLoading && journalEntries.length === 0 ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="h-6 w-6 animate-spin text-primary-400 mr-2" />
                <span>Loading journal entries...</span>
              </div>
            ) : journalEntries.length === 0 ? (
              <div className="text-center py-8 text-gray-500">
                No journal entries found
              </div>
            ) : (
              journalEntries.map((entry, index) => (
                <div key={`${entry.cursor}-${index}`} className="mb-1 leading-relaxed">
                  <span className="text-gray-500">[{entry.formatted_time}]</span>
                  <span className={`ml-2 font-medium ${getLogLevelColor(entry.level)}`}>
                    {entry.level.toUpperCase()}
                  </span>
                  <span className="ml-2 text-gray-300">{entry.message}</span>
                </div>
              ))
            )}
          </div>
          
          {/* Journal Info */}
          {journalEntries.length > 0 && (
            <div className="mt-2 text-sm text-gray-500 flex justify-between">
              <span>Showing {journalEntries.length} entries</span>
              {autoRefresh && status?.running && (
                <span className="text-success-600">Auto-refreshing every 5s</span>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default Server
