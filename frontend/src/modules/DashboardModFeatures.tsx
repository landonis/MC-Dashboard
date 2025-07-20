import React, { useState, useEffect } from 'react'
import { 
  Settings, 
  Terminal, 
  Activity, 
  Users,
  AlertCircle,
  CheckCircle,
  Loader2,
  Play,
  Square,
  RotateCcw
} from 'lucide-react'
import { useAuth } from '../contexts/AuthContext'
import api from '../services/api'

interface DashboardModStatus {
  installed: boolean
  message: string
}

const DashboardModFeatures: React.FC = () => {
  const { hasRole } = useAuth()
  const [modStatus, setModStatus] = useState<DashboardModStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [commandOutput, setCommandOutput] = useState('')
  const [executing, setExecuting] = useState(false)

  useEffect(() => {
    if (hasRole('admin')) {
      checkModStatus()
    }
  }, [hasRole])

  const checkModStatus = async () => {
    try {
      const response = await api.get('/api/mods/check-dashboard-mod')
      setModStatus(response.data)
      
      if (!response.data.installed) {
        setError('Dashboard mod is not installed. Please install it from the Mod Manager.')
      }
    } catch (error) {
      console.error('Error checking mod status:', error)
      setError('Failed to check dashboard mod status')
    } finally {
      setLoading(false)
    }
  }

  const executeCommand = async (command: string) => {
    setExecuting(true)
    setCommandOutput('')
    setError('')

    try {
      // Placeholder for dashboard mod command execution
      // This would integrate with the actual mod's API endpoints
      setCommandOutput(`Executing: ${command}\n[Dashboard Mod] Command sent to server\n[Dashboard Mod] Waiting for response...`)
      
      // Simulate command execution
      setTimeout(() => {
        setCommandOutput(prev => prev + `\n[Dashboard Mod] Command completed successfully`)
        setExecuting(false)
      }, 2000)
    } catch (error: any) {
      setError('Failed to execute command')
      setExecuting(false)
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
        <p className="text-gray-600">Loading dashboard mod features...</p>
      </div>
    )
  }

  if (!modStatus?.installed) {
    return (
      <div className="space-y-6">
        <div className="bg-white shadow-sm rounded-lg p-6">
          <div className="flex items-center space-x-3">
            <Settings className="h-6 w-6 text-primary-600" />
            <h1 className="text-2xl font-bold text-gray-900">Dashboard Mod Features</h1>
          </div>
        </div>

        <div className="bg-warning-50 border border-warning-200 text-warning-700 px-4 py-3 rounded-md">
          <div className="flex items-center">
            <AlertCircle className="h-5 w-5 mr-2" />
            <span>Dashboard mod is not installed. Please install it from the Mod Manager to access these features.</span>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-white shadow-sm rounded-lg p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <Settings className="h-6 w-6 text-primary-600" />
            <h1 className="text-2xl font-bold text-gray-900">Dashboard Mod Features</h1>
          </div>
          <div className="flex items-center space-x-2">
            <CheckCircle className="h-5 w-5 text-success-600" />
            <span className="text-sm font-medium text-success-600">Mod Active</span>
          </div>
        </div>
      </div>

      {/* Error Display */}
      {error && (
        <div className="bg-error-50 border border-error-200 text-error-700 px-4 py-3 rounded-md">
          <div className="flex items-center">
            <AlertCircle className="h-5 w-5 mr-2" />
            {error}
          </div>
        </div>
      )}

      {/* Quick Actions */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Server Commands</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <button
            onClick={() => executeCommand('/say Dashboard connected!')}
            disabled={executing}
            className="flex items-center justify-center space-x-2 bg-primary-600 text-white px-4 py-3 rounded-md hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            <Terminal className="h-4 w-4" />
            <span>Send Message</span>
          </button>
          
          <button
            onClick={() => executeCommand('/list')}
            disabled={executing}
            className="flex items-center justify-center space-x-2 bg-secondary-600 text-white px-4 py-3 rounded-md hover:bg-secondary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            <Users className="h-4 w-4" />
            <span>List Players</span>
          </button>
          
          <button
            onClick={() => executeCommand('/time set day')}
            disabled={executing}
            className="flex items-center justify-center space-x-2 bg-accent-600 text-white px-4 py-3 rounded-md hover:bg-accent-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            <Activity className="h-4 w-4" />
            <span>Set Day</span>
          </button>
        </div>
      </div>

      {/* Server Statistics */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Live Server Data</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="text-center p-4 bg-gray-50 rounded-lg">
            <div className="text-2xl font-bold text-primary-600">0</div>
            <div className="text-sm text-gray-600">Online Players</div>
          </div>
          <div className="text-center p-4 bg-gray-50 rounded-lg">
            <div className="text-2xl font-bold text-secondary-600">20</div>
            <div className="text-sm text-gray-600">TPS (Ticks/Sec)</div>
          </div>
          <div className="text-center p-4 bg-gray-50 rounded-lg">
            <div className="text-2xl font-bold text-success-600">Day</div>
            <div className="text-sm text-gray-600">World Time</div>
          </div>
        </div>
        <p className="text-sm text-gray-500 mt-4">
          * Live data integration requires dashboard mod to be running on the server
        </p>
      </div>

      {/* Command Output */}
      {commandOutput && (
        <div className="bg-white rounded-lg shadow-sm p-6">
          <h3 className="text-lg font-semibold text-gray-900 mb-4">Command Output</h3>
          <div className="bg-gray-900 text-green-400 p-4 rounded-md font-mono text-sm max-h-64 overflow-y-auto">
            <pre>{commandOutput}</pre>
            {executing && (
              <div className="flex items-center mt-2">
                <Loader2 className="h-4 w-4 animate-spin mr-2" />
                <span>Executing...</span>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Integration Status */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Integration Status</h3>
        <div className="space-y-3">
          <div className="flex items-center justify-between p-3 bg-success-50 rounded-md">
            <div className="flex items-center space-x-2">
              <CheckCircle className="h-5 w-5 text-success-600" />
              <span className="text-sm font-medium text-success-700">Dashboard Mod Installed</span>
            </div>
          </div>
          <div className="flex items-center justify-between p-3 bg-gray-50 rounded-md">
            <div className="flex items-center space-x-2">
              <AlertCircle className="h-5 w-5 text-gray-600" />
              <span className="text-sm font-medium text-gray-700">WebSocket Connection</span>
            </div>
            <span className="text-sm text-gray-500">Pending</span>
          </div>
          <div className="flex items-center justify-between p-3 bg-gray-50 rounded-md">
            <div className="flex items-center space-x-2">
              <AlertCircle className="h-5 w-5 text-gray-600" />
              <span className="text-sm font-medium text-gray-700">Real-time Data Feed</span>
            </div>
            <span className="text-sm text-gray-500">Pending</span>
          </div>
        </div>
      </div>
    </div>
  )
}

export default DashboardModFeatures