import { Link } from 'react-router-dom'
import { Compass } from 'lucide-react'
import { Button } from '@/components/ui'

export function NotFoundPage() {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-24 text-center">
      <div className="flex size-14 items-center justify-center rounded-full bg-neutral-100 text-neutral-400">
        <Compass className="size-7" aria-hidden="true" />
      </div>
      <div>
        <h1 className="text-lg font-semibold text-neutral-900">페이지를 찾을 수 없습니다</h1>
        <p className="mt-1 text-sm text-neutral-500">요청하신 페이지가 존재하지 않거나 이동되었습니다.</p>
      </div>
      <Link to="/">
        <Button variant="secondary">홈으로 이동</Button>
      </Link>
    </div>
  )
}
