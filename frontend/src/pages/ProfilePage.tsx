import type { ReactNode } from 'react'
import { User } from 'lucide-react'
import { Badge, PageHeader } from '@/components/ui'
import { useAuth } from '@/context/useAuth'
import { formatDate } from '@/utils/date'
import { userRoleLabels } from '@/utils/labels'

/**
 * The backend exposes no profile-update endpoint (UserController only has
 * POST /signup and GET /me), so this page is intentionally read-only rather
 * than presenting a form that can't actually submit anywhere.
 */
export function ProfilePage() {
  const { user } = useAuth()

  if (!user) return null

  return (
    <div className="mx-auto max-w-lg">
      <PageHeader title="내 프로필" description="GymFlow 계정 정보입니다." />

      <div className="rounded-lg border border-neutral-200 bg-white p-6">
        <div className="flex items-center gap-4">
          <span className="flex size-14 items-center justify-center rounded-full bg-neutral-100 text-neutral-400">
            <User className="size-7" aria-hidden="true" />
          </span>
          <div>
            <p className="text-lg font-semibold text-neutral-900">{user.name}</p>
            <p className="text-sm text-neutral-500">{user.email}</p>
          </div>
        </div>

        <dl className="mt-6 divide-y divide-neutral-100 border-t border-neutral-100">
          <ProfileRow label="회원 유형" value={<Badge tone="brand">{userRoleLabels[user.role]}</Badge>} />
          <ProfileRow
            label="계정 상태"
            value={<Badge tone={user.status === 'ACTIVE' ? 'success' : 'neutral'}>{user.status === 'ACTIVE' ? '활성' : '비활성'}</Badge>}
          />
          <ProfileRow label="가입일" value={formatDate(user.createdAt)} />
        </dl>
      </div>
    </div>
  )
}

function ProfileRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex items-center justify-between py-3">
      <dt className="text-sm text-neutral-500">{label}</dt>
      <dd className="text-sm">{value}</dd>
    </div>
  )
}
