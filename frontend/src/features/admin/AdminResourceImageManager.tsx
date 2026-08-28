import { useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { Trash2, Upload } from 'lucide-react'
import { Button, ConfirmDialog } from '@/components/ui'
import { ImageWithFallback } from '@/components/ImageWithFallback'
import { useDeleteResourceImage, useUploadResourceImage } from './hooks'
import { useToast } from '@/context/useToast'
import { getErrorMessage } from '@/utils/getErrorMessage'

// Mirrors AdminResourceImageService.ALLOWED_CONTENT_TYPES / MAX_FILE_SIZE and
// application.yaml's spring.servlet.multipart.max-file-size (5MB). This is a
// UX pre-check only — the backend remains the authority and still validates.
const ALLOWED_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const MAX_FILE_SIZE = 5 * 1024 * 1024

interface AdminResourceImageManagerProps {
  resourceId: number
  imageUrl: string | null
  resourceName: string
}

export function AdminResourceImageManager({ resourceId, imageUrl, resourceName }: AdminResourceImageManagerProps) {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const { showToast } = useToast()

  const uploadImage = useUploadResourceImage()
  const deleteImage = useDeleteResourceImage()

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    if (!ALLOWED_TYPES.has(file.type)) {
      showToast({ tone: 'danger', title: '지원하지 않는 파일 형식', description: 'JPEG, PNG, WebP 파일만 업로드할 수 있습니다.' })
      return
    }
    if (file.size > MAX_FILE_SIZE) {
      showToast({ tone: 'danger', title: '파일 용량 초과', description: '이미지 파일은 5MB를 초과할 수 없습니다.' })
      return
    }

    uploadImage.mutate(
      { resourceId, file },
      {
        onSuccess: () => showToast({ tone: 'success', title: '이미지가 업로드되었습니다' }),
        onError: (error) => showToast({ tone: 'danger', title: '이미지 업로드 실패', description: getErrorMessage(error) }),
      },
    )
  }

  function handleDeleteConfirm() {
    deleteImage.mutate(resourceId, {
      onSuccess: () => {
        setDeleteDialogOpen(false)
        showToast({ tone: 'success', title: '이미지가 삭제되었습니다' })
      },
      onError: (error) => showToast({ tone: 'danger', title: '이미지 삭제 실패', description: getErrorMessage(error) }),
    })
  }

  return (
    <div>
      <ImageWithFallback src={imageUrl} alt={resourceName} className="max-h-56 rounded-md" />
      <div className="mt-3 flex flex-wrap gap-2">
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="hidden"
          onChange={handleFileChange}
        />
        <Button
          type="button"
          variant="secondary"
          size="sm"
          leftIcon={<Upload className="size-4" aria-hidden="true" />}
          loading={uploadImage.isPending}
          onClick={() => fileInputRef.current?.click()}
        >
          {imageUrl ? '이미지 교체' : '이미지 업로드'}
        </Button>
        {imageUrl && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            leftIcon={<Trash2 className="size-4" aria-hidden="true" />}
            onClick={() => setDeleteDialogOpen(true)}
          >
            이미지 삭제
          </Button>
        )}
      </div>
      <p className="mt-1.5 text-xs text-neutral-400">JPEG, PNG, WebP · 최대 5MB</p>

      <ConfirmDialog
        open={deleteDialogOpen}
        title="이미지를 삭제할까요?"
        description="삭제된 이미지는 복구할 수 없습니다."
        confirmLabel="삭제"
        loading={deleteImage.isPending}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteDialogOpen(false)}
      />
    </div>
  )
}
