import React, { useState, useEffect } from 'react'
import { 
  Upload, 
  Download, 
  Cloud, 
  HardDrive, 
  FileText, 
  AlertCircle,
  CheckCircle,
  Loader2,
  Key,
  Archive
} from 'lucide-react'
import { useAuth } from '../contexts/AuthContext'
import api from '../services/api'

interface Backup {
  name: string
  size: number
  modified: string
  is_dir: boolean
}

interface DriveStatus {
  config_exists: boolean
  connected: boolean
  rclone_installed: boolean
}

const Drive: React.FC = () => {
  const { hasRole } = useAuth()
  const [backups, setBackups] = useState<Backup[]>([])
  const [status, setStatus] = useState<DriveStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [worldName, setWorldName] = useState('world')

  useEffect(() => {
    if (hasRole('admin')) {
      fetchStatus()
      fetchBackups()
    }
  }, [hasRole])

  const fetchStatus = async () => {
    try {
      const response = await api.get('/api/drive/status')
      setStatus(response.data)
    } catch (error) {
      console.error('Error fetching status:', error)
      setError('Failed to fetch drive status')
    }
  }

  const fetchBackups = async () => {
    try {
      const response = await api.get('/api/drive/backups')
      setBackups(response.data.backups || [])
    } catch (error) {
      console.error('Error fetching backups:', error)
      setError('Failed to fetch backups')
    } finally {
      setLoading(false)
    }
  }

  const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file) return

    setUploading(true)
    setError('')
    setSuccess('')

    try {
      const formData = new FormData()
      formData.append('file', file)

      await api.post('/api/drive/upload', formData, {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      })

      setSuccess(`File ${file.name} uploaded successfully`)
      fetchBackups()
    } catch (error: any) {
      setError(error.response?.data?.error || 'Upload failed')
    } finally {
      setUploading(false)
      event.target.value = ''
    }
  }

  const handleRcloneKeyUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file) return

    if (!file.name.endsWith('.conf')) {
      setError('Please select a .conf file')
      return
    }

    setUploading(true)
    setError('')
    setSuccess('')

    try {
      const formData = new FormData()
      formData.append('file', file)

      await api.post('/api/drive/upload-rclone-key', formData, {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      })

      setSuccess('rclone configuration uploaded successfully')
      fetchStatus()
    } catch (error: any) {
      setError(error.response?.data?.error || 'Configuration upload failed')
    } finally {
      setUploading(false)
      event.target.value = ''
    }
  }

  const handleExportWorld = async () => {
    if (!worldName.trim()) {
      setError('Please enter a world name')
      return
    }

    setExporting(true)
    setError('')
    setSuccess('')

    try {
      const response = await api.post('/api/drive/export-world', {
        world_name: worldName
      })

      setSuccess(response.data.message)
      fetchBackups()
    } catch (error: any) {
      setError(error.response?.data?.error || 'Export failed')
    } finally {
      setExporting(false)
    }
  }

  const handleImportWorld = async (backupFilename: string) => {
    if (!window.confirm(`Import world from ${backupFilename}? This will overwrite existing world data.`)) {
      return
    }

    setImporting(true)
    setError('')
    setSuccess('')

    try {
      const response = await api.post('/api/drive/import-world', {
        backup_filename: backupFilename
      })

      setSuccess(response.data.message)
    } catch (error: any) {
      setError(error.response?.data?.error || 'Import failed')
    } finally {
      setImporting(false)
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

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-white shadow-sm rounded-lg p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <Cloud className="h-6 w-6 text-primary-600" />
            <h1 className="text-2xl font-bold text-gray-900">Google Drive Backup</h1>
          </div>
          <div className="flex items-center space-x-2">
            {status?.connected ? (
              <>
                <CheckCircle className="h-5 w-5 text-success-600" />
                <span className="text-sm font-medium text-success-600">Connected</span>
              </>
            ) : (
              <>
                <AlertCircle className="h-5 w-5 text-error-600" />
                <span className="text-sm font-medium text-error-600">Not Connected</span>
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

      {/* Configuration Section */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Configuration</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
          <div className="text-center">
            <div className={`text-2xl font-bold ${status?.rclone_installed ? 'text-success-600' : 'text-error-600'}`}>
              {status?.rclone_installed ? 'Installed' : 'Missing'}
            </div>
            <div className="text-sm text-gray-600">rclone</div>
          </div>
          <div className="text-center">
            <div className={`text-2xl font-bold ${status?.config_exists ? 'text-success-600' : 'text-error-600'}`}>
              {status?.config_exists ? 'Present' : 'Missing'}
            </div>
            <div className="text-sm text-gray-600">Config File</div>
          </div>
          <div className="text-center">
            <div className={`text-2xl font-bold ${status?.connected ? 'text-success-600' : 'text-error-600'}`}>
              {status?.connected ? 'Connected' : 'Disconnected'}
            </div>
            <div className="text-sm text-gray-600">Google Drive</div>
          </div>
        </div>

        <div className="flex items-center space-x-4">
          <label className="flex items-center space-x-2 bg-primary-600 text-white px-4 py-2 rounded-md hover:bg-primary-700 cursor-pointer transition-colors">
            <Key className="h-4 w-4" />
            <span>Upload rclone Config</span>
            <input
              type="file"
              accept=".conf"
              onChange={handleRcloneKeyUpload}
              className="hidden"
              disabled={uploading}
            />
          </label>
          {uploading && <Loader2 className="h-5 w-5 animate-spin text-primary-600" />}
        </div>
      </div>

      {/* World Export Section */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Export World</h3>
        <div className="flex items-center space-x-4">
          <input
            type="text"
            value={worldName}
            onChange={(e) => setWorldName(e.target.value)}
            placeholder="World name (e.g., world)"
            className="flex-1 px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
          <button
            onClick={handleExportWorld}
            disabled={exporting || !status?.connected}
            className="flex items-center space-x-2 bg-secondary-600 text-white px-4 py-2 rounded-md hover:bg-secondary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {exporting ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Archive className="h-4 w-4" />
            )}
            <span>Export World</span>
          </button>
        </div>
      </div>

      {/* File Upload Section */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Upload File</h3>
        <label className="flex items-center space-x-2 bg-primary-600 text-white px-4 py-2 rounded-md hover:bg-primary-700 cursor-pointer transition-colors w-fit">
          <Upload className="h-4 w-4" />
          <span>Choose File</span>
          <input
            type="file"
            onChange={handleFileUpload}
            className="hidden"
            disabled={uploading || !status?.connected}
          />
        </label>
      </div>

      {/* Backups List */}
      <div className="bg-white rounded-lg shadow-sm p-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold text-gray-900">Backups</h3>
          <button
            onClick={fetchBackups}
            disabled={loading}
            className="text-primary-600 hover:text-primary-700 text-sm font-medium"
          >
            Refresh
          </button>
        </div>

        {loading ? (
          <div className="text-center py-8">
            <Loader2 className="h-8 w-8 animate-spin text-primary-600 mx-auto mb-2" />
            <p className="text-gray-600">Loading backups...</p>
          </div>
        ) : backups.length === 0 ? (
          <div className="text-center py-8">
            <HardDrive className="h-12 w-12 text-gray-400 mx-auto mb-4" />
            <p className="text-gray-600">No backups found</p>
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
                {backups.map((backup, index) => (
                  <tr key={index} className="hover:bg-gray-50">
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="flex items-center">
                        <FileText className="h-5 w-5 text-gray-400 mr-3" />
                        <div className="text-sm font-medium text-gray-900">{backup.name}</div>
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                      {formatFileSize(backup.size)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                      {formatDate(backup.modified)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                      {backup.name.endsWith('.zip') && (
                        <button
                          onClick={() => handleImportWorld(backup.name)}
                          disabled={importing}
                          className="text-primary-600 hover:text-primary-900 disabled:opacity-50"
                        >
                          {importing ? (
                            <Loader2 className="h-4 w-4 animate-spin" />
                          ) : (
                            <Download className="h-4 w-4" />
                          )}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

export default Drive