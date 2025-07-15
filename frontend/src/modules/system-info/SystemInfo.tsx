import { useEffect, useState } from "react"
import { Monitor, User as UserIcon, Clock } from "lucide-react"
import LoadingSpinner from "../components/LoadingSpinner"
import api from "../services/api"
import { useAuth } from "../contexts/AuthContext"

interface SystemInfo {
  user: string
  timestamp: string
  hostname: string
}

export default function SystemInfoPage() {
  const { user } = useAuth()
  const [info, setInfo] = useState<SystemInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")

  useEffect(() => {
    api.get("/api/modules/system-info")
      .then(res => setInfo(res.data))
      .catch(() => setError("Unable to load system information"))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-white shadow-sm rounded-lg p-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">System Info</h1>
            <p className="text-gray-600">
              Details for <span className="font-medium">{user?.username}</span>
            </p>
          </div>
        </div>
      </div>

      {error && (
        <div className="bg-red-100 text-red-700 px-4 py-3 rounded-md">
          {error}
        </div>
      )}

      {info && (
        <>
          {/* Stats Grid */}
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-3">
            <div className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-gray-600">Hostname</p>
                  <p className="text-xl font-bold text-gray-900">{info.hostname}</p>
                </div>
                <div className="p-3 rounded-full bg-primary-50">
                  <Monitor className="h-6 w-6 text-primary-600" />
                </div>
              </div>
            </div>
            <div className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-gray-600">User</p>
                  <p className="text-xl font-bold text-gray-900">{info.user}</p>
                </div>
                <div className="p-3 rounded-full bg-secondary-50">
                  <UserIcon className="h-6 w-6 text-secondary-600" />
                </div>
              </div>
            </div>
            <div className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-gray-600">Timestamp</p>
                  <p className="text-xl font-bold text-gray-900">
                    {new Date(info.timestamp).toLocaleString()}
                  </p>
                </div>
                <div className="p-3 rounded-full bg-success-50">
                  <Clock className="h-6 w-6 text-success-600" />
                </div>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
