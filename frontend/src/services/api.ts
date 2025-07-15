import axios from 'axios'
const api = axios.create({
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request interceptor (no token needed anymore)
api.interceptors.request.use(
  (config) => config,
  (error) => Promise.reject(error)
)

// Response interceptor to handle 401 errors
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Optional: redirect to login if not authenticated
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// Auth API
export const login = async (username: string, password: string) => {
  const response = await api.post('/api/auth/login', { username, password })
  return response.data
}

export const logout = async () => {
  try {
    await api.post('/api/auth/logout')
  } catch (error) {
    console.error('Logout error:', error)
  }
}

export const getCurrentUser = async () => {
  const response = await api.get('/api/auth/me')
  return response.data
}

// Users API
export const getUsers = async () => {
  const response = await api.get('/api/users')
  return response.data
}

export const createUser = async (userData: any) => {
  const response = await api.post('/api/users', userData)
  return response.data
}

export const updateUser = async (userId: number, userData: any) => {
  const response = await api.put(`/api/users/${userId}`, userData)
  return response.data
}

export const deleteUser = async (userId: number) => {
  await api.delete(`/api/users/${userId}`)
}

export const changePassword = async (userId: number, newPassword: string) => {
  const response = await api.post(`/api/users/${userId}/change-password`, { 
    password: newPassword 
  })
  return response.data
}

// System Info API
export const getSystemInfo = async () => {
  const response = await api.get('/api/modules/system-info')
  return response.data
}

export default api
