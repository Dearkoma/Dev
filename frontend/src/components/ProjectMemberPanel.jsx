import React, { useState, useEffect } from 'react'
import {
  fetchProjectMembers, addProjectMember, updateProjectMember, removeProjectMember,
  fetchProjectMyRole, fetchUsers
} from '../api'

const PROJECT_ROLES = ['OWNER', 'MAINTAINER', 'DEVELOPER', 'VIEWER']
const ROLE_LABELS = { OWNER: '负责人', MAINTAINER: '维护者', DEVELOPER: '开发者', VIEWER: '观察者' }
const ROLE_BADGE = { OWNER: 'badge-danger', MAINTAINER: 'badge-running', DEVELOPER: 'badge-success', VIEWER: 'badge-info' }

export default function ProjectMemberPanel({ projectId, projectName, onClose }) {
  const [members, setMembers] = useState([])
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [myPerm, setMyPerm] = useState({ canManage: false, canApprove: false })
  const [addUser, setAddUser] = useState('')
  const [addRole, setAddRole] = useState('DEVELOPER')

  const load = async () => {
    try {
      const [mList, uList, myRole] = await Promise.all([
        fetchProjectMembers(projectId),
        fetchUsers(),
        fetchProjectMyRole(projectId)
      ])
      setMembers(mList)
      setUsers(uList.filter(u => u.enabled !== false))
      setMyPerm({ canManage: myRole?.canManage || false, canApprove: myRole?.canApprove || false })
    } catch (e) {
      console.error('加载成员数据失败:', e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [projectId])

  const handleAdd = async () => {
    if (!addUser) return alert('请选择用户')
    try {
      await addProjectMember(projectId, addUser, addRole)
      setAddUser(''); setAddRole('DEVELOPER')
      load()
    } catch (e) { alert('添加失败: ' + e.message) }
  }

  const handleRoleChange = async (memberId, newRole) => {
    try {
      await updateProjectMember(projectId, memberId, newRole)
      load()
    } catch (e) { alert('更新失败: ' + e.message) }
  }

  const handleRemove = async (memberId, username) => {
    if (!confirm(`确定移除成员 ${username}？`)) return
    try {
      await removeProjectMember(projectId, memberId)
      load()
    } catch (e) { alert('移除失败: ' + e.message) }
  }

  // 过滤掉已是成员的用户
  const availableUsers = users.filter(u => !members.some(m => m.username === u.username))

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()} style={{ maxWidth: 600 }}>
        <h3>👥 项目成员 — {projectName}</h3>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 24 }}><div className="spinner" /></div>
        ) : (
          <>
            {/* 当前成员列表 */}
            <div style={{ marginTop: 16 }}>
              <h4 style={{ margin: '0 0 8px 0', fontSize: 14, color: '#6b7280' }}>
                当前成员 ({members.length})
              </h4>
              {members.length === 0 ? (
                <p style={{ color: '#9ca3af', fontSize: 13 }}>暂无成员</p>
              ) : (
                <table style={{ width: '100%', fontSize: 13 }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #e5e7eb' }}>
                      <th style={{ textAlign: 'left', padding: '6px 8px' }}>用户</th>
                      <th style={{ textAlign: 'left', padding: '6px 8px' }}>角色</th>
                      {myPerm.canManage && <th style={{ textAlign: 'center', padding: '6px 8px', width: 120 }}>操作</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {members.map(m => (
                      <tr key={m.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                        <td style={{ padding: '6px 8px' }}>{m.username}</td>
                        <td style={{ padding: '6px 8px' }}>
                          {myPerm.canManage ? (
                            <select
                              value={m.role}
                              onChange={e => handleRoleChange(m.id, e.target.value)}
                              style={{ fontSize: 12, padding: '2px 4px' }}
                            >
                              {PROJECT_ROLES.map(r => (
                                <option key={r} value={r}>{ROLE_LABELS[r]} ({r})</option>
                              ))}
                            </select>
                          ) : (
                            <span className={`badge ${ROLE_BADGE[m.role] || 'badge-info'}`} style={{ fontSize: 11 }}>
                              {ROLE_LABELS[m.role] || m.role}
                            </span>
                          )}
                        </td>
                        {myPerm.canManage && (
                          <td style={{ padding: '6px 8px', textAlign: 'center' }}>
                            <button className="btn btn-danger btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}
                              onClick={() => handleRemove(m.id, m.username)}>移除</button>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            {/* 添加成员 */}
            {myPerm.canManage && (
              <div style={{ marginTop: 16, padding: '12px 0 0 0', borderTop: '1px solid #e5e7eb' }}>
                <h4 style={{ margin: '0 0 8px 0', fontSize: 14, color: '#6b7280' }}>添加成员</h4>
                <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
                  <div style={{ flex: 1 }}>
                    <label style={{ fontSize: 12, color: '#6b7280' }}>用户</label>
                    <select value={addUser} onChange={e => setAddUser(e.target.value)}
                      style={{ width: '100%', marginTop: 2 }}>
                      <option value="">-- 选择用户 --</option>
                      {availableUsers.map(u => (
                        <option key={u.id} value={u.username}>
                          {u.realName ? `${u.realName} (${u.username})` : u.username}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div style={{ width: 140 }}>
                    <label style={{ fontSize: 12, color: '#6b7280' }}>角色</label>
                    <select value={addRole} onChange={e => setAddRole(e.target.value)}
                      style={{ width: '100%', marginTop: 2 }}>
                      {PROJECT_ROLES.map(r => (
                        <option key={r} value={r}>{ROLE_LABELS[r]}</option>
                      ))}
                    </select>
                  </div>
                  <button className="btn btn-primary btn-sm"
                    onClick={handleAdd} disabled={!addUser}>添加</button>
                </div>
              </div>
            )}

            {/* 权限说明 */}
            <div style={{ marginTop: 16, padding: 12, background: '#f9fafb', borderRadius: 6, fontSize: 12, color: '#6b7280' }}>
              <strong>角色说明：</strong>
              {PROJECT_ROLES.map(r => (
                <span key={r} style={{ marginLeft: 12 }}>
                  <span className={`badge ${ROLE_BADGE[r] || 'badge-info'}`}>{ROLE_LABELS[r]}</span> — {
                    r === 'OWNER' ? '管理成员、审批部署' :
                    r === 'MAINTAINER' ? '审批部署、触发构建' :
                    r === 'DEVELOPER' ? '触发构建、申请部署' :
                    '只读'
                  }
                </span>
              ))}
            </div>
          </>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn btn-outline" onClick={onClose}>关闭</button>
        </div>
      </div>
    </div>
  )
}
