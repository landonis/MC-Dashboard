import React, { useState, useEffect } from 'react'
import { 
  Package, 
  Upload, 
  Download, 
  Trash2, 
  AlertCircle,
  CheckCircle,
  Loader2,
  FileText,
  RotateCcw,
  Play,
  AlertTriangle,
  Settings
} from 'lucide-react'
import { useAuth } from '../contexts/AuthContext'
import api from '../services/api'

interface Mod {
  filename: string
  size: number
  modified: string
  is_fabric_api: boolean
}

interface ModsStatus {
  world_exists: boolean
  mods: Mod[]
  disabled_mods: Mod[]
  fabric_api_installed: boolean
  mods_count: number
  disabled_count: number
}

const ModManager: React.FC = () => {
  const { hasRole } = useAuth()
  const [status, setStatus] = useState<ModsStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [installing, setInstalling] = useState(false)
  const [restarting, setRestarting] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [recoveryLog, setRecoveryLog] = useState<string[]>([])

  useEffect(() => {
    if (hasRole('admin')) {
      fetchStatus()
    }
  }, [hasRole])

  const fetchStatus = async () => {
    try {
      const response = await api.get('/api/mods/status')
      setStatus(response.data)
    } catch (error) {
      console.error('Error fetching mods status:', error)
      setError('Failed to fetch mods status')
    } finally {
      setLoading(false)
    }
  }

  const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file) return

    if (!file.name.endsWith('.jar')) {
      setError('Please select a .jar file')
      return
    }

    setUploading(true)
    setError('')
    setSuccess('')

    try {
      const formData = new FormData()
      formData.append('file', file)

      const response = await api.post('/api/mods/upload', formData, {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      })

      setSuccess(response.data.message)
      fetchStatus()
    } catch (error: any) {
      setError(error.response?.data?.error || 'Upload failed')
    } finally {
      setUploading(false)
      event.target.value = ''
    }
  }

  const handleInstallFabricAPI = async () => {
    setInstalling(true)
    setError('')
    setSuccess('')

    try {
      const response = await api.post('/api/mods/install-fabric-api')
      setSuccess(response.data.message)
      fetchStatus()
    } catch (error: any) {
      setError(error.response?.data?.error || 'Fabric API installation failed')
    } finally {
      setInstalling(false)
    }
  }

  const handleDeleteMod = async (filename: string) => {
    if (!window.confirm(`Are you sure you want to delete ${filename}?`)) {
      return
    }

    setError('')
    setSuccess('')

    try {
      const response = await api.post('/api/mods/delete', { filename })
      setSuccess(response.data.message)
      fetchStatus()
    } catch (error: any) {
      setError(error.response?.data?.error || 'Delete failed')
    }
  }

  const handleEnableMod = async (filename: string) => {
    setError('')
    setSuccess('')

    try {
      const response = await api.post('/api/mods/enable', { filename })
      setSuccess(response.data.message)
      fetchStatus()
    } catch (error: any) {
      setError(error.response?.data?.error || 'Enable failed')
    }
  }

  const handleRestartWithRecovery = async () => {
    setRestarting(true)
    setError('')
    setSuccess('')
    setRecoveryLog([])

    try {
      const response = await api.post('/api/mods/restart-with-recovery')
      
      if (response.data.success) {
        setSuccess(response.data.message)
      } else {
        setError(response.data.message)
      }
      
      setRecoveryLog(response.data.recovery_log || [])
    } catch (error: any) {
      setError(error.response?.data?.error || 'Restart with recovery failed')
      if (error.response?.data?.recovery_log) {
        setRecoveryLog(error.response.data.recovery_log)
      }
    } finally {
      setRestarting(false)
    }
  }

  const formatFileSize = (bytes: number) => {
    if (bytes === 0) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
  }

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleString()
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
        <p className="text-gray-600">Loading mod manager...</p>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-white shadow-sm rounded-lg p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <Package className="h-6 w-6 text-primary-600" />
            <h1 className="text-2xl font-bold text-gray-900">Mod Manager</h1>
          </div>
          <div className="flex items-center space-x-2">
            {status?.world_exists ? (
              <>
                <CheckCircle className="h-5 w-5 text-success-600" />
                <span className="text-sm font-medium text-success-600">World Ready</span>
              </>
            ) : (
              <>
                <AlertCircle className="h-5 w-5 text-error-600" />
                <span className="text-sm font-medium text-error-600">No World</span>
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

      {/* World Not Found Warning */}
      {!status?.world_exists && (
        <div className="bg-warning-50 border border-warning-200 text-warning-700 px-4 py-3 rounded-md">
          <div className="flex items-center">
            <AlertTriangle className="h-5 w-5 mr-2" />
            <span>⚠️ Please set up a world folder before managing mods.</span>
          </div>
        </div>
      )}

      {/* Mod Management Interface - Only show if world exists */}
      {status?.world_exists && (
        <>
          {/* Stats Grid */}
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-3">
            <div className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-gray-600">Active Mods</p>
                  <p className="text-2xl font-bold text-gray-900">{status.mods_count}</p>
                </div>
                <div className="p-3 rounded-full bg-primary-50">
                  <Package className="h-6 w-6 text-primary-600" />
                </div>
              </div>
            </div>

            <div className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-gray-600">Disabled Mods</p>
                  <p className="text-2xl font-bold text-gray-900">{status.disabled_count}</p>
                </div>
                <div className="p-3 rounded-full bg-warning-50">
                  <Settings className="h-6 w-6 text-warning-600" />
                </div>
              </div>
            </div>

            <div className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-gray-600">Fabric API</p>
                  <p className={`text-2xl font-bold ${status.fabric_api_installed ? 'text-success-600' : 'text-error-600'}`}>
                    {status.fabric_api_installed ? 'Installed' : 'Missing'}
                  </p>
                </div>
                <div className={`p-3 rounded-full ${status.fabric_api_installed ? 'bg-success-50' : 'bg-error-50'}`}>
                  <CheckCircle className={`h-6 w-6 ${status.fabric_api_installed ? 'text-success-600' : 'text-error-600'}`} />
                </div>
              </div>
            </div>
          </div>

          {/* Fabric API Section */}
          {!status.fabric_api_installed && (
            <div className="bg-white rounded-lg shadow-sm p-6">
              <h3 className="text-lg font-semibold text-gray-900 mb-4">Fabric API Required</h3>
              <p className="text-gray-600 mb-4">
                Fabric API is required for most Fabric mods to work properly. Install it now to ensure compatibility.
              </p>
              <button
                onClick={handleInstallFabricAPI}
                disabled={installing}
                className="flex items-center space-x-2 bg-primary-600 text-white px-4 py-2 rounded-md hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {installing ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Download className="h-4 w-4" />
                )}
                <span>Install Fabric API</span>
              </button>
            </div>
          )}

          {/* Upload Section */}
          <div className="bg-white rounded-lg shadow-sm p-6">
            <h3 className="text-lg font-semibold text-gray-900 mb-4">Upload Mod</h3>
            <div className="flex items-center space-x-4">
              <label className="flex items-center space-x-2 bg-secondary-600 text-white px-4 py-2 rounded-md hover:bg-secondary-700 cursor-pointer transition-colors">
                <Upload className="h-4 w-4" />
                <span>Choose .jar File</span>
                <input
                  type="file"
                  accept=".jar"
                  onChange={handleFileUpload}
                  className="hidden"
                  disabled={uploading}
                />
              </label>
              {uploading && <Loader2 className="h-5 w-5 animate-spin text-primary-600" />}
            </div>
          </div>

          {/* Server Control */}
          <div className="bg-white rounded-lg shadow-sm p-6">
            <h3 className="text-lg font-semibold text-gray-900 mb-4">Server Control</h3>
            <div className="flex items-center space-x-4">
              <button
                onClick={handleRestartWithRecovery}
                disabled={restarting}
                className="flex items-center space-x-2 bg-warning-600 text-white px-4 py-2 rounded-md hover:bg-warning-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {restarting ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <RotateCcw className="h-4 w-4" />
                )}
                <span>Restart with Auto-Recovery</span>
              </button>
              <p className="text-sm text-gray-600">
                Automatically disables problematic mods if server fails to start
              </p>
            </div>
          </div>

          {/* Recovery Log */}
          {recoveryLog.length > 0 && (
            <div className="bg-white rounded-lg shadow-sm p-6">
              <h3 className="text-lg font-semibold text-gray-900 mb-4">Recovery Log</h3>
              <div className="bg-gray-900 text-green-400 p-4 rounded-md font-mono text-sm max-h-64 overflow-y-auto">
                {recoveryLog.map((line, index) => (
                  <div key={index}>{line}</div>
                ))}
              </div>
            </div>
          )}

          {/* Active Mods List */}
          <div className="bg-white rounded-lg shadow-sm p-6">
            <h3 className="text-lg font-semibold text-gray-900 mb-4">Active Mods ({status.mods_count})</h3>
            {status.mods.length === 0 ? (
              <div className="text-center py-8">
                <Package className="h-12 w-12 text-gray-400 mx-auto mb-4" />
                <p className="text-gray-600">No mods installed</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Name
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Size
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Modified
                      </th>
                      <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Actions
                      </th>
                    </tr>
                  </thead>
                  <tbody className="bg-white divide-y divide-gray-200">
                    {status.mods.map((mod, index) => (
                      <tr key={index} className="hover:bg-gray-50">
                        <td className="px-6 py-4 whitespace-nowrap">
                          <div className="flex items-center">
                            <FileText className="h-5 w-5 text-gray-400 mr-3" />
                            <div>
                              <span className="text-sm font-medium text-gray-900">{mod.filename}</span>
                              {mod.is_fabric_api && (
                                <span className="ml-2 px-2 py-1 bg-primary-100 text-primary-800 text-xs rounded-full">
                                  Fabric API
                                </span>
                              )}
                            </div>
                          </div>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                          {formatFileSize(mod.size)}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                          {formatDate(mod.modified)}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                          <button
                            onClick={() => handleDeleteMod(mod.filename)}
                            className="text-error-600 hover:text-error-900"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Disabled Mods List */}
          {status.disabled_count > 0 && (
            <div className="bg-white rounded-lg shadow-sm p-6">
              <h3 className="text-lg font-semibold text-gray-900 mb-4">Disabled Mods ({status.disabled_count})</h3>
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Name
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Size
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Modified
                      </th>
                      <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Actions
                      </th>
                    </tr>
                  </thead>
                  <tbody className="bg-white divide-y divide-gray-200">
                    {status.disabled_mods.map((mod, index) => (
                      <tr key={index} className="hover:bg-gray-50 opacity-60">
                        <td className="px-6 py-4 whitespace-nowrap">
                          <div className="flex items-center">
                            <FileText className="h-5 w-5 text-gray-400 mr-3" />
                            <span className="text-sm font-medium text-gray-900">{mod.filename}</span>
                          </div>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                          {formatFileSize(mod.size)}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                          {formatDate(mod.modified)}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                          <div className="flex items-center justify-end space-x-2">
                            <button
                              onClick={() => handleEnableMod(mod.filename)}
                              className="text-success-600 hover:text-success-900"
                            >
                              <Play className="h-4 w-4" />
                            </button>
                            <button
                              onClick={() => handleDeleteMod(mod.filename)}
                              className="text-error-600 hover:text-error-900"
                            >
                              <Trash2 className="h-4 w-4" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}

export default ModManager