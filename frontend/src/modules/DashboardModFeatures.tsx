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
  const [messageText, setMessageText] = useState('')

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
      if (command === 'sendMessage') {
        await api.post('/api/ws-conn/send_message', { content: messageText })
        setCommandOutput(`[Dashboard Mod] Sent message: "${messageText}"`)
      } else if (command === 'setDay') {
        await api.post('/api/ws-conn/set_day')
        setCommandOutput(`[Dashboard Mod] Set time to day.`)
      } } else if (command === 'listPlayers') {
        const response = await api.get('/api/ws-conn/list_players')
        const players = Array.isArray(response.data.players)
          ? response.data.players.map((p: any) => (typeof p === 'string' ? p : JSON.stringify(p)))
          : []
        setCommandOutput(`[Dashboard Mod] Online Players:\n${players.join(', ')}`)
      } else {
        setCommandOutput(`[Dashboard Mod] Unknown command.`)
      }
    } catch (error: any) {
      console.error(error)
      setError('Failed to execute command')
    } finally {
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
          <p className="text-sm text-red-600 mt-2">{error}</p>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="bg-white shadow-sm rounded-lg p-6">
        <div className="flex items-center space-x-3">
          <Settings className="h-6 w-6 text-primary-600" />
          <h1 className="text-2xl font-bold text-gray-900">Dashboard Mod Features</h1>
        </div>
      </div>

      <div className="bg-white shadow-sm rounded-lg p-6 space-y-4">
        <div className="space-y-2">
          <label htmlFor="messageInput" className="block text-sm font-medium text-gray-700">
            Send Message to Players
          </label>
          <input
            id="messageInput"
            type="text"
            className="w-full border border-gray-300 rounded-md px-3 py-2"
            value={messageText}
            onChange={(e) => setMessageText(e.target.value)}
            placeholder="Enter your message here"
          />
          <button
            onClick={() => executeCommand('sendMessage')}
            disabled={executing}
            className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
          >
            Send Message
          </button>
        </div>

        <div className="flex space-x-4">
          <button
            onClick={() => executeCommand('setDay')}
            disabled={executing}
            className="px-4 py-2 bg-green-600 text-white rounded hover:bg-green-700 disabled:opacity-50"
          >
            Set Day
          </button>

          <button
            onClick={() => executeCommand('listPlayers')}
            disabled={executing}
            className="px-4 py-2 bg-purple-600 text-white rounded hover:bg-purple-700 disabled:opacity-50"
          >
            List Players
          </button>
        </div>

        {commandOutput && (
          <pre className="bg-gray-100 text-sm rounded p-3 whitespace-pre-wrap">
            {commandOutput}
          </pre>
        )}

        {error && (
          <p className="text-sm text-red-600">
            {error}
          </p>
        )}
      </div>
    </div>
  )
}

export default DashboardModFeatures
